#!/usr/bin/env python3
"""
SMALL BiLSTM + Attention model for Pinyin to Chinese character conversion.
Optimized for mobile deployment (~10MB INT8 final size).

Architecture (reduced for mobile):
- embed_dim: 128 (vs 256 standard)
- hidden_dim: 256 (vs 512 standard)
- attention_dim: 128 (vs 256 standard)
- num_layers: 2 (vs 3 standard)

Expected model sizes:
- FP32: ~25 MB
- INT8: ~8-10 MB

Usage:
    python train_bilstm_pinyin_small.py --dataset ../hmm_dataset_ime.json

    # Resume from checkpoint:
    python train_bilstm_pinyin_small.py --resume output_small/checkpoints/checkpoint_epoch_10.pt
"""

import argparse
import json
import os
import random
import time
import math
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import List, Tuple, Dict, Optional

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader
from torch.nn.utils.rnn import pad_sequence
from torch.amp import autocast
from torch.cuda.amp import GradScaler

# Optional imports
try:
    from torch.utils.tensorboard import SummaryWriter
    HAS_TENSORBOARD = True
except ImportError:
    HAS_TENSORBOARD = False
    print("TensorBoard not available. Install with: pip install tensorboard")

try:
    from tqdm import tqdm
    HAS_TQDM = True
except ImportError:
    HAS_TQDM = False
    print("tqdm not available. Install with: pip install tqdm")

# Set seeds for reproducibility
SEED = 42
random.seed(SEED)
np.random.seed(SEED)
torch.manual_seed(SEED)
torch.cuda.manual_seed_all(SEED)

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
    """Dataset for pinyin to hanzi conversion with optimized loading."""

    def __init__(self, samples: List[Dict], pinyin_vocab: Vocabulary,
                 hanzi_vocab: Vocabulary, max_len: int = 64, sort_by_length: bool = False,
                 char_level_pinyin: bool = True):
        self.pinyin_vocab = pinyin_vocab
        self.hanzi_vocab = hanzi_vocab
        self.max_len = max_len
        self.char_level_pinyin = char_level_pinyin

        # Pre-process all samples for faster loading
        self.processed = []
        for sample in samples:
            pinyin_str = sample['pinyin']

            # Check if pinyin has spaces (syllable-level) or not (char-level)
            if ' ' in pinyin_str and not char_level_pinyin:
                # Syllable-level: split by space
                pinyin = pinyin_str.split()[:max_len]
            else:
                # Character-level: each character is a token
                pinyin = list(pinyin_str.lower().replace(' ', ''))[:max_len * 6]  # ~6 chars per syllable

            hanzi = list(sample['hanzi'])[:max_len]
            pinyin_ids = self.pinyin_vocab.encode(pinyin)
            hanzi_ids = [SOS_IDX] + self.hanzi_vocab.encode(hanzi) + [EOS_IDX]
            self.processed.append((pinyin_ids, hanzi_ids, len(pinyin_ids)))

        # Sort by length for more efficient batching (less padding)
        if sort_by_length:
            self.processed.sort(key=lambda x: x[2])

    def __len__(self):
        return len(self.processed)

    def __getitem__(self, idx):
        pinyin_ids, hanzi_ids, pinyin_len = self.processed[idx]
        return {
            'pinyin': torch.tensor(pinyin_ids, dtype=torch.long),
            'hanzi': torch.tensor(hanzi_ids, dtype=torch.long),
            'pinyin_len': pinyin_len,
            'hanzi_len': len(hanzi_ids)
        }


class BucketBatchSampler:
    """Sampler that groups similar-length sequences into batches to minimize padding."""

    def __init__(self, lengths: List[int], batch_size: int, shuffle: bool = True,
                 num_buckets: int = 10, drop_last: bool = False):
        self.batch_size = batch_size
        self.shuffle = shuffle
        self.drop_last = drop_last

        # Create buckets based on length
        sorted_indices = np.argsort(lengths)
        bucket_size = len(sorted_indices) // num_buckets

        self.buckets = []
        for i in range(num_buckets):
            start = i * bucket_size
            end = (i + 1) * bucket_size if i < num_buckets - 1 else len(sorted_indices)
            self.buckets.append(sorted_indices[start:end].tolist())

    def __iter__(self):
        # Shuffle within buckets
        if self.shuffle:
            for bucket in self.buckets:
                random.shuffle(bucket)

        # Flatten and create batches
        all_indices = []
        for bucket in self.buckets:
            all_indices.extend(bucket)

        # Shuffle bucket order
        if self.shuffle:
            bucket_batches = []
            for i in range(0, len(all_indices), self.batch_size):
                bucket_batches.append(all_indices[i:i + self.batch_size])
            random.shuffle(bucket_batches)
            all_indices = [idx for batch in bucket_batches for idx in batch]

        # Yield batches
        batch = []
        for idx in all_indices:
            batch.append(idx)
            if len(batch) == self.batch_size:
                yield batch
                batch = []

        if batch and not self.drop_last:
            yield batch

    def __len__(self):
        if self.drop_last:
            return sum(len(b) for b in self.buckets) // self.batch_size
        return (sum(len(b) for b in self.buckets) + self.batch_size - 1) // self.batch_size


