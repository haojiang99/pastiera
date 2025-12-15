#!/usr/bin/env python3
"""
Train a Somiao-style CBHG model for toneless pinyin to Chinese character conversion.

Architecture based on Kyubyong's neural_chinese_transliterator using CBHG module.
Trained on Duyu/Pinyin-Hanzi dataset with tones removed.

This model takes character-level pinyin input (e.g., "wo shi zhong guo ren")
and outputs Chinese characters (e.g., "我是中国人").

Requirements:
    pip install torch datasets tqdm onnx onnxruntime

Usage:
    python train_somiao_toneless.py

Output:
    - pinyin_model/model.onnx
    - pinyin_model/vocab_pinyin.txt
    - pinyin_model/vocab_hanzi.txt
    - pinyin_model/config.json
    - pinyin_model.zip
"""

import os
import sys
import re
import json
import zipfile
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
    tqdm = lambda x, **kwargs: x

# ============================================================================
# Constants
# ============================================================================

PAD_TOKEN = '<pad>'
UNK_TOKEN = '<unk>'
PAD_ID = 0
UNK_ID = 1

# ============================================================================
# Utility Functions
# ============================================================================

def remove_tones(pinyin_str):
    """Remove tone numbers from pinyin: 'wo3 shi4' -> 'wo shi'"""
    return re.sub(r'[1-5]', '', pinyin_str)


def normalize_pinyin(pinyin_str):
    """Normalize pinyin string for input."""
    # Remove tones, lowercase, keep only valid chars
    pinyin = remove_tones(pinyin_str).lower()
    # Keep letters, spaces, and apostrophes
    pinyin = re.sub(r"[^a-z '\s]", '', pinyin)
    # Normalize whitespace
    pinyin = ' '.join(pinyin.split())
    return pinyin


# ============================================================================
# Vocabulary
# ============================================================================

class Vocabulary:
    def __init__(self):
        self.token2idx = {PAD_TOKEN: PAD_ID, UNK_TOKEN: UNK_ID}
        self.idx2token = {PAD_ID: PAD_TOKEN, UNK_ID: UNK_TOKEN}

    def add(self, token):
        if token not in self.token2idx:
            idx = len(self.token2idx)
            self.token2idx[token] = idx
            self.idx2token[idx] = token

    def __len__(self):
        return len(self.token2idx)

    def encode(self, tokens):
        return [self.token2idx.get(t, UNK_ID) for t in tokens]

    def decode(self, ids):
        return [self.idx2token.get(i, UNK_TOKEN) for i in ids]

    def save(self, path):
        with open(path, 'w', encoding='utf-8') as f:
            for i in range(len(self.idx2token)):
                f.write(self.idx2token[i] + '\n')


# ============================================================================
# Dataset
# ============================================================================

class PinyinHanziDataset(Dataset):
    def __init__(self, data, pinyin_vocab, hanzi_vocab, max_len=50):
        self.data = data
        self.pinyin_vocab = pinyin_vocab
        self.hanzi_vocab = hanzi_vocab
        self.max_len = max_len

    def __len__(self):
        return len(self.data)

    def __getitem__(self, idx):
        item = self.data[idx]

        # Character-level tokenization
        pinyin_chars = list(item['pinyin'])[:self.max_len]
        hanzi_chars = list(item['hanzi'])[:self.max_len]

        # Pad hanzi to match pinyin length (for character-to-character mapping)
        # Use '_' as blank/space character in output
        while len(hanzi_chars) < len(pinyin_chars):
            hanzi_chars.append('_')

        pinyin_ids = self.pinyin_vocab.encode(pinyin_chars)
        hanzi_ids = self.hanzi_vocab.encode(hanzi_chars)

        return {
            'pinyin': torch.tensor(pinyin_ids, dtype=torch.long),
            'hanzi': torch.tensor(hanzi_ids, dtype=torch.long),
        }


