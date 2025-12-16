#!/usr/bin/env python3
"""
BiLSTM + Attention model for Pinyin to Chinese character conversion.
Trains a seq2seq model and exports to ONNX with INT8 quantization.

Usage:
    python train_bilstm_pinyin.py --dataset ../pinyin_dataset.json --epochs 30
"""

import argparse
import json
import os
import random
import time
from collections import Counter
from typing import List, Tuple, Dict

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader
from torch.nn.utils.rnn import pad_sequence, pack_padded_sequence, pad_packed_sequence

# Set seeds for reproducibility
SEED = 42
random.seed(SEED)
np.random.seed(SEED)
torch.manual_seed(SEED)

# Special tokens
PAD_TOKEN = '<PAD>'
SOS_TOKEN = '<SOS>'
EOS_TOKEN = '<EOS>'
UNK_TOKEN = '<UNK>'

PAD_IDX = 0
SOS_IDX = 1
EOS_IDX = 2
UNK_IDX = 3


class Vocabulary:
    """Vocabulary for mapping tokens to indices."""

    def __init__(self):
        self.token2idx = {PAD_TOKEN: PAD_IDX, SOS_TOKEN: SOS_IDX,
                          EOS_TOKEN: EOS_IDX, UNK_TOKEN: UNK_IDX}
        self.idx2token = {PAD_IDX: PAD_TOKEN, SOS_IDX: SOS_TOKEN,
                          EOS_IDX: EOS_TOKEN, UNK_IDX: UNK_TOKEN}
        self.token_counts = Counter()

    def add_token(self, token: str):
        self.token_counts[token] += 1
        if token not in self.token2idx:
            idx = len(self.token2idx)
            self.token2idx[token] = idx
            self.idx2token[idx] = token

    def add_tokens(self, tokens: List[str]):
        for token in tokens:
            self.add_token(token)

    def __len__(self):
        return len(self.token2idx)

    def encode(self, tokens: List[str]) -> List[int]:
        return [self.token2idx.get(t, UNK_IDX) for t in tokens]

    def decode(self, indices: List[int]) -> List[str]:
        return [self.idx2token.get(i, UNK_TOKEN) for i in indices]

    def save(self, path: str):
        with open(path, 'w', encoding='utf-8') as f:
            json.dump({
                'token2idx': self.token2idx,
                'idx2token': {str(k): v for k, v in self.idx2token.items()}
            }, f, ensure_ascii=False, indent=2)

    @classmethod
    def load(cls, path: str) -> 'Vocabulary':
        vocab = cls()
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)
            vocab.token2idx = data['token2idx']
            vocab.idx2token = {int(k): v for k, v in data['idx2token'].items()}
        return vocab


class PinyinDataset(Dataset):
    """Dataset for pinyin to hanzi conversion."""

    def __init__(self, samples: List[Dict], pinyin_vocab: Vocabulary,
                 hanzi_vocab: Vocabulary, max_len: int = 64):
        self.samples = samples
        self.pinyin_vocab = pinyin_vocab
        self.hanzi_vocab = hanzi_vocab
        self.max_len = max_len

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        sample = self.samples[idx]
        pinyin = sample['pinyin'].split()
        hanzi = list(sample['hanzi'])

        # Truncate if too long
        pinyin = pinyin[:self.max_len]
        hanzi = hanzi[:self.max_len]

        # Encode
        pinyin_ids = self.pinyin_vocab.encode(pinyin)
        hanzi_ids = [SOS_IDX] + self.hanzi_vocab.encode(hanzi) + [EOS_IDX]

        return {
            'pinyin': torch.tensor(pinyin_ids, dtype=torch.long),
            'hanzi': torch.tensor(hanzi_ids, dtype=torch.long),
            'pinyin_len': len(pinyin_ids),
            'hanzi_len': len(hanzi_ids)
        }


def collate_fn(batch):
    """Collate function for DataLoader."""
    pinyin = [item['pinyin'] for item in batch]
    hanzi = [item['hanzi'] for item in batch]
    pinyin_lens = torch.tensor([item['pinyin_len'] for item in batch])
    hanzi_lens = torch.tensor([item['hanzi_len'] for item in batch])

    # Pad sequences
    pinyin_padded = pad_sequence(pinyin, batch_first=True, padding_value=PAD_IDX)
    hanzi_padded = pad_sequence(hanzi, batch_first=True, padding_value=PAD_IDX)

    return {
        'pinyin': pinyin_padded,
        'hanzi': hanzi_padded,
        'pinyin_lens': pinyin_lens,
        'hanzi_lens': hanzi_lens
    }


