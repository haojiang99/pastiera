#!/usr/bin/env python3
"""
Train a simple seq2seq model for toneless pinyin to Chinese character conversion.

This script:
1. Downloads the Duyu/Pinyin-Hanzi dataset from HuggingFace
2. Converts toned pinyin to toneless (removes tone numbers)
3. Trains a character-level seq2seq model
4. Exports to ONNX format

The model uses a simple LSTM encoder-decoder architecture optimized for mobile.

Requirements:
    pip install torch datasets numpy onnx onnxruntime tqdm

Usage:
    python train_pinyin_model.py

Output:
    - pinyin_ime_model/model.onnx
    - pinyin_ime_model/vocab_pinyin.txt
    - pinyin_ime_model/vocab_hanzi.txt
    - pinyin_ime_model.zip
"""

import os
import sys
import json
import re
import zipfile
import pickle
from collections import Counter

import numpy as np

try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    from torch.utils.data import Dataset, DataLoader
    from torch.nn.utils.rnn import pad_sequence
except ImportError:
    print("Error: PyTorch not installed. Run: pip install torch")
    sys.exit(1)

try:
    from datasets import load_dataset
except ImportError:
    print("Error: datasets not installed. Run: pip install datasets")
    sys.exit(1)

try:
    from tqdm import tqdm
except ImportError:
    print("Warning: tqdm not installed, progress bars disabled")
    tqdm = lambda x, **kwargs: x


# Special tokens
PAD_TOKEN = '<pad>'
UNK_TOKEN = '<unk>'
SOS_TOKEN = '<sos>'
EOS_TOKEN = '<eos>'

PAD_ID = 0
UNK_ID = 1
SOS_ID = 2
EOS_ID = 3


def remove_tones(pinyin_str):
    """
    Remove tone numbers from pinyin string.
    "wo3 shi4 xue2 sheng1" -> "wo shi xue sheng"
    """
    # Remove tone numbers (1-5)
    return re.sub(r'[1-5]', '', pinyin_str)


def tokenize_pinyin_chars(pinyin_str):
    """
    Tokenize pinyin string character by character.
    Keeps spaces as separate tokens for word boundaries.
    "wo shi" -> ['w', 'o', ' ', 's', 'h', 'i']
    """
    return list(pinyin_str.lower())


def tokenize_hanzi(hanzi_str):
    """
    Tokenize Chinese string character by character.
    "我是" -> ['我', '是']
    """
    return list(hanzi_str)


class Vocabulary:
    """Simple vocabulary class."""

    def __init__(self):
        self.token2idx = {
            PAD_TOKEN: PAD_ID,
            UNK_TOKEN: UNK_ID,
            SOS_TOKEN: SOS_ID,
            EOS_TOKEN: EOS_ID,
        }
        self.idx2token = {v: k for k, v in self.token2idx.items()}

    def add_token(self, token):
        if token not in self.token2idx:
            idx = len(self.token2idx)
            self.token2idx[token] = idx
            self.idx2token[idx] = token

    def __len__(self):
        return len(self.token2idx)

    def encode(self, tokens, add_sos=False, add_eos=False):
        """Convert tokens to indices."""
        ids = []
        if add_sos:
            ids.append(SOS_ID)
        for t in tokens:
            ids.append(self.token2idx.get(t, UNK_ID))
        if add_eos:
            ids.append(EOS_ID)
        return ids

    def decode(self, ids):
        """Convert indices to tokens."""
        tokens = []
        for i in ids:
            if i == EOS_ID:
                break
            if i not in (PAD_ID, SOS_ID):
                tokens.append(self.idx2token.get(i, UNK_TOKEN))
        return tokens

    def save(self, path):
        """Save vocabulary to file."""
        with open(path, 'w', encoding='utf-8') as f:
            for i in range(len(self.idx2token)):
                f.write(self.idx2token[i] + '\n')

    @classmethod
    def load(cls, path):
        """Load vocabulary from file."""
        vocab = cls()
        vocab.token2idx = {}
        vocab.idx2token = {}
        with open(path, 'r', encoding='utf-8') as f:
            for idx, line in enumerate(f):
                token = line.rstrip('\n')
                vocab.token2idx[token] = idx
                vocab.idx2token[idx] = token
        return vocab