def collate_fn(batch):
    """Optimized collate function for DataLoader."""
    pinyin = [item['pinyin'] for item in batch]
    hanzi = [item['hanzi'] for item in batch]
    pinyin_lens = torch.tensor([item['pinyin_len'] for item in batch], dtype=torch.long)
    hanzi_lens = torch.tensor([item['hanzi_len'] for item in batch], dtype=torch.long)

    # Pad sequences
    pinyin_padded = pad_sequence(pinyin, batch_first=True, padding_value=PAD_IDX)
    hanzi_padded = pad_sequence(hanzi, batch_first=True, padding_value=PAD_IDX)

    return {
        'pinyin': pinyin_padded,
        'hanzi': hanzi_padded,
        'pinyin_lens': pinyin_lens,
        'hanzi_lens': hanzi_lens
    }


class MultiHeadAttention(nn.Module):
    """Multi-head attention mechanism for better context understanding."""

    def __init__(self, encoder_dim: int, decoder_dim: int, num_heads: int = 8):
        super().__init__()
        self.num_heads = num_heads
        self.head_dim = encoder_dim // num_heads
        assert self.head_dim * num_heads == encoder_dim, "encoder_dim must be divisible by num_heads"

        self.query_proj = nn.Linear(decoder_dim, encoder_dim)
        self.key_proj = nn.Linear(encoder_dim, encoder_dim)
        self.value_proj = nn.Linear(encoder_dim, encoder_dim)
        self.out_proj = nn.Linear(encoder_dim, encoder_dim)

        self.scale = math.sqrt(self.head_dim)

    def forward(self, encoder_outputs, decoder_hidden, mask=None):
        # encoder_outputs: (batch, seq_len, encoder_dim)
        # decoder_hidden: (batch, decoder_dim)

        batch_size, seq_len, _ = encoder_outputs.shape

        # Project queries, keys, values
        query = self.query_proj(decoder_hidden).view(batch_size, 1, self.num_heads, self.head_dim).transpose(1, 2)
        key = self.key_proj(encoder_outputs).view(batch_size, seq_len, self.num_heads, self.head_dim).transpose(1, 2)
        value = self.value_proj(encoder_outputs).view(batch_size, seq_len, self.num_heads, self.head_dim).transpose(1, 2)

        # Scaled dot-product attention
        scores = torch.matmul(query, key.transpose(-2, -1)) / self.scale  # (batch, heads, 1, seq_len)

        if mask is not None:
            scores = scores.masked_fill(mask.unsqueeze(1).unsqueeze(2) == 0, float('-inf'))

        weights = F.softmax(scores, dim=-1)
        context = torch.matmul(weights, value)  # (batch, heads, 1, head_dim)

        # Concatenate heads
        context = context.transpose(1, 2).contiguous().view(batch_size, -1)  # (batch, encoder_dim)
        context = self.out_proj(context)

        return context, weights.squeeze(2)


class BahdanauAttention(nn.Module):
    """Bahdanau (additive) attention mechanism."""

    def __init__(self, encoder_dim: int, decoder_dim: int, attention_dim: int):
        super().__init__()
        self.encoder_att = nn.Linear(encoder_dim, attention_dim)
        self.decoder_att = nn.Linear(decoder_dim, attention_dim)
        self.full_att = nn.Linear(attention_dim, 1)

    def forward(self, encoder_outputs, decoder_hidden, mask=None):
        # encoder_outputs: (batch, seq_len, encoder_dim)
        # decoder_hidden: (batch, decoder_dim)

        att1 = self.encoder_att(encoder_outputs)  # (batch, seq_len, attention_dim)
        att2 = self.decoder_att(decoder_hidden).unsqueeze(1)  # (batch, 1, attention_dim)

        att = torch.tanh(att1 + att2)  # (batch, seq_len, attention_dim)
        scores = self.full_att(att).squeeze(-1)  # (batch, seq_len)

        if mask is not None:
            scores = scores.masked_fill(mask == 0, float('-inf'))

        weights = F.softmax(scores, dim=-1)  # (batch, seq_len)
        context = torch.bmm(weights.unsqueeze(1), encoder_outputs).squeeze(1)  # (batch, encoder_dim)

        return context, weights


