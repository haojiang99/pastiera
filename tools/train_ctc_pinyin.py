#!/usr/bin/env python3
"""
CTC-based BiLSTM Pinyin-to-Chinese Training Script

Uses CTC (Connectionist Temporal Classification) loss which is MUCH faster
than seq2seq because it doesn't require step-by-step autoregressive decoding.

Features:
- BiLSTM encoder outputs all characters at once
- CTC loss handles alignment automatically
- Greedy/beam decoding for inference
- Exports to ONNX for Android deployment

Typical training time: ~1-2 hours (vs 5-7 hours for seq2seq)
"""

import argparse
import json
import sys
import time
from pathlib import Path
from collections import Counter

import numpy as np

# Force unbuffered output
sys.stdout.reconfigure(line_buffering=True)

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader
from torch.nn.utils.rnn import pad_sequence


class Vocabulary:
    """Character and pinyin vocabulary management."""

    def __init__(self):
        # CTC requires blank token at index 0
        self.char2id = {'<BLANK>': 0, '<UNK>': 1}
        self.id2char = {0: '<BLANK>', 1: '<UNK>'}
        self.pinyin2id = {'<PAD>': 0, '<UNK>': 1}
        self.id2pinyin = {0: '<PAD>', 1: '<UNK>'}

    def build_from_data(self, data):
        """Build vocabularies from training data."""
        char_counter = Counter()
        pinyin_counter = Counter()

        for item in data:
            pinyin_seq = item['pinyin'].split()
            hanzi_seq = list(item['hanzi'])

            for p in pinyin_seq:
                pinyin_counter[p] += 1
            for c in hanzi_seq:
                char_counter[c] += 1

        # Build char vocab (CTC blank already at 0)
        for char, _ in char_counter.most_common():
            if char not in self.char2id:
                idx = len(self.char2id)
                self.char2id[char] = idx
                self.id2char[idx] = char

        # Build pinyin vocab
        for pinyin, _ in pinyin_counter.most_common():
            if pinyin not in self.pinyin2id:
                idx = len(self.pinyin2id)
                self.pinyin2id[pinyin] = idx
                self.id2pinyin[idx] = pinyin

        print(f"Vocabulary: {len(self.char2id)} characters (incl. CTC blank), {len(self.pinyin2id)} pinyin syllables")

    def encode_pinyin(self, pinyin_str):
        """Encode pinyin string to IDs."""
        return [self.pinyin2id.get(p, self.pinyin2id['<UNK>'])
                for p in pinyin_str.split()]

    def encode_hanzi(self, hanzi_str):
        """Encode hanzi string to IDs (no special tokens needed for CTC)."""
        return [self.char2id.get(c, self.char2id['<UNK>']) for c in hanzi_str]

    def decode_ctc(self, ids):
        """Decode CTC output (collapse repeats, remove blanks)."""
        chars = []
        prev_id = None
        for idx in ids:
            if idx != 0 and idx != prev_id:  # Not blank and not repeat
                chars.append(self.id2char.get(idx, '?'))
            prev_id = idx
        return ''.join(chars)


class PinyinDataset(Dataset):
    """Dataset for pinyin-to-hanzi with CTC."""

    def __init__(self, data, vocab, max_src_len=30, max_tgt_len=40):
        self.data = data
        self.vocab = vocab
        self.max_src_len = max_src_len
        self.max_tgt_len = max_tgt_len

    def __len__(self):
        return len(self.data)

    def __getitem__(self, idx):
        item = self.data[idx]

        src_ids = self.vocab.encode_pinyin(item['pinyin'])[:self.max_src_len]
        tgt_ids = self.vocab.encode_hanzi(item['hanzi'])[:self.max_tgt_len]

        return {
            'src': torch.tensor(src_ids, dtype=torch.long),
            'tgt': torch.tensor(tgt_ids, dtype=torch.long),
            'src_len': len(src_ids),
            'tgt_len': len(tgt_ids)
        }


def collate_fn(batch):
    """Collate function for variable length sequences."""
    srcs = [item['src'] for item in batch]
    tgts = [item['tgt'] for item in batch]
    src_lens = torch.tensor([item['src_len'] for item in batch])
    tgt_lens = torch.tensor([item['tgt_len'] for item in batch])

    # Pad sequences
    src_padded = pad_sequence(srcs, batch_first=True, padding_value=0)
    tgt_padded = pad_sequence(tgts, batch_first=True, padding_value=0)

    return src_padded, tgt_padded, src_lens, tgt_lens