class PinyinDataset(Dataset):
    """Dataset for pinyin-to-hanzi conversion."""

    def __init__(self, data, pinyin_vocab, hanzi_vocab, max_len=50):
        self.data = data
        self.pinyin_vocab = pinyin_vocab
        self.hanzi_vocab = hanzi_vocab
        self.max_len = max_len

    def __len__(self):
        return len(self.data)

    def __getitem__(self, idx):
        item = self.data[idx]
        pinyin = item['pinyin']
        hanzi = item['hanzi']

        # Tokenize
        pinyin_tokens = tokenize_pinyin_chars(pinyin)[:self.max_len]
        hanzi_tokens = tokenize_hanzi(hanzi)[:self.max_len]

        # Encode
        pinyin_ids = self.pinyin_vocab.encode(pinyin_tokens)
        hanzi_ids = self.hanzi_vocab.encode(hanzi_tokens, add_sos=True, add_eos=True)

        return {
            'pinyin': torch.tensor(pinyin_ids, dtype=torch.long),
            'hanzi': torch.tensor(hanzi_ids, dtype=torch.long),
        }


def collate_fn(batch):
    """Collate function for DataLoader."""
    pinyin_batch = [item['pinyin'] for item in batch]
    hanzi_batch = [item['hanzi'] for item in batch]

    pinyin_padded = pad_sequence(pinyin_batch, batch_first=True, padding_value=PAD_ID)
    hanzi_padded = pad_sequence(hanzi_batch, batch_first=True, padding_value=PAD_ID)

    return {
        'pinyin': pinyin_padded,
        'hanzi': hanzi_padded,
    }


class Encoder(nn.Module):
    """Bidirectional LSTM encoder."""

    def __init__(self, vocab_size, embed_size, hidden_size, num_layers=2, dropout=0.1):
        super().__init__()
        self.embedding = nn.Embedding(vocab_size, embed_size, padding_idx=PAD_ID)
        self.lstm = nn.LSTM(
            embed_size, hidden_size,
            num_layers=num_layers,
            batch_first=True,
            bidirectional=True,
            dropout=dropout if num_layers > 1 else 0
        )

    def forward(self, x):
        # x: (batch, seq_len)
        embedded = self.embedding(x)  # (batch, seq_len, embed_size)
        outputs, (hidden, cell) = self.lstm(embedded)
        # outputs: (batch, seq_len, hidden_size * 2)
        # hidden: (num_layers * 2, batch, hidden_size)
        return outputs, hidden, cell


class Attention(nn.Module):
    """Attention mechanism."""

    def __init__(self, enc_hidden_size, dec_hidden_size):
        super().__init__()
        self.attn = nn.Linear(enc_hidden_size + dec_hidden_size, dec_hidden_size)
        self.v = nn.Linear(dec_hidden_size, 1, bias=False)

    def forward(self, decoder_hidden, encoder_outputs, mask=None):
        # decoder_hidden: (batch, dec_hidden)
        # encoder_outputs: (batch, src_len, enc_hidden)
        src_len = encoder_outputs.size(1)

        # Repeat decoder hidden state
        hidden = decoder_hidden.unsqueeze(1).repeat(1, src_len, 1)

        # Calculate attention scores
        energy = torch.tanh(self.attn(torch.cat([hidden, encoder_outputs], dim=2)))
        attention = self.v(energy).squeeze(2)  # (batch, src_len)

        if mask is not None:
            attention = attention.masked_fill(mask == 0, -1e10)

        weights = F.softmax(attention, dim=1)
        return weights