class Encoder(nn.Module):
    """Bidirectional LSTM encoder with layer normalization."""

    def __init__(self, vocab_size: int, embed_dim: int, hidden_dim: int,
                 num_layers: int = 2, dropout: float = 0.3):
        super().__init__()
        self.embedding = nn.Embedding(vocab_size, embed_dim, padding_idx=PAD_IDX)
        self.layer_norm = nn.LayerNorm(embed_dim)
        self.lstm = nn.LSTM(embed_dim, hidden_dim, num_layers=num_layers,
                           batch_first=True, bidirectional=True,
                           dropout=dropout if num_layers > 1 else 0)
        self.output_norm = nn.LayerNorm(hidden_dim * 2)
        self.dropout = nn.Dropout(dropout)
        self.hidden_dim = hidden_dim
        self.num_layers = num_layers

    def forward(self, x, lengths=None):
        # x: (batch, seq_len)
        embedded = self.dropout(self.layer_norm(self.embedding(x)))  # (batch, seq_len, embed_dim)

        if lengths is not None and x.device.type == 'cuda':
            # Use pack_padded_sequence for CUDA
            lengths_cpu = lengths.cpu()
            packed = nn.utils.rnn.pack_padded_sequence(
                embedded, lengths_cpu, batch_first=True, enforce_sorted=False
            )
            outputs, (hidden, cell) = self.lstm(packed)
            outputs, _ = nn.utils.rnn.pad_packed_sequence(outputs, batch_first=True)
        else:
            outputs, (hidden, cell) = self.lstm(embedded)

        # Apply layer norm to outputs
        outputs = self.output_norm(outputs)

        # outputs: (batch, seq_len, hidden_dim*2)
        # Combine bidirectional hidden states
        batch_size = x.size(0)
        hidden = hidden.view(self.num_layers, 2, batch_size, self.hidden_dim)
        hidden = torch.cat([hidden[:, 0, :, :], hidden[:, 1, :, :]], dim=-1)

        cell = cell.view(self.num_layers, 2, batch_size, self.hidden_dim)
        cell = torch.cat([cell[:, 0, :, :], cell[:, 1, :, :]], dim=-1)

        return outputs, hidden, cell


class Decoder(nn.Module):
    """LSTM decoder with attention and residual connections."""

    def __init__(self, vocab_size: int, embed_dim: int, hidden_dim: int,
                 encoder_dim: int, attention_dim: int, num_layers: int = 2,
                 dropout: float = 0.3, attention_type: str = 'bahdanau'):
        super().__init__()
        self.vocab_size = vocab_size
        self.embedding = nn.Embedding(vocab_size, embed_dim, padding_idx=PAD_IDX)
        self.layer_norm = nn.LayerNorm(embed_dim)

        if attention_type == 'multihead':
            self.attention = MultiHeadAttention(encoder_dim, hidden_dim, num_heads=8)
        else:
            self.attention = BahdanauAttention(encoder_dim, hidden_dim, attention_dim)

        self.lstm = nn.LSTM(embed_dim + encoder_dim, hidden_dim, num_layers=num_layers,
                           batch_first=True, dropout=dropout if num_layers > 1 else 0)

        # Output projection with intermediate layer
        self.fc1 = nn.Linear(hidden_dim + encoder_dim, hidden_dim)
        self.fc2 = nn.Linear(hidden_dim, vocab_size)
        self.dropout = nn.Dropout(dropout)

    def forward(self, input_token, encoder_outputs, hidden, cell, mask=None):
        # input_token: (batch,)
        embedded = self.dropout(self.layer_norm(self.embedding(input_token.unsqueeze(1))))  # (batch, 1, embed_dim)

        # Get attention context
        context, _ = self.attention(encoder_outputs, hidden[-1], mask)  # (batch, encoder_dim)

        # Concatenate embedded input with context
        lstm_input = torch.cat([embedded, context.unsqueeze(1)], dim=-1)

        output, (hidden, cell) = self.lstm(lstm_input, (hidden, cell))

        # Combine LSTM output with context for prediction
        combined = torch.cat([output.squeeze(1), context], dim=-1)
        combined = self.dropout(F.relu(self.fc1(combined)))
        prediction = self.fc2(combined)

        return prediction, hidden, cell


class Seq2SeqModel(nn.Module):
    """Complete Seq2Seq model with attention."""

    def __init__(self, encoder: Encoder, decoder: Decoder, device: torch.device):
        super().__init__()
        self.encoder = encoder
        self.decoder = decoder
        self.device = device

    def create_mask(self, src):
        """Create mask for padding positions."""
        return (src != PAD_IDX).to(src.device)

    def forward(self, src, src_lens, trg, teacher_forcing_ratio: float = 0.5):
        batch_size = src.size(0)
        trg_len = trg.size(1)
        trg_vocab_size = self.decoder.vocab_size

        outputs = torch.zeros(batch_size, trg_len - 1, trg_vocab_size, device=self.device)

        # Create mask
        mask = self.create_mask(src)

        # Encode
        encoder_outputs, hidden, cell = self.encoder(src, src_lens)

        # First decoder input is SOS token
        decoder_input = trg[:, 0]

        for t in range(1, trg_len):
            output, hidden, cell = self.decoder(decoder_input, encoder_outputs, hidden, cell, mask)
            outputs[:, t - 1] = output

            # Teacher forcing
            use_teacher_forcing = random.random() < teacher_forcing_ratio
            top1 = output.argmax(1)
            decoder_input = trg[:, t] if use_teacher_forcing else top1

        return outputs

    @torch.no_grad()
    def inference(self, src, src_lens, max_len: int = 64):
        """Greedy decoding for inference."""
        batch_size = src.size(0)
        mask = self.create_mask(src)

        # Encode
        encoder_outputs, hidden, cell = self.encoder(src, src_lens)

        # Start with SOS
        decoder_input = torch.full((batch_size,), SOS_IDX, dtype=torch.long, device=self.device)

        outputs = []
        for _ in range(max_len):
            output, hidden, cell = self.decoder(decoder_input, encoder_outputs, hidden, cell, mask)
            top1 = output.argmax(1)
            outputs.append(top1)

            if (top1 == EOS_IDX).all():
                break

            decoder_input = top1

        return torch.stack(outputs, dim=1)