class Attention(nn.Module):
    """Bahdanau attention mechanism."""

    def __init__(self, encoder_dim: int, decoder_dim: int, attention_dim: int):
        super().__init__()
        self.encoder_att = nn.Linear(encoder_dim, attention_dim)
        self.decoder_att = nn.Linear(decoder_dim, attention_dim)
        self.full_att = nn.Linear(attention_dim, 1)

    def forward(self, encoder_outputs, decoder_hidden):
        # encoder_outputs: (batch, seq_len, encoder_dim)
        # decoder_hidden: (batch, decoder_dim)

        att1 = self.encoder_att(encoder_outputs)  # (batch, seq_len, attention_dim)
        att2 = self.decoder_att(decoder_hidden).unsqueeze(1)  # (batch, 1, attention_dim)

        att = torch.tanh(att1 + att2)  # (batch, seq_len, attention_dim)
        scores = self.full_att(att).squeeze(-1)  # (batch, seq_len)

        weights = F.softmax(scores, dim=-1)  # (batch, seq_len)
        context = torch.bmm(weights.unsqueeze(1), encoder_outputs).squeeze(1)  # (batch, encoder_dim)

        return context, weights


class Encoder(nn.Module):
    """Bidirectional LSTM encoder."""

    def __init__(self, vocab_size: int, embed_dim: int, hidden_dim: int,
                 num_layers: int = 2, dropout: float = 0.3):
        super().__init__()
        self.embedding = nn.Embedding(vocab_size, embed_dim, padding_idx=PAD_IDX)
        self.lstm = nn.LSTM(embed_dim, hidden_dim, num_layers=num_layers,
                           batch_first=True, bidirectional=True, dropout=dropout if num_layers > 1 else 0)
        self.dropout = nn.Dropout(dropout)
        self.hidden_dim = hidden_dim
        self.num_layers = num_layers

    def forward(self, x, lengths=None):
        # x: (batch, seq_len)
        embedded = self.dropout(self.embedding(x))  # (batch, seq_len, embed_dim)

        # For MPS compatibility, don't use packed sequences
        outputs, (hidden, cell) = self.lstm(embedded)
        # outputs: (batch, seq_len, hidden_dim*2)

        # Combine bidirectional hidden states
        # hidden: (num_layers*2, batch, hidden_dim)
        batch_size = x.size(0)
        hidden = hidden.view(self.num_layers, 2, batch_size, self.hidden_dim)
        hidden = torch.cat([hidden[:, 0, :, :], hidden[:, 1, :, :]], dim=-1)  # (num_layers, batch, hidden_dim*2)

        cell = cell.view(self.num_layers, 2, batch_size, self.hidden_dim)
        cell = torch.cat([cell[:, 0, :, :], cell[:, 1, :, :]], dim=-1)  # (num_layers, batch, hidden_dim*2)

        return outputs, hidden, cell


class Decoder(nn.Module):
    """LSTM decoder with attention."""

    def __init__(self, vocab_size: int, embed_dim: int, hidden_dim: int,
                 encoder_dim: int, attention_dim: int, num_layers: int = 2,
                 dropout: float = 0.3):
        super().__init__()
        self.vocab_size = vocab_size
        self.embedding = nn.Embedding(vocab_size, embed_dim, padding_idx=PAD_IDX)
        self.attention = Attention(encoder_dim, hidden_dim, attention_dim)
        self.lstm = nn.LSTM(embed_dim + encoder_dim, hidden_dim, num_layers=num_layers,
                           batch_first=True, dropout=dropout if num_layers > 1 else 0)
        self.fc = nn.Linear(hidden_dim, vocab_size)
        self.dropout = nn.Dropout(dropout)

    def forward(self, input_token, encoder_outputs, hidden, cell):
        # input_token: (batch,)
        # encoder_outputs: (batch, seq_len, encoder_dim)
        # hidden, cell: (num_layers, batch, hidden_dim)

        embedded = self.dropout(self.embedding(input_token.unsqueeze(1)))  # (batch, 1, embed_dim)

        # Get attention context
        context, _ = self.attention(encoder_outputs, hidden[-1])  # (batch, encoder_dim)

        # Concatenate embedded input with context
        lstm_input = torch.cat([embedded, context.unsqueeze(1)], dim=-1)  # (batch, 1, embed_dim + encoder_dim)

        output, (hidden, cell) = self.lstm(lstm_input, (hidden, cell))  # output: (batch, 1, hidden_dim)

        prediction = self.fc(output.squeeze(1))  # (batch, vocab_size)

        return prediction, hidden, cell