class Decoder(nn.Module):
    """LSTM decoder with attention."""

    def __init__(self, vocab_size, embed_size, hidden_size, enc_hidden_size, num_layers=2, dropout=0.1):
        super().__init__()
        self.vocab_size = vocab_size
        self.embedding = nn.Embedding(vocab_size, embed_size, padding_idx=PAD_ID)
        self.attention = Attention(enc_hidden_size, hidden_size)
        self.lstm = nn.LSTM(
            embed_size + enc_hidden_size, hidden_size,
            num_layers=num_layers,
            batch_first=True,
            dropout=dropout if num_layers > 1 else 0
        )
        self.fc = nn.Linear(hidden_size + enc_hidden_size + embed_size, vocab_size)
        self.dropout = nn.Dropout(dropout)

    def forward(self, input_token, hidden, cell, encoder_outputs, mask=None):
        # input_token: (batch, 1)
        embedded = self.dropout(self.embedding(input_token))  # (batch, 1, embed_size)

        # Attention
        attn_weights = self.attention(hidden[-1], encoder_outputs, mask)
        context = torch.bmm(attn_weights.unsqueeze(1), encoder_outputs)  # (batch, 1, enc_hidden)

        # LSTM input
        lstm_input = torch.cat([embedded, context], dim=2)
        output, (hidden, cell) = self.lstm(lstm_input, (hidden, cell))

        # Output
        output = torch.cat([output, context, embedded], dim=2)
        prediction = self.fc(output.squeeze(1))  # (batch, vocab_size)

        return prediction, hidden, cell, attn_weights


class Seq2SeqModel(nn.Module):
    """Sequence-to-sequence model with attention."""

    def __init__(self, pinyin_vocab_size, hanzi_vocab_size,
                 embed_size=128, hidden_size=256, num_layers=2, dropout=0.1):
        super().__init__()

        self.encoder = Encoder(pinyin_vocab_size, embed_size, hidden_size, num_layers, dropout)
        self.decoder = Decoder(
            hanzi_vocab_size, embed_size, hidden_size,
            hidden_size * 2,  # bidirectional encoder
            num_layers, dropout
        )

        # Bridge layer to convert encoder hidden to decoder hidden
        self.bridge_hidden = nn.Linear(hidden_size * 2, hidden_size)
        self.bridge_cell = nn.Linear(hidden_size * 2, hidden_size)

        self.hanzi_vocab_size = hanzi_vocab_size

    def forward(self, pinyin, hanzi, teacher_forcing_ratio=0.5):
        """
        Training forward pass with teacher forcing.

        Args:
            pinyin: (batch, src_len)
            hanzi: (batch, tgt_len) - includes SOS and EOS
            teacher_forcing_ratio: probability of using teacher forcing
        """
        batch_size = pinyin.size(0)
        tgt_len = hanzi.size(1)

        # Encode
        encoder_outputs, enc_hidden, enc_cell = self.encoder(pinyin)

        # Create mask for padding
        mask = (pinyin != PAD_ID).float()

        # Bridge encoder states to decoder
        # Combine forward and backward hidden states
        hidden = enc_hidden.view(self.encoder.lstm.num_layers, 2, batch_size, -1)
        hidden = torch.cat([hidden[:, 0], hidden[:, 1]], dim=2)
        hidden = torch.tanh(self.bridge_hidden(hidden))

        cell = enc_cell.view(self.encoder.lstm.num_layers, 2, batch_size, -1)
        cell = torch.cat([cell[:, 0], cell[:, 1]], dim=2)
        cell = torch.tanh(self.bridge_cell(cell))

        # Decode
        outputs = torch.zeros(batch_size, tgt_len, self.hanzi_vocab_size).to(pinyin.device)
        input_token = hanzi[:, 0:1]  # Start with SOS

        for t in range(1, tgt_len):
            output, hidden, cell, _ = self.decoder(input_token, hidden, cell, encoder_outputs, mask)
            outputs[:, t] = output

            # Teacher forcing
            use_teacher_forcing = np.random.random() < teacher_forcing_ratio
            if use_teacher_forcing:
                input_token = hanzi[:, t:t+1]
            else:
                input_token = output.argmax(1, keepdim=True)

        return outputs

    def inference(self, pinyin, max_len=50):
        """
        Inference without teacher forcing.

        Args:
            pinyin: (batch, src_len)
            max_len: maximum output length
        """
        batch_size = pinyin.size(0)

        # Encode
        encoder_outputs, enc_hidden, enc_cell = self.encoder(pinyin)
        mask = (pinyin != PAD_ID).float()

        # Bridge
        hidden = enc_hidden.view(self.encoder.lstm.num_layers, 2, batch_size, -1)
        hidden = torch.cat([hidden[:, 0], hidden[:, 1]], dim=2)
        hidden = torch.tanh(self.bridge_hidden(hidden))

        cell = enc_cell.view(self.encoder.lstm.num_layers, 2, batch_size, -1)
        cell = torch.cat([cell[:, 0], cell[:, 1]], dim=2)
        cell = torch.tanh(self.bridge_cell(cell))

        # Decode
        outputs = []
        input_token = torch.full((batch_size, 1), SOS_ID, dtype=torch.long).to(pinyin.device)

        for _ in range(max_len):
            output, hidden, cell, _ = self.decoder(input_token, hidden, cell, encoder_outputs, mask)
            pred = output.argmax(1)
            outputs.append(pred)
            input_token = pred.unsqueeze(1)

            # Stop if all sequences have generated EOS
            if (pred == EOS_ID).all():
                break

        return torch.stack(outputs, dim=1)


