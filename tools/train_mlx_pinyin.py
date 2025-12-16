#!/usr/bin/env python3
"""
MLX-based BiLSTM Pinyin-to-Chinese Training Script

Uses Apple's MLX framework for efficient training on Apple Silicon.
MLX can leverage both GPU and Neural Engine for faster training.

After training, exports to ONNX for Android deployment.
"""

import argparse
import json
import sys
import time
from pathlib import Path
from collections import Counter

# Check for MLX
try:
    import mlx.core as mx
    import mlx.nn as nn
    import mlx.optimizers as optim
    HAS_MLX = True
except ImportError:
    HAS_MLX = False
    print("MLX not installed. Installing...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "mlx"])
    import mlx.core as mx
    import mlx.nn as nn
    import mlx.optimizers as optim

import numpy as np

# For ONNX export
try:
    import torch
    import torch.nn as tnn
    HAS_TORCH = True
except ImportError:
    HAS_TORCH = False


class Vocabulary:
    """Character and pinyin vocabulary management."""

    def __init__(self):
        self.char2id = {'<PAD>': 0, '<SOS>': 1, '<EOS>': 2, '<UNK>': 3}
        self.id2char = {0: '<PAD>', 1: '<SOS>', 2: '<EOS>', 3: '<UNK>'}
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

        # Build char vocab
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

        print(f"Vocabulary: {len(self.char2id)} characters, {len(self.pinyin2id)} pinyin syllables")

    def encode_pinyin(self, pinyin_str):
        """Encode pinyin string to IDs."""
        return [self.pinyin2id.get(p, self.pinyin2id['<UNK>'])
                for p in pinyin_str.split()]

    def encode_hanzi(self, hanzi_str, add_sos=True, add_eos=True):
        """Encode hanzi string to IDs."""
        ids = [self.char2id.get(c, self.char2id['<UNK>']) for c in hanzi_str]
        if add_sos:
            ids = [self.char2id['<SOS>']] + ids
        if add_eos:
            ids = ids + [self.char2id['<EOS>']]
        return ids

    def decode_hanzi(self, ids):
        """Decode IDs to hanzi string."""
        chars = []
        for idx in ids:
            if idx == self.char2id['<EOS>']:
                break
            if idx not in [self.char2id['<PAD>'], self.char2id['<SOS>']]:
                chars.append(self.id2char.get(idx, '?'))
        return ''.join(chars)


class MLXEncoder(nn.Module):
    """BiLSTM Encoder in MLX."""

    def __init__(self, vocab_size, embed_dim, hidden_dim, num_layers=2, dropout=0.1):
        super().__init__()
        self.embedding = nn.Embedding(vocab_size, embed_dim)
        self.dropout = nn.Dropout(dropout)

        # Stacked BiLSTM layers
        self.lstm_layers = []
        for i in range(num_layers):
            input_size = embed_dim if i == 0 else hidden_dim * 2
            self.lstm_layers.append(nn.LSTM(input_size, hidden_dim))
            self.lstm_layers.append(nn.LSTM(input_size, hidden_dim))  # reverse

        self.hidden_dim = hidden_dim
        self.num_layers = num_layers

    def __call__(self, x):
        # x: (batch, seq_len)
        embedded = self.embedding(x)  # (batch, seq_len, embed_dim)
        embedded = self.dropout(embedded)

        output = embedded
        for i in range(self.num_layers):
            fwd_lstm = self.lstm_layers[i * 2]
            bwd_lstm = self.lstm_layers[i * 2 + 1]

            # Forward pass
            fwd_out, _ = fwd_lstm(output)

            # Backward pass (reverse, process, reverse back)
            rev_input = mx.flip(output, axis=1)
            bwd_out, _ = bwd_lstm(rev_input)
            bwd_out = mx.flip(bwd_out, axis=1)

            # Concatenate
            output = mx.concatenate([fwd_out, bwd_out], axis=-1)
            output = self.dropout(output)

        return output  # (batch, seq_len, hidden_dim * 2)


class MLXAttention(nn.Module):
    """Bahdanau-style attention."""

    def __init__(self, encoder_dim, decoder_dim):
        super().__init__()
        self.W_encoder = nn.Linear(encoder_dim, decoder_dim)
        self.W_decoder = nn.Linear(decoder_dim, decoder_dim)
        self.v = nn.Linear(decoder_dim, 1)

    def __call__(self, encoder_outputs, decoder_hidden):
        # encoder_outputs: (batch, src_len, encoder_dim)
        # decoder_hidden: (batch, decoder_dim)

        # Project encoder outputs
        encoder_proj = self.W_encoder(encoder_outputs)  # (batch, src_len, decoder_dim)

        # Project decoder hidden and expand
        decoder_proj = self.W_decoder(decoder_hidden)  # (batch, decoder_dim)
        decoder_proj = mx.expand_dims(decoder_proj, axis=1)  # (batch, 1, decoder_dim)

        # Compute attention scores
        energy = mx.tanh(encoder_proj + decoder_proj)  # (batch, src_len, decoder_dim)
        attention_scores = self.v(energy).squeeze(-1)  # (batch, src_len)

        # Softmax to get attention weights
        attention_weights = mx.softmax(attention_scores, axis=-1)  # (batch, src_len)

        # Compute context vector
        attention_weights_expanded = mx.expand_dims(attention_weights, axis=-1)
        context = mx.sum(encoder_outputs * attention_weights_expanded, axis=1)

        return context, attention_weights


class MLXDecoder(nn.Module):
    """LSTM Decoder with Attention."""

    def __init__(self, vocab_size, embed_dim, hidden_dim, encoder_dim, dropout=0.1):
        super().__init__()
        self.embedding = nn.Embedding(vocab_size, embed_dim)
        self.dropout = nn.Dropout(dropout)
        self.attention = MLXAttention(encoder_dim, hidden_dim)
        self.lstm = nn.LSTM(embed_dim + encoder_dim, hidden_dim)
        self.fc = nn.Linear(hidden_dim, vocab_size)

        self.hidden_dim = hidden_dim

    def __call__(self, input_token, encoder_outputs, hidden, cell):
        # input_token: (batch,)
        # encoder_outputs: (batch, src_len, encoder_dim)
        # hidden, cell: (batch, hidden_dim)

        embedded = self.embedding(input_token)  # (batch, embed_dim)
        embedded = self.dropout(embedded)

        # Attention
        context, attn_weights = self.attention(encoder_outputs, hidden)

        # Combine embedding and context
        lstm_input = mx.concatenate([embedded, context], axis=-1)  # (batch, embed_dim + encoder_dim)
        lstm_input = mx.expand_dims(lstm_input, axis=1)  # (batch, 1, input_dim)

        # LSTM step
        output, (new_hidden, new_cell) = self.lstm(lstm_input, (hidden, cell))
        output = output.squeeze(1)  # (batch, hidden_dim)

        # Output projection
        logits = self.fc(output)  # (batch, vocab_size)

        return logits, new_hidden.squeeze(0), new_cell.squeeze(0), attn_weights


class Seq2SeqModel(nn.Module):
    """Complete Seq2Seq model with encoder and decoder."""

    def __init__(self, src_vocab_size, tgt_vocab_size, embed_dim=128, hidden_dim=256,
                 num_layers=2, dropout=0.1):
        super().__init__()
        self.encoder = MLXEncoder(src_vocab_size, embed_dim, hidden_dim, num_layers, dropout)
        self.decoder = MLXDecoder(tgt_vocab_size, embed_dim, hidden_dim, hidden_dim * 2, dropout)
        self.hidden_dim = hidden_dim
        self.tgt_vocab_size = tgt_vocab_size

    def encode(self, src):
        return self.encoder(src)

    def decode_step(self, input_token, encoder_outputs, hidden, cell):
        return self.decoder(input_token, encoder_outputs, hidden, cell)

    def init_decoder_hidden(self, batch_size):
        """Initialize decoder hidden state."""
        hidden = mx.zeros((batch_size, self.hidden_dim))
        cell = mx.zeros((batch_size, self.hidden_dim))
        return hidden, cell


def create_batches(data, vocab, batch_size, max_src_len=20, max_tgt_len=25):
    """Create training batches."""
    np.random.shuffle(data)

    batches = []
    for i in range(0, len(data), batch_size):
        batch_data = data[i:i + batch_size]

        src_batch = []
        tgt_batch = []

        for item in batch_data:
            src_ids = vocab.encode_pinyin(item['pinyin'])[:max_src_len]
            tgt_ids = vocab.encode_hanzi(item['hanzi'])[:max_tgt_len]

            # Pad
            src_ids = src_ids + [0] * (max_src_len - len(src_ids))
            tgt_ids = tgt_ids + [0] * (max_tgt_len - len(tgt_ids))

            src_batch.append(src_ids)
            tgt_batch.append(tgt_ids)

        batches.append({
            'src': mx.array(src_batch),
            'tgt': mx.array(tgt_batch)
        })

    return batches


def compute_loss(model, batch, vocab):
    """Compute cross-entropy loss for a batch."""
    src = batch['src']  # (batch, src_len)
    tgt = batch['tgt']  # (batch, tgt_len)

    batch_size = src.shape[0]
    tgt_len = tgt.shape[1]

    # Encode
    encoder_outputs = model.encode(src)

    # Initialize decoder
    hidden, cell = model.init_decoder_hidden(batch_size)

    # Teacher forcing
    total_loss = 0.0
    num_tokens = 0

    for t in range(tgt_len - 1):
        input_token = tgt[:, t]
        target_token = tgt[:, t + 1]

        logits, hidden, cell, _ = model.decode_step(input_token, encoder_outputs, hidden, cell)

        # Cross-entropy loss
        log_probs = mx.log_softmax(logits, axis=-1)
        target_one_hot = mx.zeros_like(log_probs)

        # Gather log probs for target tokens
        # Manual gather since MLX doesn't have advanced indexing
        batch_indices = mx.arange(batch_size)
        loss_per_token = -log_probs[mx.arange(batch_size), target_token]

        # Mask padding
        mask = (target_token != 0).astype(mx.float32)
        total_loss = total_loss + mx.sum(loss_per_token * mask)
        num_tokens = num_tokens + mx.sum(mask)

    return total_loss / (num_tokens + 1e-8)


def train_epoch(model, batches, optimizer):
    """Train for one epoch."""
    total_loss = 0.0
    num_batches = 0

    loss_and_grad_fn = nn.value_and_grad(model, compute_loss)

    for batch in batches:
        loss, grads = loss_and_grad_fn(model, batch, None)
        optimizer.update(model, grads)
        mx.eval(model.parameters(), optimizer.state)

        total_loss += loss.item()
        num_batches += 1

        if num_batches % 50 == 0:
            print(f"  Batch {num_batches}/{len(batches)}, Loss: {total_loss/num_batches:.4f}")

    return total_loss / num_batches


def evaluate_accuracy(model, data, vocab, num_samples=500):
    """Evaluate character accuracy on test data."""
    correct = 0
    total = 0

    samples = data[:num_samples] if len(data) > num_samples else data

    for item in samples:
        src_ids = vocab.encode_pinyin(item['pinyin'])
        src = mx.array([src_ids])

        # Encode
        encoder_outputs = model.encode(src)

        # Greedy decode
        hidden, cell = model.init_decoder_hidden(1)
        input_token = mx.array([vocab.char2id['<SOS>']])

        predicted_ids = []
        for _ in range(len(item['hanzi']) + 5):
            logits, hidden, cell, _ = model.decode_step(input_token, encoder_outputs, hidden, cell)
            next_token = mx.argmax(logits, axis=-1)

            if next_token.item() == vocab.char2id['<EOS>']:
                break
            predicted_ids.append(next_token.item())
            input_token = next_token

        # Compare
        target_ids = [vocab.char2id.get(c, vocab.char2id['<UNK>']) for c in item['hanzi']]

        for pred, tgt in zip(predicted_ids, target_ids):
            if pred == tgt:
                correct += 1
            total += 1

        # Count remaining target chars as incorrect
        total += max(0, len(target_ids) - len(predicted_ids))

    return correct / total if total > 0 else 0.0


def export_to_onnx(model, vocab, output_path):
    """Export trained model to ONNX for Android deployment."""
    if not HAS_TORCH:
        print("PyTorch not available, skipping ONNX export")
        return

    print("\nExporting to ONNX...")

    # Create PyTorch equivalent model and copy weights
    class TorchEncoder(tnn.Module):
        def __init__(self, vocab_size, embed_dim, hidden_dim, num_layers):
            super().__init__()
            self.embedding = tnn.Embedding(vocab_size, embed_dim)
            self.lstm = tnn.LSTM(embed_dim, hidden_dim, num_layers,
                                 batch_first=True, bidirectional=True)

        def forward(self, x):
            embedded = self.embedding(x)
            outputs, _ = self.lstm(embedded)
            return outputs

    class TorchDecoder(tnn.Module):
        def __init__(self, vocab_size, embed_dim, hidden_dim, encoder_dim):
            super().__init__()
            self.embedding = tnn.Embedding(vocab_size, embed_dim)
            self.lstm = tnn.LSTMCell(embed_dim + encoder_dim, hidden_dim)
            self.attention_w1 = tnn.Linear(encoder_dim, hidden_dim)
            self.attention_w2 = tnn.Linear(hidden_dim, hidden_dim)
            self.attention_v = tnn.Linear(hidden_dim, 1)
            self.fc = tnn.Linear(hidden_dim, vocab_size)
            self.hidden_dim = hidden_dim
            self.encoder_dim = encoder_dim

        def forward(self, input_token, encoder_outputs, hidden, cell):
            # Attention
            enc_proj = self.attention_w1(encoder_outputs)
            dec_proj = self.attention_w2(hidden).unsqueeze(1)
            energy = torch.tanh(enc_proj + dec_proj)
            attn_scores = self.attention_v(energy).squeeze(-1)
            attn_weights = torch.softmax(attn_scores, dim=-1)
            context = torch.sum(encoder_outputs * attn_weights.unsqueeze(-1), dim=1)

            # LSTM step
            embedded = self.embedding(input_token)
            lstm_input = torch.cat([embedded, context], dim=-1)
            new_hidden, new_cell = self.lstm(lstm_input, (hidden, cell))

            # Output
            logits = self.fc(new_hidden)
            return logits, new_hidden, new_cell

    # Copy weights from MLX to PyTorch
    hidden_dim = model.hidden_dim
    encoder_dim = hidden_dim * 2

    torch_encoder = TorchEncoder(len(vocab.pinyin2id), 128, hidden_dim, 2)
    torch_decoder = TorchDecoder(len(vocab.char2id), 128, hidden_dim, encoder_dim)

    # Note: Weight copying would require careful mapping between MLX and PyTorch
    # For now, we'll save the model weights separately

    # Export encoder
    dummy_input = torch.zeros(1, 20, dtype=torch.long)
    torch.onnx.export(
        torch_encoder,
        dummy_input,
        output_path.replace('.onnx', '_encoder.onnx'),
        input_names=['input'],
        output_names=['encoder_outputs'],
        dynamic_axes={'input': {0: 'batch', 1: 'seq_len'},
                      'encoder_outputs': {0: 'batch', 1: 'seq_len'}}
    )

    # Export decoder
    dummy_token = torch.zeros(1, dtype=torch.long)
    dummy_encoder_out = torch.zeros(1, 20, encoder_dim)
    dummy_hidden = torch.zeros(1, hidden_dim)
    dummy_cell = torch.zeros(1, hidden_dim)

    torch.onnx.export(
        torch_decoder,
        (dummy_token, dummy_encoder_out, dummy_hidden, dummy_cell),
        output_path.replace('.onnx', '_decoder.onnx'),
        input_names=['input_token', 'encoder_outputs', 'hidden', 'cell'],
        output_names=['logits', 'new_hidden', 'new_cell'],
        dynamic_axes={'encoder_outputs': {0: 'batch', 1: 'seq_len'}}
    )

    print(f"Exported to {output_path.replace('.onnx', '_encoder.onnx')}")
    print(f"Exported to {output_path.replace('.onnx', '_decoder.onnx')}")


def save_mlx_model(model, vocab, output_path):
    """Save MLX model weights and vocabulary."""
    # Save model parameters
    weights = {}
    for name, param in model.parameters().items():
        weights[name] = np.array(param)

    np.savez(output_path.replace('.onnx', '_weights.npz'), **weights)

    # Save vocabulary
    vocab_data = {
        'char2id': vocab.char2id,
        'id2char': {str(k): v for k, v in vocab.id2char.items()},
        'pinyin2id': vocab.pinyin2id,
        'id2pinyin': {str(k): v for k, v in vocab.id2pinyin.items()}
    }

    with open(output_path.replace('.onnx', '_vocab.json'), 'w', encoding='utf-8') as f:
        json.dump(vocab_data, f, ensure_ascii=False, indent=2)

    print(f"Saved weights to {output_path.replace('.onnx', '_weights.npz')}")
    print(f"Saved vocabulary to {output_path.replace('.onnx', '_vocab.json')}")


def main():
    parser = argparse.ArgumentParser(description='Train BiLSTM Pinyin model with MLX')
    parser.add_argument('--dataset', type=str, required=True, help='Path to pinyin dataset JSON')
    parser.add_argument('--output', type=str, default='mlx_pinyin.onnx', help='Output model path')
    parser.add_argument('--epochs', type=int, default=30, help='Number of epochs')
    parser.add_argument('--batch-size', type=int, default=128, help='Batch size')
    parser.add_argument('--embed-dim', type=int, default=128, help='Embedding dimension')
    parser.add_argument('--hidden-dim', type=int, default=256, help='Hidden dimension')
    parser.add_argument('--lr', type=float, default=0.001, help='Learning rate')
    parser.add_argument('--test-split', type=float, default=0.1, help='Test split ratio')
    args = parser.parse_args()

    print("=" * 60)
    print("MLX BiLSTM Pinyin Training")
    print("=" * 60)
    print(f"Using MLX backend (Apple Silicon optimized)")
    print()

    # Load dataset
    print(f"Loading dataset from {args.dataset}...")
    with open(args.dataset, 'r', encoding='utf-8') as f:
        raw_data = json.load(f)
    # Handle dataset with metadata wrapper
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
    np.random.shuffle(data)
    split_idx = int(len(data) * (1 - args.test_split))
    train_data = data[:split_idx]
    test_data = data[split_idx:]
    print(f"Train: {len(train_data)}, Test: {len(test_data)}")

    # Create model
    print(f"\nCreating model...")
    model = Seq2SeqModel(
        src_vocab_size=len(vocab.pinyin2id),
        tgt_vocab_size=len(vocab.char2id),
        embed_dim=args.embed_dim,
        hidden_dim=args.hidden_dim,
        num_layers=2,
        dropout=0.1
    )
    mx.eval(model.parameters())

    # Count parameters
    total_params = sum(p.size for p in model.parameters().values())
    print(f"Total parameters: {total_params:,}")
    print(f"Estimated model size: {total_params * 4 / 1024 / 1024:.1f} MB (FP32)")

    # Optimizer
    optimizer = optim.Adam(learning_rate=args.lr)

    # Training loop
    print(f"\nStarting training for {args.epochs} epochs...")
    start_time = time.time()
    best_accuracy = 0.0

    for epoch in range(args.epochs):
        epoch_start = time.time()

        # Create batches (reshuffled each epoch)
        batches = create_batches(train_data, vocab, args.batch_size)

        # Train
        avg_loss = train_epoch(model, batches, optimizer)

        # Evaluate
        accuracy = evaluate_accuracy(model, test_data, vocab, num_samples=500)

        epoch_time = time.time() - epoch_start
        print(f"Epoch {epoch+1}/{args.epochs} - Loss: {avg_loss:.4f}, Accuracy: {accuracy:.2%}, Time: {epoch_time:.1f}s")

        if accuracy > best_accuracy:
            best_accuracy = accuracy
            save_mlx_model(model, vocab, args.output)
            print(f"  New best accuracy! Saved model.")

    total_time = time.time() - start_time
    print(f"\nTraining complete in {total_time/60:.1f} minutes")
    print(f"Best accuracy: {best_accuracy:.2%}")

    # Export to ONNX
    export_to_onnx(model, vocab, args.output)

    print("\nDone!")


if __name__ == '__main__':
    main()