class WarmupCosineScheduler:
    """Learning rate scheduler with linear warmup and cosine decay."""

    def __init__(self, optimizer, warmup_steps: int, total_steps: int,
                 min_lr: float = 1e-6, max_lr: float = 1e-3):
        self.optimizer = optimizer
        self.warmup_steps = warmup_steps
        self.total_steps = total_steps
        self.min_lr = min_lr
        self.max_lr = max_lr
        self.current_step = 0

    def step(self):
        self.current_step += 1
        lr = self.get_lr()
        for param_group in self.optimizer.param_groups:
            param_group['lr'] = lr
        return lr

    def get_lr(self):
        if self.current_step < self.warmup_steps:
            # Linear warmup
            return self.min_lr + (self.max_lr - self.min_lr) * (self.current_step / self.warmup_steps)
        else:
            # Cosine decay
            progress = (self.current_step - self.warmup_steps) / (self.total_steps - self.warmup_steps)
            return self.min_lr + 0.5 * (self.max_lr - self.min_lr) * (1 + math.cos(math.pi * progress))


def train_epoch(model, dataloader, optimizer, criterion, scaler, scheduler,
                grad_accum_steps: int = 1, precision: str = 'bf16'):
    """Train for one epoch with mixed precision and gradient accumulation."""
    model.train()
    total_loss = 0
    num_batches = 0

    optimizer.zero_grad(set_to_none=True)  # Faster than zero_grad()

    iterator = tqdm(dataloader, desc="Training", leave=False) if HAS_TQDM else dataloader

    use_amp = precision in ['fp16', 'bf16']
    amp_dtype = torch.bfloat16 if precision == 'bf16' else torch.float16

    for batch_idx, batch in enumerate(iterator):
        pinyin = batch['pinyin'].to(model.device, non_blocking=True)
        hanzi = batch['hanzi'].to(model.device, non_blocking=True)
        pinyin_lens = batch['pinyin_lens']

        # Mixed precision forward pass
        with autocast('cuda', enabled=use_amp, dtype=amp_dtype):
            outputs = model(pinyin, pinyin_lens, hanzi, teacher_forcing_ratio=0.5)

            output_dim = outputs.shape[-1]
            outputs_flat = outputs.reshape(-1, output_dim)
            target = hanzi[:, 1:].reshape(-1)

            loss = criterion(outputs_flat, target)
            loss = loss / grad_accum_steps

        # Backward pass with gradient scaling
        scaler.scale(loss).backward()

        if (batch_idx + 1) % grad_accum_steps == 0:
            # Gradient clipping
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)

            # Optimizer step
            scaler.step(optimizer)
            scaler.update()
            optimizer.zero_grad(set_to_none=True)

            # Learning rate scheduling
            if scheduler is not None:
                scheduler.step()

        total_loss += loss.item() * grad_accum_steps
        num_batches += 1

        if HAS_TQDM:
            iterator.set_postfix({'loss': f'{total_loss / num_batches:.4f}'})

    return total_loss / num_batches


@torch.no_grad()
def evaluate(model, dataloader, criterion, precision: str = 'bf16'):
    """Evaluate model."""
    model.eval()
    total_loss = 0
    num_batches = 0

    iterator = tqdm(dataloader, desc="Evaluating", leave=False) if HAS_TQDM else dataloader

    use_amp = precision in ['fp16', 'bf16']
    amp_dtype = torch.bfloat16 if precision == 'bf16' else torch.float16

    for batch in iterator:
        pinyin = batch['pinyin'].to(model.device, non_blocking=True)
        hanzi = batch['hanzi'].to(model.device, non_blocking=True)
        pinyin_lens = batch['pinyin_lens']

        with autocast('cuda', enabled=use_amp, dtype=amp_dtype):
            outputs = model(pinyin, pinyin_lens, hanzi, teacher_forcing_ratio=0)

            output_dim = outputs.shape[-1]
            outputs_flat = outputs.reshape(-1, output_dim)
            target = hanzi[:, 1:].reshape(-1)

            loss = criterion(outputs_flat, target)

        total_loss += loss.item()
        num_batches += 1

    return total_loss / num_batches