def load_and_prepare_data(max_samples=100000):
    """Load dataset and prepare for training."""
    print("Loading Duyu/Pinyin-Hanzi dataset...")
    dataset = load_dataset("Duyu/Pinyin-Hanzi", split="train")

    print(f"Dataset size: {len(dataset)}")

    # Sample if too large
    if len(dataset) > max_samples:
        print(f"Sampling {max_samples} examples...")
        indices = np.random.choice(len(dataset), max_samples, replace=False)
        dataset = dataset.select(indices)

    # Build vocabularies
    print("Building vocabularies...")
    pinyin_vocab = Vocabulary()
    hanzi_vocab = Vocabulary()

    data = []
    for item in tqdm(dataset, desc="Processing data"):
        # Get pinyin and convert to toneless
        pinyin = item['汉语拼音序列']
        pinyin = remove_tones(pinyin)

        # Get hanzi
        hanzi = item['汉字语句序列']

        # Skip empty or too long
        if not pinyin or not hanzi:
            continue
        if len(pinyin) > 100 or len(hanzi) > 50:
            continue

        # Tokenize and add to vocabularies
        pinyin_tokens = tokenize_pinyin_chars(pinyin)
        hanzi_tokens = tokenize_hanzi(hanzi)

        for t in pinyin_tokens:
            pinyin_vocab.add_token(t)
        for t in hanzi_tokens:
            hanzi_vocab.add_token(t)

        data.append({
            'pinyin': pinyin,
            'hanzi': hanzi,
        })

    print(f"Prepared {len(data)} samples")
    print(f"Pinyin vocabulary size: {len(pinyin_vocab)}")
    print(f"Hanzi vocabulary size: {len(hanzi_vocab)}")

    return data, pinyin_vocab, hanzi_vocab


def train_model(model, train_loader, optimizer, criterion, device, epoch):
    """Train for one epoch."""
    model.train()
    total_loss = 0

    pbar = tqdm(train_loader, desc=f"Epoch {epoch}")
    for batch in pbar:
        pinyin = batch['pinyin'].to(device)
        hanzi = batch['hanzi'].to(device)

        optimizer.zero_grad()
        outputs = model(pinyin, hanzi, teacher_forcing_ratio=0.5)

        # Calculate loss (ignore padding)
        outputs = outputs[:, 1:].contiguous().view(-1, outputs.size(-1))
        targets = hanzi[:, 1:].contiguous().view(-1)

        loss = criterion(outputs, targets)
        loss.backward()

        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()

        total_loss += loss.item()
        pbar.set_postfix({'loss': loss.item()})

    return total_loss / len(train_loader)