class CTCPinyinModel(nn.Module):
    """BiLSTM model with CTC for pinyin-to-hanzi."""

    def __init__(self, pinyin_vocab_size, char_vocab_size, embed_dim=128,
                 hidden_dim=256, num_layers=3, dropout=0.2):
        super().__init__()

        self.embedding = nn.Embedding(pinyin_vocab_size, embed_dim, padding_idx=0)
        self.dropout = nn.Dropout(dropout)

        # BiLSTM encoder
        self.lstm = nn.LSTM(
            embed_dim,
            hidden_dim,
            num_layers=num_layers,
            batch_first=True,
            bidirectional=True,
            dropout=dropout if num_layers > 1 else 0
        )

        # Output projection (BiLSTM output is 2*hidden_dim)
        self.fc = nn.Linear(hidden_dim * 2, char_vocab_size)

        self.hidden_dim = hidden_dim
        self.num_layers = num_layers

    def forward(self, src, src_lens=None):
        """
        Args:
            src: (batch, seq_len) input pinyin IDs
            src_lens: (batch,) actual lengths (optional)
        Returns:
            log_probs: (seq_len, batch, vocab_size) for CTC loss
        """
        # Embedding
        embedded = self.embedding(src)  # (batch, seq_len, embed_dim)
        embedded = self.dropout(embedded)

        # BiLSTM
        # For MPS compatibility, don't use pack_padded_sequence
        output, _ = self.lstm(embedded)  # (batch, seq_len, hidden*2)

        # Project to vocabulary
        logits = self.fc(output)  # (batch, seq_len, vocab_size)

        # Log softmax for CTC (need seq_len first for CTC loss)
        log_probs = F.log_softmax(logits, dim=-1)
        log_probs = log_probs.transpose(0, 1)  # (seq_len, batch, vocab_size)

        return log_probs

    def decode_greedy(self, src):
        """Greedy decoding for inference."""
        with torch.no_grad():
            log_probs = self.forward(src)  # (seq_len, batch, vocab_size)
            # Get most likely token at each position
            predictions = log_probs.argmax(dim=-1)  # (seq_len, batch)
            return predictions.transpose(0, 1)  # (batch, seq_len)


def train_epoch(model, dataloader, optimizer, device):
    """Train for one epoch using CTC loss."""
    model.train()
    total_loss = 0
    num_batches = 0

    # CTC loss on CPU (not implemented on MPS)
    ctc_loss = nn.CTCLoss(blank=0, reduction='mean', zero_infinity=True)

    for batch_idx, (src, tgt, src_lens, tgt_lens) in enumerate(dataloader):
        src = src.to(device)
        # Keep targets and lengths on CPU for CTC loss
        # tgt stays on CPU
        # src_lens and tgt_lens stay on CPU

        optimizer.zero_grad()

        # Forward pass on GPU/MPS
        log_probs = model(src, src_lens.to(device))  # (T, N, C)

        # Move log_probs to CPU for CTC loss computation
        log_probs_cpu = log_probs.cpu()

        # CTC loss requires:
        # - log_probs: (T, N, C) where T=input_length, N=batch, C=num_classes
        # - targets: (N, S) where S=target_length
        # - input_lengths: (N,) actual input lengths
        # - target_lengths: (N,) actual target lengths

        # Input length for CTC (same as src_lens since no downsampling)
        input_lengths = src_lens

        # Create target list for each batch item
        target_list = []
        for i in range(tgt.size(0)):
            target_list.append(tgt[i, :tgt_lens[i]])
        targets_concat = torch.cat(target_list)

        # Compute CTC loss on CPU
        loss = ctc_loss(log_probs_cpu, targets_concat, input_lengths, tgt_lens)

        # Backward pass
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=5.0)
        optimizer.step()

        total_loss += loss.item()
        num_batches += 1

        if batch_idx % 100 == 0:
            print(f"  Batch {batch_idx}/{len(dataloader)}, Loss: {loss.item():.4f}")

    return total_loss / num_batches