@torch.no_grad()
def calculate_accuracy(model, dataloader, hanzi_vocab, num_samples: int = 500):
    """Calculate character-level accuracy on a subset (fast version)."""
    model.eval()
    total_chars = 0
    correct_chars = 0
    samples_processed = 0

    for batch in dataloader:
        if samples_processed >= num_samples:
            break

        pinyin = batch['pinyin'].to(model.device, non_blocking=True)
        pinyin_lens = batch['pinyin_lens']
        hanzi = batch['hanzi']

        # Use smaller max_len for faster inference during accuracy check
        predictions = model.inference(pinyin, pinyin_lens, max_len=48)

        batch_size = min(len(pinyin), num_samples - samples_processed)
        for i in range(batch_size):
            pred = predictions[i].cpu().tolist()
            target = hanzi[i, 1:].tolist()

            pred = [p for p in pred if p not in [PAD_IDX, EOS_IDX]]
            target = [t for t in target if t not in [PAD_IDX, EOS_IDX]]

            min_len = min(len(pred), len(target))
            for j in range(min_len):
                total_chars += 1
                if pred[j] == target[j]:
                    correct_chars += 1

            total_chars += abs(len(pred) - len(target))
            samples_processed += 1

    return correct_chars / total_chars if total_chars > 0 else 0


def save_checkpoint(model, optimizer, scaler, epoch, loss, accuracy, path: str, config: dict):
    """Save training checkpoint."""
    checkpoint = {
        'epoch': epoch,
        'model_state_dict': model.state_dict(),
        'optimizer_state_dict': optimizer.state_dict(),
        'scaler_state_dict': scaler.state_dict(),
        'loss': loss,
        'accuracy': accuracy,
        'config': config
    }
    torch.save(checkpoint, path)
    print(f"Checkpoint saved: {path}")


def load_checkpoint(path: str, model, optimizer=None, scaler=None):
    """Load training checkpoint."""
    checkpoint = torch.load(path, map_location='cuda')
    model.load_state_dict(checkpoint['model_state_dict'])

    if optimizer is not None:
        optimizer.load_state_dict(checkpoint['optimizer_state_dict'])
    if scaler is not None:
        scaler.load_state_dict(checkpoint['scaler_state_dict'])

    return checkpoint['epoch'], checkpoint['loss'], checkpoint.get('accuracy', 0), checkpoint['config']


def export_to_onnx(model, pinyin_vocab, hanzi_vocab, output_path: str):
    """Export model to ONNX format."""
    model.eval()
    model.to('cpu')

    # Export encoder
    class EncoderWrapper(nn.Module):
        def __init__(self, encoder):
            super().__init__()
            self.encoder = encoder

        def forward(self, src):
            lengths = torch.tensor([src.size(1)])
            outputs, hidden, cell = self.encoder(src, lengths)
            return outputs, hidden, cell

    encoder_wrapper = EncoderWrapper(model.encoder)
    encoder_path = output_path.replace('.onnx', '_encoder.onnx')

    dummy_src = torch.randint(4, len(pinyin_vocab), (1, 20))

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

    encoder_outputs, hidden, cell = model.encoder(dummy_src, torch.tensor([20]))
    dummy_token = torch.tensor([SOS_IDX])

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