def export_to_onnx(model, pinyin_vocab, output_path, max_len=50):
    """Export model to ONNX format."""
    print(f"Exporting to ONNX: {output_path}")

    model.eval()

    # Create a wrapper for export
    class InferenceWrapper(nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model

        def forward(self, pinyin):
            return self.model.inference(pinyin, max_len=max_len)

    wrapper = InferenceWrapper(model)
    wrapper.eval()

    # Dummy input
    dummy_input = torch.randint(0, len(pinyin_vocab), (1, max_len), dtype=torch.long)

    torch.onnx.export(
        wrapper,
        dummy_input,
        output_path,
        input_names=['pinyin_input'],
        output_names=['hanzi_output'],
        dynamic_axes={
            'pinyin_input': {0: 'batch_size', 1: 'seq_len'},
            'hanzi_output': {0: 'batch_size', 1: 'out_len'}
        },
        opset_version=14,
        do_constant_folding=True
    )

    print("ONNX export complete")


def create_zip(output_dir, zip_path):
    """Create zip file with all model files."""
    print(f"Creating {zip_path}...")

    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
        for filename in ['model.onnx', 'vocab_pinyin.txt', 'vocab_hanzi.txt', 'config.json']:
            filepath = os.path.join(output_dir, filename)
            if os.path.exists(filepath):
                zf.write(filepath, filename)
                size = os.path.getsize(filepath)
                if size > 1024 * 1024:
                    print(f"  Added: {filename} ({size / 1024 / 1024:.2f} MB)")
                else:
                    print(f"  Added: {filename} ({size / 1024:.1f} KB)")

    zip_size = os.path.getsize(zip_path) / (1024 * 1024)
    print(f"Final zip size: {zip_size:.2f} MB")


def main():
    # Configuration
    output_dir = "pinyin_ime_model"
    zip_path = "pinyin_ime_model.zip"

    max_samples = 100000  # Use 100k samples for faster training
    batch_size = 64
    num_epochs = 10
    embed_size = 128
    hidden_size = 256
    num_layers = 2
    learning_rate = 0.001

    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    print(f"Using device: {device}")

    # Create output directory
    os.makedirs(output_dir, exist_ok=True)

    # Load data
    data, pinyin_vocab, hanzi_vocab = load_and_prepare_data(max_samples)

    # Split data
    split_idx = int(len(data) * 0.95)
    train_data = data[:split_idx]
    val_data = data[split_idx:]

    print(f"Train samples: {len(train_data)}")
    print(f"Val samples: {len(val_data)}")

    # Create datasets
    train_dataset = PinyinDataset(train_data, pinyin_vocab, hanzi_vocab)
    val_dataset = PinyinDataset(val_data, pinyin_vocab, hanzi_vocab)

    train_loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True, collate_fn=collate_fn)
    val_loader = DataLoader(val_dataset, batch_size=batch_size, shuffle=False, collate_fn=collate_fn)

    # Create model
    model = Seq2SeqModel(
        pinyin_vocab_size=len(pinyin_vocab),
        hanzi_vocab_size=len(hanzi_vocab),
        embed_size=embed_size,
        hidden_size=hidden_size,
        num_layers=num_layers
    ).to(device)

    print(f"Model parameters: {sum(p.numel() for p in model.parameters()):,}")

    # Training
    optimizer = torch.optim.Adam(model.parameters(), lr=learning_rate)
    criterion = nn.CrossEntropyLoss(ignore_index=PAD_ID)

    best_loss = float('inf')
    for epoch in range(1, num_epochs + 1):
        train_loss = train_model(model, train_loader, optimizer, criterion, device, epoch)
        print(f"Epoch {epoch}: Train Loss = {train_loss:.4f}")

        if train_loss < best_loss:
            best_loss = train_loss
            torch.save(model.state_dict(), os.path.join(output_dir, 'best_model.pt'))
            print("  Saved best model")

    # Load best model
    model.load_state_dict(torch.load(os.path.join(output_dir, 'best_model.pt')))
    model = model.cpu()

    # Save vocabularies
    pinyin_vocab.save(os.path.join(output_dir, 'vocab_pinyin.txt'))
    hanzi_vocab.save(os.path.join(output_dir, 'vocab_hanzi.txt'))

    # Save config
    config = {
        'model_type': 'lstm_seq2seq',
        'embed_size': embed_size,
        'hidden_size': hidden_size,
        'num_layers': num_layers,
        'pinyin_vocab_size': len(pinyin_vocab),
        'hanzi_vocab_size': len(hanzi_vocab),
        'max_len': 50,
        'supports_toneless': True,
        'input_format': 'character_sequence',
    }
    with open(os.path.join(output_dir, 'config.json'), 'w') as f:
        json.dump(config, f, indent=2)

    # Export to ONNX
    export_to_onnx(model, pinyin_vocab, os.path.join(output_dir, 'model.onnx'))

    # Create zip
    create_zip(output_dir, zip_path)

    print("\n" + "="*60)
    print("Training complete!")
    print(f"Output directory: {output_dir}/")
    print(f"Zip file: {zip_path}")
    print("="*60)


if __name__ == "__main__":
    main()
