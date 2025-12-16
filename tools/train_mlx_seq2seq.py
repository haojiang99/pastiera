#!/usr/bin/env python3
"""
MLX BiLSTM+Attention Seq2Seq for Pinyin-to-Chinese

Apple's MLX framework for efficient training on Apple Silicon.
Leverages GPU and Neural Engine for faster training than PyTorch MPS.

Usage:
    python train_mlx_seq2seq.py --dataset ../pinyin_dataset.json --epochs 30
"""

import argparse
import json
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np

# Install MLX if needed
try:
    import mlx.core as mx
    import mlx.nn as nn
    import mlx.optimizers as optim
    import mlx.utils
except ImportError:
    print("Installing MLX...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "mlx"])
    import mlx.core as mx
    import mlx.nn as nn
    import mlx.optimizers as optim
    import mlx.utils


class Vocabulary:
    """Vocabulary management for pinyin and characters."""

    def __init__(self):
        self.char2id = {'<PAD>': 0, '<SOS>': 1, '<EOS>': 2, '<UNK>': 3}
        self.id2char = {0: '<PAD>', 1: '<SOS>', 2: '<EOS>', 3: '<UNK>'}
        self.pinyin2id = {'<PAD>': 0, '<UNK>': 1}
        self.id2pinyin = {0: '<PAD>', 1: '<UNK>'}

    def build(self, data):
        char_counter = Counter()
        pinyin_counter = Counter()

        for item in data:
            for p in item['pinyin'].split():
                pinyin_counter[p] += 1
            for c in item['hanzi']:
                char_counter[c] += 1

        for char in char_counter:
            if char not in self.char2id:
                idx = len(self.char2id)
                self.char2id[char] = idx
                self.id2char[idx] = char

        for pinyin in pinyin_counter:
            if pinyin not in self.pinyin2id:
                idx = len(self.pinyin2id)
                self.pinyin2id[pinyin] = idx
                self.id2pinyin[idx] = pinyin

        print(f"Vocab: {len(self.char2id)} chars, {len(self.pinyin2id)} pinyin")

    def encode_pinyin(self, text):
        return [self.pinyin2id.get(p, 1) for p in text.split()]

    def encode_hanzi(self, text, add_sos=True, add_eos=True):
        ids = [self.char2id.get(c, 3) for c in text]
        if add_sos:
            ids = [1] + ids
        if add_eos:
            ids = ids + [2]
        return ids

    def decode(self, ids):
        chars = []
        for i in ids:
            if i == 2:  # EOS
                break
            if i > 3:  # Skip special tokens
                chars.append(self.id2char.get(i, '?'))
        return ''.join(chars)


class Encoder(nn.Module):
    """BiLSTM Encoder."""

    def __init__(self, vocab_size, embed_dim, hidden_dim):
        super().__init__()
        self.embed = nn.Embedding(vocab_size, embed_dim)
        # Forward and backward LSTMs
        self.lstm_fwd = nn.LSTM(embed_dim, hidden_dim)
        self.lstm_bwd = nn.LSTM(embed_dim, hidden_dim)
        self.hidden_dim = hidden_dim

    def __call__(self, x):
        # x: (batch, seq_len)
        emb = self.embed(x)  # (batch, seq_len, embed_dim)

        # Forward LSTM - MLX returns (output, hidden_states)
        # output: (batch, seq_len, hidden)
        # hidden_states: (batch, seq_len, hidden) - hidden at each timestep
        fwd_out, fwd_hidden_all = self.lstm_fwd(emb)

        # Backward LSTM (reverse input)
        rev_emb = emb[:, ::-1, :]
        bwd_out, bwd_hidden_all = self.lstm_bwd(rev_emb)
        bwd_out = bwd_out[:, ::-1, :]  # Reverse back

        # Concatenate bidirectional outputs
        outputs = mx.concatenate([fwd_out, bwd_out], axis=-1)  # (batch, seq, hidden*2)

        # Get final hidden states from last timestep
        # fwd_hidden_all: (batch, seq_len, hidden) -> take [:, -1, :] for final
        # For backward, the "final" state is at index 0 of the reversed sequence
        fwd_h_final = fwd_hidden_all[:, -1, :]  # (batch, hidden)
        bwd_h_final = bwd_hidden_all[:, -1, :]  # (batch, hidden)

        # Concatenate bidirectional hidden states
        hidden = mx.concatenate([fwd_h_final, bwd_h_final], axis=-1)  # (batch, hidden*2)

        # MLX LSTM doesn't return cell state, use zeros
        cell = mx.zeros_like(hidden)

        return outputs, hidden, cell


class Attention(nn.Module):
    """Bahdanau Attention."""

    def __init__(self, encoder_dim, decoder_dim):
        super().__init__()
        self.W1 = nn.Linear(encoder_dim, decoder_dim, bias=False)
        self.W2 = nn.Linear(decoder_dim, decoder_dim, bias=False)
        self.V = nn.Linear(decoder_dim, 1, bias=False)

    def __call__(self, encoder_outputs, decoder_hidden):
        # encoder_outputs: (batch, src_len, encoder_dim)
        # decoder_hidden: (batch, decoder_dim)

        # Project encoder outputs
        enc_proj = self.W1(encoder_outputs)  # (batch, src_len, decoder_dim)

        # Project decoder hidden
        dec_proj = self.W2(decoder_hidden)  # (batch, decoder_dim)
        dec_proj = mx.expand_dims(dec_proj, axis=1)  # (batch, 1, decoder_dim)

        # Compute attention scores
        scores = self.V(mx.tanh(enc_proj + dec_proj))  # (batch, src_len, 1)
        scores = scores.squeeze(-1)  # (batch, src_len)

        # Softmax
        weights = mx.softmax(scores, axis=-1)  # (batch, src_len)

        # Context vector
        weights_exp = mx.expand_dims(weights, axis=-1)  # (batch, src_len, 1)
        context = mx.sum(encoder_outputs * weights_exp, axis=1)  # (batch, encoder_dim)

        return context, weights


class Decoder(nn.Module):
    """LSTM Decoder with Attention."""

    def __init__(self, vocab_size, embed_dim, hidden_dim, encoder_dim):
        super().__init__()
        self.embed = nn.Embedding(vocab_size, embed_dim)
        self.attention = Attention(encoder_dim, hidden_dim)
        self.lstm = nn.LSTM(embed_dim + encoder_dim, hidden_dim)
        self.fc = nn.Linear(hidden_dim, vocab_size)
        self.hidden_dim = hidden_dim

    def __call__(self, input_token, encoder_outputs, hidden, cell):
        # input_token: (batch,)
        emb = self.embed(input_token)  # (batch, embed_dim)

        # Attention
        context, attn_weights = self.attention(encoder_outputs, hidden)

        # Combine embedding and context
        lstm_input = mx.concatenate([emb, context], axis=-1)  # (batch, embed+encoder_dim)
        lstm_input = mx.expand_dims(lstm_input, axis=1)  # (batch, 1, input_dim)

        # LSTM step - MLX returns (output, hidden_states)
        output, hidden_states = self.lstm(lstm_input)

        # Get new hidden state from the single timestep output
        new_h = hidden_states[:, 0, :]  # (batch, hidden)

        # Output projection
        logits = self.fc(output.squeeze(1))  # (batch, vocab_size)

        return logits, new_h, new_h  # Use hidden as both h and c (MLX doesn't have cell state)


class Seq2Seq(nn.Module):
    """Complete Seq2Seq model."""

    def __init__(self, src_vocab, tgt_vocab, embed_dim=128, hidden_dim=256):
        super().__init__()
        self.encoder = Encoder(src_vocab, embed_dim, hidden_dim)
        self.decoder = Decoder(tgt_vocab, embed_dim, hidden_dim, hidden_dim * 2)

        # Bridge: project encoder hidden to decoder hidden
        self.bridge_h = nn.Linear(hidden_dim * 2, hidden_dim)
        self.bridge_c = nn.Linear(hidden_dim * 2, hidden_dim)

        self.hidden_dim = hidden_dim
        self.tgt_vocab = tgt_vocab

    def __call__(self, src, tgt):
        """Forward pass with teacher forcing."""
        batch_size = src.shape[0]
        tgt_len = tgt.shape[1]

        # Encode
        enc_outputs, enc_h, enc_c = self.encoder(src)

        # Bridge to decoder dimensions
        dec_h = mx.tanh(self.bridge_h(enc_h))
        dec_c = mx.tanh(self.bridge_c(enc_c))

        # Decode with teacher forcing
        all_logits = []
        for t in range(tgt_len - 1):
            input_token = tgt[:, t]
            logits, dec_h, dec_c = self.decoder(input_token, enc_outputs, dec_h, dec_c)
            all_logits.append(logits)

        # Stack: (batch, tgt_len-1, vocab)
        return mx.stack(all_logits, axis=1)

    def generate(self, src, max_len=50):
        """Greedy decoding for inference."""
        batch_size = src.shape[0]

        # Encode
        enc_outputs, enc_h, enc_c = self.encoder(src)
        dec_h = mx.tanh(self.bridge_h(enc_h))
        dec_c = mx.tanh(self.bridge_c(enc_c))

        # Start with SOS token
        input_token = mx.ones((batch_size,), dtype=mx.int32)  # SOS = 1

        outputs = []
        for _ in range(max_len):
            logits, dec_h, dec_c = self.decoder(input_token, enc_outputs, dec_h, dec_c)
            next_token = mx.argmax(logits, axis=-1)
            outputs.append(next_token)
            input_token = next_token

            # Stop if all sequences have EOS
            if mx.all(next_token == 2):
                break

        return mx.stack(outputs, axis=1)


def create_batch(data, vocab, batch_size, max_src=30, max_tgt=35):
    """Create a batch of data."""
    np.random.shuffle(data)

    batches = []
    for i in range(0, len(data), batch_size):
        batch_data = data[i:i+batch_size]

        src_batch = []
        tgt_batch = []

        for item in batch_data:
            src = vocab.encode_pinyin(item['pinyin'])[:max_src]
            tgt = vocab.encode_hanzi(item['hanzi'])[:max_tgt]

            # Pad
            src = src + [0] * (max_src - len(src))
            tgt = tgt + [0] * (max_tgt - len(tgt))

            src_batch.append(src)
            tgt_batch.append(tgt)

        batches.append({
            'src': mx.array(src_batch),
            'tgt': mx.array(tgt_batch)
        })

    return batches


def loss_fn(model, src, tgt):
    """Cross-entropy loss with masking."""
    logits = model(src, tgt)  # (batch, tgt_len-1, vocab)

    # Targets are shifted by 1 (predict next token)
    targets = tgt[:, 1:]  # (batch, tgt_len-1)

    # Compute cross-entropy
    batch_size, seq_len, vocab_size = logits.shape

    # Flatten for cross-entropy
    logits_flat = logits.reshape(-1, vocab_size)
    targets_flat = targets.reshape(-1)

    # Log softmax
    # Compute log_softmax manually: log(softmax(x)) = x - log(sum(exp(x)))
    log_probs = logits_flat - mx.logsumexp(logits_flat, axis=-1, keepdims=True)

    # Gather log probs for targets
    # Manual gather: create indices
    batch_indices = mx.arange(logits_flat.shape[0])
    target_log_probs = log_probs[batch_indices, targets_flat]

    # Mask padding (target = 0)
    mask = (targets_flat != 0).astype(mx.float32)

    # Compute masked loss
    loss = -mx.sum(target_log_probs * mask) / (mx.sum(mask) + 1e-8)

    return loss


def train_step(model, optimizer, src, tgt):
    """Single training step."""
    def compute_loss(model):
        return loss_fn(model, src, tgt)

    loss, grads = nn.value_and_grad(model, compute_loss)(model)
    optimizer.update(model, grads)
    mx.eval(model.parameters(), optimizer.state)
    return loss


def evaluate(model, data, vocab, num_samples=200):
    """Evaluate character accuracy."""
    correct = 0
    total = 0

    samples = data[:num_samples]

    for item in samples:
        src = vocab.encode_pinyin(item['pinyin'])
        src = mx.array([src])

        pred_ids = model.generate(src, max_len=len(item['hanzi']) + 5)
        pred_ids = pred_ids[0].tolist()

        pred_text = vocab.decode(pred_ids)
        target_text = item['hanzi']

        # Character-level accuracy
        for p, t in zip(pred_text, target_text):
            if p == t:
                correct += 1
            total += 1
        total += abs(len(target_text) - len(pred_text))

    return correct / max(total, 1)


def count_params(model):
    """Count total parameters."""
    total = 0

    def count_array(arr):
        if isinstance(arr, mx.array):
            return arr.size
        elif isinstance(arr, dict):
            return sum(count_array(v) for v in arr.values())
        elif isinstance(arr, list):
            return sum(count_array(v) for v in arr)
        return 0

    params = model.parameters()
    return count_array(params)


def save_model(model, vocab, path):
    """Save model weights and vocabulary."""
    # Save weights using MLX's native function
    # tree_flatten returns list of (key_path, array) tuples
    weights = model.parameters()
    flat_weights = mlx.utils.tree_flatten(weights)
    # Extract just the arrays with string keys based on path
    weight_dict = {'.'.join(k): v for k, v in flat_weights}
    mx.savez(path.replace('.npz', '_weights.npz'), **weight_dict)

    # Save vocab
    vocab_data = {
        'char2id': vocab.char2id,
        'pinyin2id': vocab.pinyin2id,
    }
    with open(path.replace('.npz', '_vocab.json'), 'w') as f:
        json.dump(vocab_data, f, ensure_ascii=False)

    print(f"Saved model to {path}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dataset', required=True)
    parser.add_argument('--epochs', type=int, default=30)
    parser.add_argument('--batch-size', type=int, default=64)
    parser.add_argument('--embed-dim', type=int, default=128)
    parser.add_argument('--hidden-dim', type=int, default=256)
    parser.add_argument('--lr', type=float, default=0.001)
    parser.add_argument('--output', default='mlx_seq2seq.npz')
    args = parser.parse_args()

    print("=" * 60)
    print("MLX BiLSTM+Attention Training")
    print("=" * 60)
    print("Using Apple Silicon GPU + Neural Engine")
    print()

    # Load data
    print(f"Loading {args.dataset}...")
    with open(args.dataset) as f:
        raw = json.load(f)
    data = raw['data'] if 'data' in raw else raw
    print(f"Loaded {len(data)} samples")

    # Build vocabulary
    vocab = Vocabulary()
    vocab.build(data)

    # Split data
    np.random.seed(42)
    np.random.shuffle(data)
    split = int(len(data) * 0.9)
    train_data = data[:split]
    test_data = data[split:]
    print(f"Train: {len(train_data)}, Test: {len(test_data)}")

    # Create model
    model = Seq2Seq(
        src_vocab=len(vocab.pinyin2id),
        tgt_vocab=len(vocab.char2id),
        embed_dim=args.embed_dim,
        hidden_dim=args.hidden_dim
    )
    mx.eval(model.parameters())

    num_params = count_params(model)
    print(f"Parameters: {num_params:,}")
    print(f"Est. size: {num_params * 4 / 1024 / 1024:.1f} MB (FP32)")

    # Optimizer
    optimizer = optim.Adam(learning_rate=args.lr)

    # Training
    print(f"\nTraining for {args.epochs} epochs...")
    start_time = time.time()
    best_acc = 0

    for epoch in range(args.epochs):
        epoch_start = time.time()

        # Create batches (shuffled each epoch)
        batches = create_batch(train_data, vocab, args.batch_size)

        total_loss = 0
        for i, batch in enumerate(batches):
            loss = train_step(model, optimizer, batch['src'], batch['tgt'])
            mx.eval(loss)
            total_loss += loss.item()

            if i % 100 == 0:
                print(f"  Batch {i}/{len(batches)}, Loss: {loss.item():.4f}")

        avg_loss = total_loss / len(batches)

        # Evaluate
        acc = evaluate(model, test_data, vocab)

        epoch_time = time.time() - epoch_start
        print(f"Epoch {epoch+1}/{args.epochs} - Loss: {avg_loss:.4f}, Acc: {acc:.2%}, Time: {epoch_time:.1f}s")

        if acc > best_acc:
            best_acc = acc
            save_model(model, vocab, args.output)
            print(f"  New best! Saved.")

    total_time = time.time() - start_time
    print(f"\nDone in {total_time/60:.1f} min, Best acc: {best_acc:.2%}")

    # Test examples
    print("\n" + "=" * 60)
    print("Sample predictions:")
    test_phrases = ["ni hao", "zhong guo", "wo ai ni"]
    for phrase in test_phrases:
        src = mx.array([vocab.encode_pinyin(phrase)])
        pred = model.generate(src)
        result = vocab.decode(pred[0].tolist())
        print(f"  {phrase} -> {result}")


if __name__ == '__main__':
    main()