class Seq2SeqModel(nn.Module):
    """Complete Seq2Seq model with attention."""

    def __init__(self, encoder: Encoder, decoder: Decoder, device: torch.device):
        super().__init__()
        self.encoder = encoder
        self.decoder = decoder
        self.device = device

    def forward(self, src, src_lens, trg, teacher_forcing_ratio: float = 0.5):
        # src: (batch, src_len)
        # trg: (batch, trg_len) - includes SOS at start

        batch_size = src.size(0)
        trg_len = trg.size(1)
        trg_vocab_size = self.decoder.vocab_size

        # Tensor to store decoder outputs
        outputs = torch.zeros(batch_size, trg_len - 1, trg_vocab_size).to(self.device)

        # Encode
        encoder_outputs, hidden, cell = self.encoder(src, src_lens)

        # First decoder input is SOS token
        decoder_input = trg[:, 0]

        for t in range(1, trg_len):
            output, hidden, cell = self.decoder(decoder_input, encoder_outputs, hidden, cell)
            outputs[:, t - 1] = output

            # Teacher forcing
            use_teacher_forcing = random.random() < teacher_forcing_ratio
            top1 = output.argmax(1)
            decoder_input = trg[:, t] if use_teacher_forcing else top1

        return outputs

    def inference(self, src, src_lens, max_len: int = 64):
        """Greedy decoding for inference."""
        batch_size = src.size(0)

        # Encode
        encoder_outputs, hidden, cell = self.encoder(src, src_lens)

        # Start with SOS
        decoder_input = torch.full((batch_size,), SOS_IDX, dtype=torch.long, device=self.device)

        outputs = []
        for _ in range(max_len):
            output, hidden, cell = self.decoder(decoder_input, encoder_outputs, hidden, cell)
            top1 = output.argmax(1)
            outputs.append(top1)

            # Stop if all sequences have produced EOS
            if (top1 == EOS_IDX).all():
                break

            decoder_input = top1

        return torch.stack(outputs, dim=1)


def train_epoch(model, dataloader, optimizer, criterion, clip: float = 1.0):
    """Train for one epoch."""
    model.train()
    total_loss = 0

    for batch in dataloader:
        pinyin = batch['pinyin'].to(model.device)
        hanzi = batch['hanzi'].to(model.device)
        pinyin_lens = batch['pinyin_lens']

        optimizer.zero_grad()

        # Forward pass
        outputs = model(pinyin, pinyin_lens, hanzi, teacher_forcing_ratio=0.5)

        # Calculate loss (ignore padding)
        # outputs: (batch, trg_len-1, vocab_size)
        # target: hanzi[:, 1:] (without SOS)
        output_dim = outputs.shape[-1]
        outputs = outputs.reshape(-1, output_dim)
        target = hanzi[:, 1:].reshape(-1)

        loss = criterion(outputs, target)
        loss.backward()

        # Gradient clipping
        torch.nn.utils.clip_grad_norm_(model.parameters(), clip)

        optimizer.step()
        total_loss += loss.item()

    return total_loss / len(dataloader)


def evaluate(model, dataloader, criterion):
    """Evaluate model."""
    model.eval()
    total_loss = 0

    with torch.no_grad():
        for batch in dataloader:
            pinyin = batch['pinyin'].to(model.device)
            hanzi = batch['hanzi'].to(model.device)
            pinyin_lens = batch['pinyin_lens']

            outputs = model(pinyin, pinyin_lens, hanzi, teacher_forcing_ratio=0)

            output_dim = outputs.shape[-1]
            outputs = outputs.reshape(-1, output_dim)
            target = hanzi[:, 1:].reshape(-1)

            loss = criterion(outputs, target)
            total_loss += loss.item()

    return total_loss / len(dataloader)