def collate_fn(batch):
    pinyin = [item['pinyin'] for item in batch]
    hanzi = [item['hanzi'] for item in batch]

    pinyin_padded = pad_sequence(pinyin, batch_first=True, padding_value=PAD_ID)
    hanzi_padded = pad_sequence(hanzi, batch_first=True, padding_value=PAD_ID)

    return {'pinyin': pinyin_padded, 'hanzi': hanzi_padded}


# ============================================================================
# Model: CBHG-based Seq2Seq (Somiao-style)
# ============================================================================

class HighwayNet(nn.Module):
    """Highway Network layer."""
    def __init__(self, size):
        super().__init__()
        self.H = nn.Linear(size, size)
        self.T = nn.Linear(size, size)
        self.T.bias.data.fill_(-1.0)  # Initialize gate to be mostly closed

    def forward(self, x):
        H = F.relu(self.H(x))
        T = torch.sigmoid(self.T(x))
        return H * T + x * (1 - T)


class Conv1DBank(nn.Module):
    """Bank of 1D convolutions with different kernel sizes."""
    def __init__(self, in_channels, out_channels, K=16):
        super().__init__()
        self.convs = nn.ModuleList([
            nn.Conv1d(in_channels, out_channels, kernel_size=k, padding=k//2)
            for k in range(1, K + 1)
        ])
        self.bns = nn.ModuleList([
            nn.BatchNorm1d(out_channels) for _ in range(K)
        ])

    def forward(self, x):
        # x: (batch, seq, channels) -> (batch, channels, seq)
        x = x.transpose(1, 2)
        outputs = []
        for conv, bn in zip(self.convs, self.bns):
            out = conv(x)
            # Trim to original length
            if out.size(2) > x.size(2):
                out = out[:, :, :x.size(2)]
            out = F.relu(bn(out))
            outputs.append(out)
        # Concat: (batch, K * out_channels, seq)
        return torch.cat(outputs, dim=1).transpose(1, 2)


class CBHG(nn.Module):
    """
    CBHG Module: Conv1D Bank + Highway + Bidirectional GRU
    Based on Tacotron architecture, used in Somiao-Pinyin.
    """
    def __init__(self, in_channels, K=16, proj_channels=128, num_highways=4):
        super().__init__()

        # Conv1D bank
        self.conv_bank = Conv1DBank(in_channels, in_channels, K)

        # Max pooling
        self.maxpool = nn.MaxPool1d(kernel_size=2, stride=1, padding=1)

        # Conv1D projections
        self.conv_proj1 = nn.Conv1d(K * in_channels, proj_channels, kernel_size=3, padding=1)
        self.bn1 = nn.BatchNorm1d(proj_channels)
        self.conv_proj2 = nn.Conv1d(proj_channels, in_channels, kernel_size=3, padding=1)
        self.bn2 = nn.BatchNorm1d(in_channels)

        # Pre-highway linear (if dimensions don't match)
        self.pre_highway = nn.Linear(in_channels, in_channels)

        # Highway layers
        self.highways = nn.ModuleList([
            HighwayNet(in_channels) for _ in range(num_highways)
        ])

        # Bidirectional GRU
        self.gru = nn.GRU(in_channels, in_channels, batch_first=True, bidirectional=True)

    def forward(self, x):
        # x: (batch, seq, channels)
        residual = x

        # Conv1D bank
        x = self.conv_bank(x)  # (batch, seq, K * channels)

        # Max pooling
        x = x.transpose(1, 2)  # (batch, K * channels, seq)
        x = self.maxpool(x)[:, :, :residual.size(1)]  # Keep original length

        # Conv projections
        x = F.relu(self.bn1(self.conv_proj1(x)))
        x = self.bn2(self.conv_proj2(x))
        x = x.transpose(1, 2)  # (batch, seq, channels)

        # Residual connection
        x = x + residual

        # Highway layers
        for highway in self.highways:
            x = highway(x)

        # Bidirectional GRU
        x, _ = self.gru(x)

        # Sum forward and backward outputs
        x = x[:, :, :x.size(2)//2] + x[:, :, x.size(2)//2:]

        return x


class SomiaoPinyinModel(nn.Module):
    """
    Somiao-style Pinyin to Hanzi model.

    Architecture:
    - Embedding
    - Prenet (2 dense layers)
    - CBHG module
    - Output projection

    Input: Character-level pinyin sequence
    Output: Character-level hanzi sequence (same length)
    """
    def __init__(self, pinyin_vocab_size, hanzi_vocab_size,
                 embed_size=256, prenet_size=128, cbhg_channels=128,
                 num_banks=16, num_highways=4):
        super().__init__()

        self.embed_size = embed_size

        # Embedding
        self.embedding = nn.Embedding(pinyin_vocab_size, embed_size, padding_idx=PAD_ID)

        # Prenet: two dense layers with dropout
        self.prenet = nn.Sequential(
            nn.Linear(embed_size, prenet_size),
            nn.ReLU(),
            nn.Dropout(0.5),
            nn.Linear(prenet_size, prenet_size),
            nn.ReLU(),
            nn.Dropout(0.5),
        )

        # CBHG module
        self.cbhg = CBHG(prenet_size, K=num_banks, proj_channels=prenet_size,
                         num_highways=num_highways)

        # Output projection
        self.output = nn.Linear(prenet_size, hanzi_vocab_size)

    def forward(self, x):
        """
        Args:
            x: (batch, seq_len) pinyin character indices
        Returns:
            logits: (batch, seq_len, hanzi_vocab_size)
        """
        # Embedding
        x = self.embedding(x)  # (batch, seq, embed_size)

        # Prenet
        x = self.prenet(x)  # (batch, seq, prenet_size)

        # CBHG
        x = self.cbhg(x)  # (batch, seq, prenet_size)

        # Output projection
        logits = self.output(x)  # (batch, seq, hanzi_vocab_size)

        return logits

    def predict(self, x):
        """Get predicted hanzi indices."""
        logits = self.forward(x)
        return torch.argmax(logits, dim=-1)


# ============================================================================
# Training
# ============================================================================

def load_data(max_samples=200000):
    """Load and preprocess dataset."""
    print("Loading Duyu/Pinyin-Hanzi dataset...")
    dataset = load_dataset("Duyu/Pinyin-Hanzi", split="train")
    print(f"Total samples: {len(dataset)}")

    if len(dataset) > max_samples:
        print(f"Sampling {max_samples} examples...")
        indices = np.random.choice(len(dataset), max_samples, replace=False)
        dataset = dataset.select(indices)

    # Build vocabularies and process data
    print("Building vocabularies...")
    pinyin_vocab = Vocabulary()
    hanzi_vocab = Vocabulary()

    # Add blank character for hanzi (used when pinyin is longer)
    hanzi_vocab.add('_')

    data = []
    for item in tqdm(dataset, desc="Processing"):
        # Get and normalize pinyin (remove tones)
        pinyin = normalize_pinyin(item['汉语拼音序列'])
        hanzi = item['汉字语句序列']

        # Skip invalid
        if not pinyin or not hanzi:
            continue
        if len(pinyin) > 100 or len(hanzi) > 50:
            continue

        # Add tokens to vocabularies
        for c in pinyin:
            pinyin_vocab.add(c)
        for c in hanzi:
            hanzi_vocab.add(c)

        data.append({'pinyin': pinyin, 'hanzi': hanzi})

    print(f"Processed {len(data)} samples")
    print(f"Pinyin vocab size: {len(pinyin_vocab)}")
    print(f"Hanzi vocab size: {len(hanzi_vocab)}")

    return data, pinyin_vocab, hanzi_vocab


def train_epoch(model, loader, optimizer, criterion, device, epoch):
    model.train()
    total_loss = 0
    total_correct = 0
    total_tokens = 0

    pbar = tqdm(loader, desc=f"Epoch {epoch}")
    for batch in pbar:
        pinyin = batch['pinyin'].to(device)
        hanzi = batch['hanzi'].to(device)

        optimizer.zero_grad()
        logits = model(pinyin)

        # Flatten for loss computation
        logits_flat = logits.view(-1, logits.size(-1))
        hanzi_flat = hanzi.view(-1)

        # Compute loss (ignore padding)
        loss = criterion(logits_flat, hanzi_flat)
        loss.backward()

        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()

        # Compute accuracy
        mask = hanzi_flat != PAD_ID
        preds = logits_flat.argmax(dim=-1)
        correct = ((preds == hanzi_flat) & mask).sum().item()
        total_correct += correct
        total_tokens += mask.sum().item()

        total_loss += loss.item()
        pbar.set_postfix({
            'loss': f'{loss.item():.4f}',
            'acc': f'{total_correct/max(1,total_tokens)*100:.1f}%'
        })

    return total_loss / len(loader), total_correct / max(1, total_tokens)


def evaluate(model, loader, criterion, device):
    model.eval()
    total_loss = 0
    total_correct = 0
    total_tokens = 0

    with torch.no_grad():
        for batch in loader:
            pinyin = batch['pinyin'].to(device)
            hanzi = batch['hanzi'].to(device)

            logits = model(pinyin)
            logits_flat = logits.view(-1, logits.size(-1))
            hanzi_flat = hanzi.view(-1)

            loss = criterion(logits_flat, hanzi_flat)
            total_loss += loss.item()

            mask = hanzi_flat != PAD_ID
            preds = logits_flat.argmax(dim=-1)
            correct = ((preds == hanzi_flat) & mask).sum().item()
            total_correct += correct
            total_tokens += mask.sum().item()

    return total_loss / len(loader), total_correct / max(1, total_tokens)


def export_onnx(model, pinyin_vocab_size, output_path, max_len=50):
    """Export model to ONNX format."""
    print(f"Exporting to ONNX: {output_path}")

    model.eval()
    model.cpu()

    # Dummy input
    dummy = torch.randint(0, pinyin_vocab_size, (1, max_len), dtype=torch.long)

    torch.onnx.export(
        model,
        dummy,
        output_path,
        input_names=['pinyin_input'],
        output_names=['logits'],
        dynamic_axes={
            'pinyin_input': {0: 'batch', 1: 'seq_len'},
            'logits': {0: 'batch', 1: 'seq_len'}
        },
        opset_version=14,
        do_constant_folding=True
    )

    # Verify
    import onnx
    onnx_model = onnx.load(output_path)
    onnx.checker.check_model(onnx_model)
    print("ONNX export verified!")


def create_zip(output_dir, zip_path):
    """Create zip file for deployment."""
    print(f"Creating {zip_path}...")

    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
        for fname in ['model.onnx', 'vocab_pinyin.txt', 'vocab_hanzi.txt', 'config.json']:
            fpath = os.path.join(output_dir, fname)
            if os.path.exists(fpath):
                zf.write(fpath, fname)
                size = os.path.getsize(fpath)
                unit = 'MB' if size > 1e6 else 'KB'
                size_val = size/1e6 if size > 1e6 else size/1e3
                print(f"  {fname}: {size_val:.2f} {unit}")

    total = os.path.getsize(zip_path) / 1e6
    print(f"Total zip: {total:.2f} MB")


# ============================================================================
# Main
# ============================================================================

def main():
    # Configuration
    output_dir = "pinyin_model"
    zip_path = "pinyin_model.zip"

    max_samples = 200000  # Use 200k samples
    batch_size = 64
    num_epochs = 15
    learning_rate = 0.001

    # Model hyperparameters (Somiao-style)
    embed_size = 256
    prenet_size = 128
    num_banks = 16
    num_highways = 4

    device = torch.device('mps' if torch.backends.mps.is_available() else
                          'cuda' if torch.cuda.is_available() else 'cpu')
    print(f"Using device: {device}")

    os.makedirs(output_dir, exist_ok=True)

    # Load data
    data, pinyin_vocab, hanzi_vocab = load_data(max_samples)

    # Split
    split_idx = int(len(data) * 0.95)
    train_data = data[:split_idx]
    val_data = data[split_idx:]
    print(f"Train: {len(train_data)}, Val: {len(val_data)}")

    # Datasets
    train_dataset = PinyinHanziDataset(train_data, pinyin_vocab, hanzi_vocab)
    val_dataset = PinyinHanziDataset(val_data, pinyin_vocab, hanzi_vocab)

    train_loader = DataLoader(train_dataset, batch_size=batch_size, shuffle=True,
                              collate_fn=collate_fn, num_workers=0)
    val_loader = DataLoader(val_dataset, batch_size=batch_size, shuffle=False,
                            collate_fn=collate_fn, num_workers=0)

    # Model
    model = SomiaoPinyinModel(
        pinyin_vocab_size=len(pinyin_vocab),
        hanzi_vocab_size=len(hanzi_vocab),
        embed_size=embed_size,
        prenet_size=prenet_size,
        num_banks=num_banks,
        num_highways=num_highways
    ).to(device)

    num_params = sum(p.numel() for p in model.parameters())
    print(f"Model parameters: {num_params:,}")

    # Training
    optimizer = torch.optim.Adam(model.parameters(), lr=learning_rate)
    criterion = nn.CrossEntropyLoss(ignore_index=PAD_ID)
    scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(optimizer, patience=2, factor=0.5)

    best_val_acc = 0
    for epoch in range(1, num_epochs + 1):
        train_loss, train_acc = train_epoch(model, train_loader, optimizer, criterion, device, epoch)
        val_loss, val_acc = evaluate(model, val_loader, criterion, device)

        print(f"Epoch {epoch}: Train Loss={train_loss:.4f}, Acc={train_acc*100:.1f}% | "
              f"Val Loss={val_loss:.4f}, Acc={val_acc*100:.1f}%")

        scheduler.step(val_loss)

        if val_acc > best_val_acc:
            best_val_acc = val_acc
            torch.save(model.state_dict(), os.path.join(output_dir, 'best_model.pt'))
            print(f"  Saved best model (acc={val_acc*100:.1f}%)")

    # Load best and export
    model.load_state_dict(torch.load(os.path.join(output_dir, 'best_model.pt')))

    # Save vocabularies
    pinyin_vocab.save(os.path.join(output_dir, 'vocab_pinyin.txt'))
    hanzi_vocab.save(os.path.join(output_dir, 'vocab_hanzi.txt'))

    # Save config
    config = {
        'model_type': 'somiao_cbhg',
        'embed_size': embed_size,
        'prenet_size': prenet_size,
        'num_banks': num_banks,
        'num_highways': num_highways,
        'pinyin_vocab_size': len(pinyin_vocab),
        'hanzi_vocab_size': len(hanzi_vocab),
        'max_len': 50,
        'input_type': 'character',  # Character-level pinyin input
        'toneless': True,
    }
    with open(os.path.join(output_dir, 'config.json'), 'w') as f:
        json.dump(config, f, indent=2)

    # Export ONNX
    export_onnx(model, len(pinyin_vocab), os.path.join(output_dir, 'model.onnx'))

    # Create zip
    create_zip(output_dir, zip_path)

    print("\n" + "="*60)
    print("Training complete!")
    print(f"Best validation accuracy: {best_val_acc*100:.1f}%")
    print(f"Output: {output_dir}/")
    print(f"Zip: {zip_path}")
    print("="*60)


if __name__ == "__main__":
    main()