def evaluate(model, dataloader, vocab, device, num_samples=500):
    """Evaluate character accuracy."""
    model.eval()
    correct = 0
    total = 0
    samples_evaluated = 0

    with torch.no_grad():
        for src, tgt, src_lens, tgt_lens in dataloader:
            if samples_evaluated >= num_samples:
                break

            src = src.to(device)

            # Greedy decode
            predictions = model.decode_greedy(src)  # (batch, seq_len)

            for i in range(min(src.size(0), num_samples - samples_evaluated)):
                pred_ids = predictions[i].cpu().tolist()
                tgt_ids = tgt[i, :tgt_lens[i]].tolist()

                # Decode CTC output (collapse repeats, remove blanks)
                pred_chars = vocab.decode_ctc(pred_ids)
                tgt_chars = ''.join([vocab.id2char.get(t, '?') for t in tgt_ids])

                # Character accuracy
                for p, t in zip(pred_chars, tgt_chars):
                    if p == t:
                        correct += 1
                    total += 1

                # Count remaining target chars as incorrect
                total += max(0, len(tgt_chars) - len(pred_chars))

                samples_evaluated += 1

    return correct / total if total > 0 else 0.0


def export_to_onnx(model, vocab, output_path, device):
    """Export model to ONNX format."""
    model.eval()

    # Create dummy input
    dummy_input = torch.zeros(1, 30, dtype=torch.long, device=device)

    # Export
    torch.onnx.export(
        model,
        dummy_input,
        output_path,
        input_names=['pinyin_ids'],
        output_names=['log_probs'],
        dynamic_axes={
            'pinyin_ids': {0: 'batch', 1: 'seq_len'},
            'log_probs': {0: 'seq_len', 1: 'batch'}
        },
        opset_version=14
    )

    print(f"Exported ONNX model to {output_path}")

    # Also save vocabulary
    vocab_path = output_path.replace('.onnx', '_vocab.json')
    vocab_data = {
        'char2id': vocab.char2id,
        'id2char': {str(k): v for k, v in vocab.id2char.items()},
        'pinyin2id': vocab.pinyin2id,
        'id2pinyin': {str(k): v for k, v in vocab.id2pinyin.items()}
    }
    with open(vocab_path, 'w', encoding='utf-8') as f:
        json.dump(vocab_data, f, ensure_ascii=False, indent=2)
    print(f"Saved vocabulary to {vocab_path}")


def quantize_onnx(input_path, output_path):
    """Apply INT8 quantization to ONNX model."""
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
        quantize_dynamic(
            input_path,
            output_path,
            weight_type=QuantType.QInt8
        )
        print(f"Quantized model saved to {output_path}")

        # Compare sizes
        import os
        orig_size = os.path.getsize(input_path) / (1024 * 1024)
        quant_size = os.path.getsize(output_path) / (1024 * 1024)
        print(f"Original size: {orig_size:.1f} MB, Quantized size: {quant_size:.1f} MB")
    except Exception as e:
        print(f"Quantization failed: {e}")