def calculate_accuracy(model, dataloader, hanzi_vocab):
    """Calculate character-level accuracy."""
    model.eval()
    total_chars = 0
    correct_chars = 0

    with torch.no_grad():
        for batch in dataloader:
            pinyin = batch['pinyin'].to(model.device)
            pinyin_lens = batch['pinyin_lens']
            hanzi = batch['hanzi']  # Keep on CPU for comparison

            # Inference
            predictions = model.inference(pinyin, pinyin_lens)

            # Compare character by character
            for i in range(len(pinyin)):
                pred = predictions[i].cpu().tolist()
                target = hanzi[i, 1:].tolist()  # Skip SOS

                # Remove padding and EOS
                pred = [p for p in pred if p not in [PAD_IDX, EOS_IDX]]
                target = [t for t in target if t not in [PAD_IDX, EOS_IDX]]

                # Compare
                for j in range(min(len(pred), len(target))):
                    total_chars += 1
                    if pred[j] == target[j]:
                        correct_chars += 1

                # Count remaining target chars as incorrect
                total_chars += abs(len(pred) - len(target))

    return correct_chars / total_chars if total_chars > 0 else 0


def export_to_onnx(model, pinyin_vocab, hanzi_vocab, output_path: str, max_len: int = 64):
    """Export model to ONNX format."""
    model.eval()

    # Create dummy input
    batch_size = 1
    seq_len = 20
    dummy_src = torch.randint(4, len(pinyin_vocab), (batch_size, seq_len)).to(model.device)
    dummy_src_lens = torch.tensor([seq_len])

    # Export encoder
    class EncoderWrapper(nn.Module):
        def __init__(self, encoder):
            super().__init__()
            self.encoder = encoder

        def forward(self, src):
            # Fixed length for ONNX
            lengths = torch.tensor([src.size(1)])
            outputs, hidden, cell = self.encoder(src, lengths)
            return outputs, hidden, cell

    encoder_wrapper = EncoderWrapper(model.encoder)
    encoder_path = output_path.replace('.onnx', '_encoder.onnx')

    torch.onnx.export(
        encoder_wrapper,
        (dummy_src,),
        encoder_path,
        input_names=['pinyin_ids'],
        output_names=['encoder_outputs', 'hidden', 'cell'],
        dynamic_axes={
            'pinyin_ids': {0: 'batch', 1: 'seq_len'},
            'encoder_outputs': {0: 'batch', 1: 'seq_len'}
        },
        opset_version=14
    )
    print(f"Encoder exported to {encoder_path}")

    # Export decoder step
    class DecoderStepWrapper(nn.Module):
        def __init__(self, decoder):
            super().__init__()
            self.decoder = decoder

        def forward(self, input_token, encoder_outputs, hidden, cell):
            output, new_hidden, new_cell = self.decoder(input_token, encoder_outputs, hidden, cell)
            return output, new_hidden, new_cell

    decoder_wrapper = DecoderStepWrapper(model.decoder)
    decoder_path = output_path.replace('.onnx', '_decoder.onnx')

    # Create dummy decoder inputs
    encoder_outputs, hidden, cell = model.encoder(dummy_src, dummy_src_lens)
    dummy_token = torch.tensor([SOS_IDX]).to(model.device)

    torch.onnx.export(
        decoder_wrapper,
        (dummy_token, encoder_outputs, hidden, cell),
        decoder_path,
        input_names=['input_token', 'encoder_outputs', 'hidden', 'cell'],
        output_names=['output', 'new_hidden', 'new_cell'],
        dynamic_axes={
            'encoder_outputs': {0: 'batch', 1: 'seq_len'}
        },
        opset_version=14
    )
    print(f"Decoder exported to {decoder_path}")

    return encoder_path, decoder_path


def quantize_onnx_model(input_path: str, output_path: str):
    """Apply INT8 quantization to ONNX model."""
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType

        quantize_dynamic(
            input_path,
            output_path,
            weight_type=QuantType.QInt8
        )

        # Get file sizes
        original_size = os.path.getsize(input_path) / (1024 * 1024)
        quantized_size = os.path.getsize(output_path) / (1024 * 1024)

        print(f"Quantized {input_path}")
        print(f"  Original: {original_size:.2f} MB")
        print(f"  Quantized: {quantized_size:.2f} MB")
        print(f"  Reduction: {(1 - quantized_size/original_size)*100:.1f}%")

        return output_path
    except ImportError:
        print("Warning: onnxruntime-tools not installed, skipping quantization")
        return input_path