def main():
    parser = argparse.ArgumentParser(description='Train BiLSTM+Attention Pinyin model (CUDA optimized)')

    # Data arguments
    parser.add_argument('--dataset', type=str, default='../hmm_dataset_ime.json',
                       help='Path to training dataset')
    parser.add_argument('--output-dir', type=str, default='./output_small',
                       help='Output directory for models and logs')
    parser.add_argument('--resume', type=str, default=None,
                       help='Path to checkpoint to resume from')

    # Model arguments (SMALL - reduced for mobile deployment)
    parser.add_argument('--embed-dim', type=int, default=128,
                       help='Embedding dimension (128 for small model)')
    parser.add_argument('--hidden-dim', type=int, default=256,
                       help='LSTM hidden dimension (256 for small model)')
    parser.add_argument('--attention-dim', type=int, default=128,
                       help='Attention dimension (128 for small model)')
    parser.add_argument('--num-layers', type=int, default=2,
                       help='Number of LSTM layers (2 for small model)')
    parser.add_argument('--dropout', type=float, default=0.2,
                       help='Dropout rate (0.2 for small model)')
    parser.add_argument('--attention-type', type=str, default='bahdanau',
                       choices=['bahdanau', 'multihead'],
                       help='Attention mechanism type')

    # Training arguments
    parser.add_argument('--epochs', type=int, default=30,
                       help='Number of training epochs')
    parser.add_argument('--batch-size', type=int, default=256,
                       help='Batch size (256 for small model - uses less memory)')
    parser.add_argument('--grad-accum', type=int, default=2,
                       help='Gradient accumulation steps (effective batch = batch-size * grad-accum)')
    parser.add_argument('--lr', type=float, default=5e-4,
                       help='Peak learning rate')
    parser.add_argument('--min-lr', type=float, default=1e-6,
                       help='Minimum learning rate')
    parser.add_argument('--warmup-epochs', type=float, default=1,
                       help='Warmup epochs')
    parser.add_argument('--weight-decay', type=float, default=0.01,
                       help='Weight decay')
    parser.add_argument('--max-len', type=int, default=64,
                       help='Maximum sequence length')
    parser.add_argument('--val-split', type=float, default=0.05,
                       help='Validation split ratio')

    # CUDA arguments
    parser.add_argument('--precision', type=str, default='bf16',
                       choices=['fp32', 'fp16', 'bf16'],
                       help='Training precision (bf16 recommended for 4090)')
    parser.add_argument('--compile', action='store_true',
                       help='Enable torch.compile() (often slower for LSTM models)')
    parser.add_argument('--num-workers', type=int, default=4,
                       help='DataLoader num_workers')
    parser.add_argument('--bucket-batching', action='store_true', default=True,
                       help='Use bucket batching to minimize padding')

    # Output arguments
    parser.add_argument('--save-every', type=int, default=5,
                       help='Save checkpoint every N epochs')
    parser.add_argument('--eval-every', type=int, default=3,
                       help='Evaluate every N epochs')
    parser.add_argument('--export-onnx', action='store_true',
                       help='Export to ONNX after training')

    args = parser.parse_args()

    # Setup output directory
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    checkpoint_dir = output_dir / 'checkpoints'
    checkpoint_dir.mkdir(exist_ok=True)

    # Setup CUDA
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is not available. This script requires a CUDA-capable GPU.")

    device = torch.device('cuda')

    # Print GPU info
    print("=" * 70)
    print("CUDA Configuration")
    print("=" * 70)
    print(f"PyTorch version: {torch.__version__}")
    print(f"CUDA version: {torch.version.cuda}")
    print(f"cuDNN version: {torch.backends.cudnn.version()}")
    print(f"Device: {torch.cuda.get_device_name(0)}")
    print(f"Memory: {torch.cuda.get_device_properties(0).total_memory / 1024**3:.1f} GB")
    print(f"Precision: {args.precision}")

    # Enable cuDNN benchmark for optimized performance
    torch.backends.cudnn.benchmark = True
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True

    # Check BF16 support
    if args.precision == 'bf16':
        if not torch.cuda.is_bf16_supported():
            print("Warning: BF16 not supported on this GPU, falling back to FP16")
            args.precision = 'fp16'

    # Load dataset
    print("\n" + "=" * 70)
    print("Loading Dataset")
    print("=" * 70)

    print(f"Loading from {args.dataset}...")
    with open(args.dataset, 'r', encoding='utf-8') as f:
        data = json.load(f)
    samples = data['data']
    print(f"Total samples: {len(samples):,}")
    print(f"Metadata: {data.get('metadata', {})}")

    # Build vocabularies
    print("\nBuilding vocabularies...")
    pinyin_vocab = Vocabulary()
    hanzi_vocab = Vocabulary()

    # Detect if data uses character-level pinyin (no spaces) or syllable-level (with spaces)
    # Check first 10 samples to determine format
    has_spaces = any(' ' in sample['pinyin'] for sample in samples[:10])
    char_level_pinyin = not has_spaces

    if char_level_pinyin:
        print("Detected IME format (character-level pinyin, no spaces)")
        for sample in samples:
            # Character-level: each character is a token
            pinyin_chars = list(sample['pinyin'].lower())
            pinyin_vocab.add_tokens(pinyin_chars)
            hanzi_vocab.add_tokens(list(sample['hanzi']))
    else:
        print("Detected syllable-level pinyin (with spaces)")
        for sample in samples:
            pinyin_vocab.add_tokens(sample['pinyin'].split())
            hanzi_vocab.add_tokens(list(sample['hanzi']))

    print(f"Pinyin vocabulary: {len(pinyin_vocab):,} tokens")
    print(f"Hanzi vocabulary: {len(hanzi_vocab):,} tokens")

    # Save vocabularies
    pinyin_vocab.save(str(output_dir / 'pinyin_vocab.json'))
    hanzi_vocab.save(str(output_dir / 'hanzi_vocab.json'))

    # Split dataset
    random.shuffle(samples)
    val_size = int(len(samples) * args.val_split)
    train_samples = samples[val_size:]
    val_samples = samples[:val_size]

    print(f"\nTrain samples: {len(train_samples):,}")
    print(f"Validation samples: {len(val_samples):,}")

    # Create datasets
    print("\nPre-processing datasets...")
    train_dataset = PinyinDataset(train_samples, pinyin_vocab, hanzi_vocab, args.max_len,
                                   char_level_pinyin=char_level_pinyin)
    val_dataset = PinyinDataset(val_samples, pinyin_vocab, hanzi_vocab, args.max_len,
                                 char_level_pinyin=char_level_pinyin)

    # Create dataloaders with pinned memory for faster GPU transfer
    if args.bucket_batching:
        print("Using bucket batching for efficient padding...")
        train_lengths = [item[2] for item in train_dataset.processed]
        train_sampler = BucketBatchSampler(train_lengths, args.batch_size, shuffle=True, num_buckets=20)
        train_loader = DataLoader(
            train_dataset,
            batch_sampler=train_sampler,
            collate_fn=collate_fn,
            num_workers=args.num_workers,
            pin_memory=True,
            persistent_workers=True if args.num_workers > 0 else False,
            prefetch_factor=4 if args.num_workers > 0 else None
        )
    else:
        train_loader = DataLoader(
            train_dataset,
            batch_size=args.batch_size,
            shuffle=True,
            collate_fn=collate_fn,
            num_workers=args.num_workers,
            pin_memory=True,
            persistent_workers=True if args.num_workers > 0 else False,
            prefetch_factor=4 if args.num_workers > 0 else None
        )

    val_loader = DataLoader(
        val_dataset,
        batch_size=args.batch_size,  # Same as train batch
        shuffle=False,
        collate_fn=collate_fn,
        num_workers=args.num_workers,
        pin_memory=True,
        persistent_workers=True if args.num_workers > 0 else False
    )

    # Create model
    print("\n" + "=" * 70)
    print("Creating Model")
    print("=" * 70)

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
        hidden_dim=args.hidden_dim * 2,
        encoder_dim=args.hidden_dim * 2,
        attention_dim=args.attention_dim,
        num_layers=args.num_layers,
        dropout=args.dropout,
        attention_type=args.attention_type
    )

    model = Seq2SeqModel(encoder, decoder, device).to(device)

    # Model statistics
    total_params = sum(p.numel() for p in model.parameters())
    trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)

    print(f"Architecture: BiLSTM + {args.attention_type.capitalize()} Attention")
    print(f"Embed dim: {args.embed_dim}")
    print(f"Hidden dim: {args.hidden_dim}")
    print(f"Num layers: {args.num_layers}")
    print(f"Total parameters: {total_params:,}")
    print(f"Trainable parameters: {trainable_params:,}")
    print(f"Estimated size: {total_params * 4 / (1024**2):.1f} MB (FP32)")

    # Compile model for PyTorch 2.x (disabled by default for LSTM - often slower)
    # torch.compile works better with Transformers than LSTMs
    if args.compile and hasattr(torch, 'compile'):
        print("\nCompiling model with torch.compile()...")
        try:
            model = torch.compile(model, mode='default')
            print("Model compiled successfully!")
        except Exception as e:
            print(f"Warning: torch.compile() failed: {e}")
            print("Continuing without compilation...")

    # Optimizer
    optimizer = torch.optim.AdamW(
        model.parameters(),
        lr=args.lr,
        weight_decay=args.weight_decay,
        betas=(0.9, 0.98),
        eps=1e-8
    )

    # Scheduler
    steps_per_epoch = len(train_loader) // args.grad_accum
    total_steps = steps_per_epoch * args.epochs
    warmup_steps = int(steps_per_epoch * args.warmup_epochs)

    scheduler = WarmupCosineScheduler(
        optimizer,
        warmup_steps=warmup_steps,
        total_steps=total_steps,
        min_lr=args.min_lr,
        max_lr=args.lr
    )

    # Loss and scaler
    criterion = nn.CrossEntropyLoss(ignore_index=PAD_IDX, label_smoothing=0.1)
    scaler = GradScaler(enabled=(args.precision in ['fp16', 'bf16']))

    # Resume from checkpoint
    start_epoch = 0
    best_val_loss = float('inf')

    if args.resume:
        print(f"\nResuming from checkpoint: {args.resume}")
        start_epoch, best_val_loss, _, config = load_checkpoint(
            args.resume, model, optimizer, scaler
        )
        start_epoch += 1
        print(f"Resumed from epoch {start_epoch}, best val loss: {best_val_loss:.4f}")

    # TensorBoard
    writer = None
    if HAS_TENSORBOARD:
        log_dir = output_dir / 'logs' / datetime.now().strftime('%Y%m%d_%H%M%S')
        writer = SummaryWriter(log_dir)
        print(f"\nTensorBoard logs: {log_dir}")

    # Save config
    config = vars(args)
    config['pinyin_vocab_size'] = len(pinyin_vocab)
    config['hanzi_vocab_size'] = len(hanzi_vocab)
    config['total_params'] = total_params
    config['char_level_pinyin'] = char_level_pinyin

    with open(output_dir / 'config.json', 'w') as f:
        json.dump(config, f, indent=2)

    # Training loop
    print("\n" + "=" * 70)
    print("Starting Training")
    print("=" * 70)
    print(f"Epochs: {args.epochs}")
    print(f"Batch size: {args.batch_size}")
    print(f"Gradient accumulation: {args.grad_accum}")
    print(f"Effective batch size: {args.batch_size * args.grad_accum}")
    print(f"Steps per epoch: {steps_per_epoch}")
    print(f"Total steps: {total_steps}")
    print(f"Warmup steps: {warmup_steps}")
    print("=" * 70 + "\n")

    best_model_state = None
    epoch_times = []

    # Clear CUDA cache before training
    torch.cuda.empty_cache()
    torch.cuda.reset_peak_memory_stats()

    for epoch in range(start_epoch, args.epochs):
        start_time = time.time()

        # Train
        train_loss = train_epoch(
            model, train_loader, optimizer, criterion, scaler, scheduler,
            grad_accum_steps=args.grad_accum, precision=args.precision
        )

        elapsed = time.time() - start_time
        epoch_times.append(elapsed)

        # Calculate ETA
        avg_time = sum(epoch_times[-5:]) / len(epoch_times[-5:])  # Use last 5 epochs
        remaining_epochs = args.epochs - epoch - 1
        eta_seconds = avg_time * remaining_epochs
        eta_str = f"{int(eta_seconds//60)}m{int(eta_seconds%60)}s" if eta_seconds < 3600 else f"{eta_seconds/3600:.1f}h"

        current_lr = optimizer.param_groups[0]['lr']

        # Evaluate periodically
        if (epoch + 1) % args.eval_every == 0 or epoch == args.epochs - 1:
            val_loss = evaluate(model, val_loader, criterion, args.precision)

            # Calculate accuracy less frequently
            accuracy = 0
            if (epoch + 1) % 10 == 0 or epoch == args.epochs - 1:
                accuracy = calculate_accuracy(model, val_loader, hanzi_vocab, num_samples=500)

            # Log to TensorBoard
            if writer:
                writer.add_scalar('Loss/train', train_loss, epoch)
                writer.add_scalar('Loss/val', val_loss, epoch)
                writer.add_scalar('Learning_rate', current_lr, epoch)
                if accuracy > 0:
                    writer.add_scalar('Accuracy/val', accuracy, epoch)

            # Print progress
            if accuracy > 0:
                print(f"Epoch {epoch+1:3d}/{args.epochs} | "
                      f"Train: {train_loss:.4f} | Val: {val_loss:.4f} | "
                      f"Acc: {accuracy*100:.1f}% | LR: {current_lr:.1e} | "
                      f"{elapsed:.0f}s | ETA: {eta_str}")
            else:
                print(f"Epoch {epoch+1:3d}/{args.epochs} | "
                      f"Train: {train_loss:.4f} | Val: {val_loss:.4f} | "
                      f"LR: {current_lr:.1e} | {elapsed:.0f}s | ETA: {eta_str}")

            # Save best model
            if val_loss < best_val_loss:
                best_val_loss = val_loss
                best_model_state = {k: v.cpu().clone() for k, v in model.state_dict().items()}
                print(f"  >> New best! Val loss: {val_loss:.4f}")
        else:
            # Quick progress for non-eval epochs
            print(f"Epoch {epoch+1:3d}/{args.epochs} | "
                  f"Train: {train_loss:.4f} | LR: {current_lr:.1e} | "
                  f"{elapsed:.0f}s | ETA: {eta_str}")

            # Still log to TensorBoard
            if writer:
                writer.add_scalar('Loss/train', train_loss, epoch)
                writer.add_scalar('Learning_rate', current_lr, epoch)

        # Save checkpoint
        if (epoch + 1) % args.save_every == 0:
            checkpoint_path = checkpoint_dir / f'checkpoint_epoch_{epoch+1}.pt'
            save_checkpoint(model, optimizer, scaler, epoch, train_loss, 0,
                          str(checkpoint_path), config)

    # Load best model
    if best_model_state:
        model.load_state_dict(best_model_state)
        model.to(device)

    # Final evaluation
    print("\n" + "=" * 70)
    print("Final Evaluation")
    print("=" * 70)

    final_val_loss = evaluate(model, val_loader, criterion, args.precision)
    final_accuracy = calculate_accuracy(model, val_loader, hanzi_vocab, num_samples=len(val_samples))

    print(f"Final validation loss: {final_val_loss:.4f}")
    print(f"Final character accuracy: {final_accuracy*100:.2f}%")

    # Save final model
    final_model_path = output_dir / 'bilstm_pinyin_final.pt'
    torch.save({
        'model_state_dict': model.state_dict(),
        'config': config,
        'val_loss': final_val_loss,
        'accuracy': final_accuracy
    }, final_model_path)
    print(f"\nFinal model saved: {final_model_path}")

    # Export to ONNX
    if args.export_onnx:
        print("\n" + "=" * 70)
        print("Exporting to ONNX")
        print("=" * 70)
        onnx_path = str(output_dir / 'bilstm_pinyin.onnx')
        export_to_onnx(model, pinyin_vocab, hanzi_vocab, onnx_path)

    # Cleanup
    if writer:
        writer.close()

    print("\n" + "=" * 70)
    print("Training Complete!")
    print("=" * 70)
    print(f"Best validation loss: {best_val_loss:.4f}")
    print(f"Final accuracy: {final_accuracy*100:.2f}%")
    print(f"Output directory: {output_dir}")


if __name__ == '__main__':
    main()