def main():
    parser = argparse.ArgumentParser(description='Train CTC Pinyin model')
    parser.add_argument('--dataset', type=str, required=True, help='Path to pinyin dataset JSON')
    parser.add_argument('--output', type=str, default='ctc_pinyin.onnx', help='Output model path')
    parser.add_argument('--epochs', type=int, default=30, help='Number of epochs')
    parser.add_argument('--batch-size', type=int, default=128, help='Batch size')
    parser.add_argument('--embed-dim', type=int, default=128, help='Embedding dimension')
    parser.add_argument('--hidden-dim', type=int, default=256, help='Hidden dimension')
    parser.add_argument('--num-layers', type=int, default=3, help='Number of LSTM layers')
    parser.add_argument('--lr', type=float, default=0.001, help='Learning rate')
    parser.add_argument('--test-split', type=float, default=0.1, help='Test split ratio')
    parser.add_argument('--device', type=str, default='auto', help='Device: cpu, cuda, mps, auto')
    args = parser.parse_args()

    print("=" * 60)
    print("CTC BiLSTM Pinyin Training")
    print("=" * 60)
    print("CTC is MUCH faster than seq2seq (no step-by-step decoding)")
    print()

    # Device selection
    if args.device == 'auto':
        if torch.cuda.is_available():
            device = torch.device('cuda')
        elif torch.backends.mps.is_available():
            device = torch.device('mps')
        else:
            device = torch.device('cpu')
    else:
        device = torch.device(args.device)
    print(f"Using device: {device}")

    # Load dataset
    print(f"\nLoading dataset from {args.dataset}...")
    with open(args.dataset, 'r', encoding='utf-8') as f:
        raw_data = json.load(f)
    if isinstance(raw_data, dict) and 'data' in raw_data:
        data = raw_data['data']
    else:
        data = raw_data
    print(f"Loaded {len(data)} samples")

    # Build vocabulary
    vocab = Vocabulary()
    vocab.build_from_data(data)

    # Split data
    np.random.seed(42)
    indices = np.random.permutation(len(data))
    split_idx = int(len(data) * (1 - args.test_split))

    train_data = [data[i] for i in indices[:split_idx]]
    test_data = [data[i] for i in indices[split_idx:]]
    print(f"Train: {len(train_data)}, Test: {len(test_data)}")

    # Create datasets and dataloaders
    train_dataset = PinyinDataset(train_data, vocab)
    test_dataset = PinyinDataset(test_data, vocab)

    train_loader = DataLoader(
        train_dataset,
        batch_size=args.batch_size,
        shuffle=True,
        collate_fn=collate_fn,
        num_workers=0  # MPS doesn't support multiprocess
    )
    test_loader = DataLoader(
        test_dataset,
        batch_size=args.batch_size,
        shuffle=False,
        collate_fn=collate_fn,
        num_workers=0
    )

    # Create model
    print(f"\nCreating CTC model...")
    model = CTCPinyinModel(
        pinyin_vocab_size=len(vocab.pinyin2id),
        char_vocab_size=len(vocab.char2id),
        embed_dim=args.embed_dim,
        hidden_dim=args.hidden_dim,
        num_layers=args.num_layers,
        dropout=0.2
    ).to(device)

    total_params = sum(p.numel() for p in model.parameters())
    print(f"Total parameters: {total_params:,}")
    print(f"Estimated model size: {total_params * 4 / 1024 / 1024:.1f} MB (FP32)")

    # Optimizer with learning rate scheduler
    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr)
    scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(
        optimizer, mode='max', factor=0.5, patience=3
    )

    # Training loop
    print(f"\nStarting training for {args.epochs} epochs...")
    start_time = time.time()
    best_accuracy = 0.0

    for epoch in range(args.epochs):
        epoch_start = time.time()

        # Train
        avg_loss = train_epoch(model, train_loader, optimizer, device)

        # Evaluate
        accuracy = evaluate(model, test_loader, vocab, device, num_samples=1000)

        epoch_time = time.time() - epoch_start
        current_lr = optimizer.param_groups[0]['lr']
        print(f"Epoch {epoch+1}/{args.epochs} - Loss: {avg_loss:.4f}, Accuracy: {accuracy:.2%}, LR: {current_lr:.6f}, Time: {epoch_time:.1f}s")

        # Learning rate scheduling
        scheduler.step(accuracy)

        # Save best model
        if accuracy > best_accuracy:
            best_accuracy = accuracy
            torch.save({
                'epoch': epoch,
                'model_state_dict': model.state_dict(),
                'accuracy': accuracy,
                'vocab': vocab
            }, args.output.replace('.onnx', '_best.pt'))
            print(f"  New best accuracy! Saved checkpoint.")

        # Early stopping if learning rate too low
        if current_lr < 1e-6:
            print("Learning rate too low, stopping training.")
            break

    total_time = time.time() - start_time
    print(f"\nTraining complete in {total_time/60:.1f} minutes")
    print(f"Best accuracy: {best_accuracy:.2%}")

    # Load best model for export
    checkpoint = torch.load(args.output.replace('.onnx', '_best.pt'))
    model.load_state_dict(checkpoint['model_state_dict'])

    # Export to ONNX
    print("\nExporting to ONNX...")
    export_to_onnx(model, vocab, args.output, device)

    # Quantize
    print("\nApplying INT8 quantization...")
    quantize_onnx(args.output, args.output.replace('.onnx', '_int8.onnx'))

    # Test some examples
    print("\n" + "=" * 60)
    print("Sample predictions:")
    print("=" * 60)
    model.eval()
    test_samples = [
        "ni hao",
        "zhong guo",
        "wo ai ni",
        "jin tian tian qi hen hao"
    ]
    for pinyin in test_samples:
        src_ids = vocab.encode_pinyin(pinyin)
        src = torch.tensor([src_ids], dtype=torch.long, device=device)
        pred = model.decode_greedy(src)[0].cpu().tolist()
        result = vocab.decode_ctc(pred)
        print(f"  {pinyin} -> {result}")

    print("\nDone!")


if __name__ == '__main__':
    main()