def main():
    import sys
    # Force unbuffered output
    sys.stdout.reconfigure(line_buffering=True)

    parser = argparse.ArgumentParser(description='Train BiLSTM+Attention Pinyin model')
    parser.add_argument('--dataset', type=str, default='../pinyin_dataset.json',
                       help='Path to training dataset')
    parser.add_argument('--output', type=str, default='bilstm_pinyin.onnx',
                       help='Output ONNX model path')
    parser.add_argument('--epochs', type=int, default=30,
                       help='Number of training epochs')
    parser.add_argument('--batch-size', type=int, default=64,
                       help='Batch size')
    parser.add_argument('--embed-dim', type=int, default=128,
                       help='Embedding dimension')
    parser.add_argument('--hidden-dim', type=int, default=256,
                       help='LSTM hidden dimension')
    parser.add_argument('--attention-dim', type=int, default=128,
                       help='Attention dimension')
    parser.add_argument('--num-layers', type=int, default=2,
                       help='Number of LSTM layers')
    parser.add_argument('--dropout', type=float, default=0.3,
                       help='Dropout rate')
    parser.add_argument('--lr', type=float, default=0.001,
                       help='Learning rate')
    parser.add_argument('--max-len', type=int, default=64,
                       help='Maximum sequence length')
    parser.add_argument('--val-split', type=float, default=0.1,
                       help='Validation split ratio')
    parser.add_argument('--no-quantize', action='store_true',
                       help='Skip INT8 quantization')
    parser.add_argument('--device', type=str, default='auto',
                       help='Device: auto, mps, cuda, cpu')
    args = parser.parse_args()

    # Device selection
    if args.device == 'auto':
        if torch.backends.mps.is_available():
            device = torch.device('mps')
        elif torch.cuda.is_available():
            device = torch.device('cuda')
        else:
            device = torch.device('cpu')
    else:
        device = torch.device(args.device)

    print(f"Using device: {device}", flush=True)
    if device.type == 'mps':
        print("Apple Silicon GPU (MPS) enabled for training!", flush=True)

    # Load dataset
    print(f"\nLoading dataset from {args.dataset}...", flush=True)
    with open(args.dataset, 'r', encoding='utf-8') as f:
        data = json.load(f)
    samples = data['data']
    print(f"Loaded {len(samples)} samples")

    # Build vocabularies
    print("\nBuilding vocabularies...")
    pinyin_vocab = Vocabulary()
    hanzi_vocab = Vocabulary()

    for sample in samples:
        pinyin_vocab.add_tokens(sample['pinyin'].split())
        hanzi_vocab.add_tokens(list(sample['hanzi']))

    print(f"Pinyin vocabulary size: {len(pinyin_vocab)}")
    print(f"Hanzi vocabulary size: {len(hanzi_vocab)}")

    # Save vocabularies
    pinyin_vocab.save(args.output.replace('.onnx', '_pinyin_vocab.json'))
    hanzi_vocab.save(args.output.replace('.onnx', '_hanzi_vocab.json'))

    # Split dataset
    random.shuffle(samples)
    val_size = int(len(samples) * args.val_split)
    train_samples = samples[val_size:]
    val_samples = samples[:val_size]

    print(f"\nTrain samples: {len(train_samples)}")
    print(f"Validation samples: {len(val_samples)}")

    # Create datasets and dataloaders
    train_dataset = PinyinDataset(train_samples, pinyin_vocab, hanzi_vocab, args.max_len)
    val_dataset = PinyinDataset(val_samples, pinyin_vocab, hanzi_vocab, args.max_len)

    train_loader = DataLoader(train_dataset, batch_size=args.batch_size,
                             shuffle=True, collate_fn=collate_fn, num_workers=0)
    val_loader = DataLoader(val_dataset, batch_size=args.batch_size,
                           shuffle=False, collate_fn=collate_fn, num_workers=0)

    # Create model
    print("\nCreating model...")
    encoder = Encoder(
        vocab_size=len(pinyin_vocab),
        embed_dim=args.embed_dim,
        hidden_dim=args.hidden_dim,
        num_layers=args.num_layers,
        dropout=args.dropout
    )

    decoder = Decoder(
        vocab_size=len(hanzi_vocab),
        embed_dim=args.embed_dim,
        hidden_dim=args.hidden_dim * 2,  # *2 for bidirectional encoder
        encoder_dim=args.hidden_dim * 2,
        attention_dim=args.attention_dim,
        num_layers=args.num_layers,
        dropout=args.dropout
    )

    model = Seq2SeqModel(encoder, decoder, device).to(device)

    # Count parameters
    total_params = sum(p.numel() for p in model.parameters())
    trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    print(f"Total parameters: {total_params:,}")
    print(f"Trainable parameters: {trainable_params:,}")
    print(f"Estimated FP32 size: {total_params * 4 / (1024*1024):.2f} MB")
    print(f"Estimated INT8 size: {total_params / (1024*1024):.2f} MB")

    # Optimizer and loss
    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr)
    scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(optimizer, mode='min',
                                                           factor=0.5, patience=3)
    criterion = nn.CrossEntropyLoss(ignore_index=PAD_IDX)

    # Training loop
    print("\n" + "="*60)
    print("Starting training...")
    print("="*60)

    best_val_loss = float('inf')
    best_model_state = None

    for epoch in range(args.epochs):
        start_time = time.time()

        train_loss = train_epoch(model, train_loader, optimizer, criterion)
        val_loss = evaluate(model, val_loader, criterion)

        # Learning rate scheduling
        scheduler.step(val_loss)

        elapsed = time.time() - start_time

        # Calculate accuracy every 5 epochs
        if (epoch + 1) % 5 == 0 or epoch == args.epochs - 1:
            accuracy = calculate_accuracy(model, val_loader, hanzi_vocab)
            print(f"Epoch {epoch+1:3d}/{args.epochs} | "
                  f"Train Loss: {train_loss:.4f} | Val Loss: {val_loss:.4f} | "
                  f"Accuracy: {accuracy*100:.2f}% | Time: {elapsed:.1f}s")
        else:
            print(f"Epoch {epoch+1:3d}/{args.epochs} | "
                  f"Train Loss: {train_loss:.4f} | Val Loss: {val_loss:.4f} | "
                  f"Time: {elapsed:.1f}s")

        # Save best model
        if val_loss < best_val_loss:
            best_val_loss = val_loss
            best_model_state = {k: v.cpu().clone() for k, v in model.state_dict().items()}

    # Load best model
    model.load_state_dict(best_model_state)
    model.to(device)

    # Final accuracy
    print("\n" + "="*60)
    print("Final evaluation...")
    final_accuracy = calculate_accuracy(model, val_loader, hanzi_vocab)
    print(f"Final character accuracy: {final_accuracy*100:.2f}%")

    # Save PyTorch model
    torch_path = args.output.replace('.onnx', '.pt')
    torch.save({
        'model_state_dict': model.state_dict(),
        'encoder_config': {
            'vocab_size': len(pinyin_vocab),
            'embed_dim': args.embed_dim,
            'hidden_dim': args.hidden_dim,
            'num_layers': args.num_layers
        },
        'decoder_config': {
            'vocab_size': len(hanzi_vocab),
            'embed_dim': args.embed_dim,
            'hidden_dim': args.hidden_dim * 2,
            'encoder_dim': args.hidden_dim * 2,
            'attention_dim': args.attention_dim,
            'num_layers': args.num_layers
        }
    }, torch_path)
    print(f"\nPyTorch model saved to {torch_path}")

    # Export to ONNX
    print("\n" + "="*60)
    print("Exporting to ONNX...")
    encoder_path, decoder_path = export_to_onnx(model, pinyin_vocab, hanzi_vocab, args.output)

    # Quantize
    if not args.no_quantize:
        print("\n" + "="*60)
        print("Applying INT8 quantization...")
        encoder_quant_path = encoder_path.replace('.onnx', '_int8.onnx')
        decoder_quant_path = decoder_path.replace('.onnx', '_int8.onnx')

        quantize_onnx_model(encoder_path, encoder_quant_path)
        quantize_onnx_model(decoder_path, decoder_quant_path)

        # Total size
        total_size = (os.path.getsize(encoder_quant_path) +
                     os.path.getsize(decoder_quant_path)) / (1024*1024)
        print(f"\nTotal quantized model size: {total_size:.2f} MB")

    print("\n" + "="*60)
    print("Training complete!")
    print("="*60)


if __name__ == '__main__':
    main()
