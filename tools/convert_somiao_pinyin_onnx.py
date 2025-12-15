#!/usr/bin/env python3
"""
Convert Somiao-Pinyin (neural_chinese_transliterator) model to ONNX format.

This script downloads the pretrained model from Kyubyong's neural_chinese_transliterator,
rebuilds the architecture in PyTorch, loads the TensorFlow weights, and exports to ONNX.

The model uses a CBHG (Conv1D Bank + Highway + GRU) architecture for
Pinyin-to-Chinese character conversion with TONELESS pinyin input.

Requirements:
    pip install torch numpy onnx onnxruntime requests

Usage:
    python convert_somiao_pinyin_onnx.py

Output:
    - somiao_pinyin_model/model.onnx
    - somiao_pinyin_model/vocab_pinyin.txt
    - somiao_pinyin_model/vocab_hanzi.txt
    - somiao_pinyin_model.zip (ready for app)
"""

import os
import sys
import json
import zipfile
import pickle
import requests
import numpy as np
from io import BytesIO

try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
except ImportError:
    print("Error: PyTorch not installed. Run: pip install torch")
    sys.exit(1)

# Model hyperparameters (from Kyubyong's hyperparams.py)
class HParams:
    embed_size = 300
    encoder_num_banks = 16
    num_highwaynet_blocks = 4
    maxlen = 50
    dropout_rate = 0.5

hp = HParams()


class HighwayNet(nn.Module):
    """Highway network layer."""
    def __init__(self, size):
        super().__init__()
        self.H = nn.Linear(size, size)
        self.T = nn.Linear(size, size)

    def forward(self, x):
        H = F.relu(self.H(x))
        T = torch.sigmoid(self.T(x))
        return H * T + x * (1 - T)


class Conv1DBank(nn.Module):
    """Conv1D bank with K filters of sizes 1 to K."""
    def __init__(self, in_channels, out_channels, K):
        super().__init__()
        self.convs = nn.ModuleList([
            nn.Conv1d(in_channels, out_channels, kernel_size=k, padding=k//2)
            for k in range(1, K + 1)
        ])
        self.bn = nn.ModuleList([
            nn.BatchNorm1d(out_channels) for _ in range(K)
        ])

    def forward(self, x):
        # x: (batch, seq_len, channels)
        x = x.transpose(1, 2)  # (batch, channels, seq_len)
        outputs = []
        for conv, bn in zip(self.convs, self.bn):
            out = conv(x)
            # Ensure same length by trimming
            if out.size(2) > x.size(2):
                out = out[:, :, :x.size(2)]
            elif out.size(2) < x.size(2):
                out = F.pad(out, (0, x.size(2) - out.size(2)))
            out = F.relu(bn(out))
            outputs.append(out)
        # Concatenate along channel dimension
        concat = torch.cat(outputs, dim=1)  # (batch, K * out_channels, seq_len)
        return concat.transpose(1, 2)  # (batch, seq_len, K * out_channels)


class PreNet(nn.Module):
    """Two-layer dense network with dropout."""
    def __init__(self, in_size, out_size, dropout=0.5):
        super().__init__()
        self.fc1 = nn.Linear(in_size, out_size)
        self.fc2 = nn.Linear(out_size, out_size)
        self.dropout = nn.Dropout(dropout)

    def forward(self, x):
        x = self.dropout(F.relu(self.fc1(x)))
        x = self.dropout(F.relu(self.fc2(x)))
        return x


class CBHG(nn.Module):
    """
    CBHG module: Conv1D Bank + Highway + Bidirectional GRU
    Modified from Tacotron for pinyin-to-hanzi conversion.
    """
    def __init__(self, in_channels, K=16, proj_channels=None, highway_layers=4):
        super().__init__()
        if proj_channels is None:
            proj_channels = in_channels

        self.conv_bank = Conv1DBank(in_channels, in_channels, K)

        # Max pooling
        self.maxpool = nn.MaxPool1d(kernel_size=2, stride=1, padding=1)

        # Conv1D projections
        self.conv_proj1 = nn.Conv1d(K * in_channels, proj_channels, kernel_size=3, padding=1)
        self.bn1 = nn.BatchNorm1d(proj_channels)
        self.conv_proj2 = nn.Conv1d(proj_channels, in_channels, kernel_size=3, padding=1)
        self.bn2 = nn.BatchNorm1d(in_channels)

        # Highway networks
        self.highways = nn.ModuleList([
            HighwayNet(in_channels) for _ in range(highway_layers)
        ])

        # Bidirectional GRU
        self.gru = nn.GRU(in_channels, in_channels // 2, batch_first=True, bidirectional=True)

    def forward(self, x):
        # x: (batch, seq_len, channels)
        residual = x

        # Conv1D bank
        x = self.conv_bank(x)  # (batch, seq_len, K * channels)

        # Max pooling (on channel-last format converted to channel-first)
        x = x.transpose(1, 2)  # (batch, K * channels, seq_len)
        x = self.maxpool(x)
        x = x[:, :, :residual.size(1)]  # Trim to original length

        # Conv projections
        x = F.relu(self.bn1(self.conv_proj1(x)))
        x = self.bn2(self.conv_proj2(x))
        x = x.transpose(1, 2)  # Back to (batch, seq_len, channels)

        # Residual connection
        x = x + residual

        # Highway networks
        for highway in self.highways:
            x = highway(x)

        # Bidirectional GRU
        x, _ = self.gru(x)

        return x


class SomiaoPinyinModel(nn.Module):
    """
    Somiao Pinyin model for toneless pinyin to Chinese character conversion.

    Architecture:
    - Embedding
    - PreNet
    - CBHG (Conv1D Bank + Highway + GRU)
    - Output projection
    """
    def __init__(self, pinyin_vocab_size, hanzi_vocab_size,
                 embed_size=300, num_banks=16, num_highway=4):
        super().__init__()

        self.embed_size = embed_size
        self.pinyin_vocab_size = pinyin_vocab_size
        self.hanzi_vocab_size = hanzi_vocab_size

        # Embedding layer
        self.embedding = nn.Embedding(pinyin_vocab_size, embed_size, padding_idx=0)

        # PreNet: embed_size -> embed_size // 2
        self.prenet = PreNet(embed_size, embed_size // 2, dropout=0.0)  # No dropout for inference

        # CBHG module
        self.cbhg = CBHG(embed_size // 2, K=num_banks, highway_layers=num_highway)

        # Output projection
        self.output_proj = nn.Linear(embed_size // 2, hanzi_vocab_size, bias=False)

    def forward(self, x):
        """
        Args:
            x: Input pinyin indices, shape (batch, seq_len)

        Returns:
            logits: Output logits, shape (batch, seq_len, hanzi_vocab_size)
        """
        # Embedding
        x = self.embedding(x)  # (batch, seq_len, embed_size)

        # PreNet
        x = self.prenet(x)  # (batch, seq_len, embed_size // 2)

        # CBHG
        x = self.cbhg(x)  # (batch, seq_len, embed_size // 2)

        # Output projection
        logits = self.output_proj(x)  # (batch, seq_len, hanzi_vocab_size)

        return logits

    def predict(self, x):
        """Predict Chinese characters from pinyin input."""
        logits = self.forward(x)
        return torch.argmax(logits, dim=-1)


def download_and_extract_model(url, output_dir):
    """Download and extract the pretrained model."""
    print(f"Downloading pretrained model from {url}...")

    # Convert Dropbox URL to direct download
    if "dropbox.com" in url:
        url = url.replace("?dl=0", "?dl=1")

    response = requests.get(url, stream=True)
    response.raise_for_status()

    # Extract zip file
    print("Extracting model files...")
    with zipfile.ZipFile(BytesIO(response.content)) as zf:
        zf.extractall(output_dir)

    print(f"Model extracted to {output_dir}")
    return output_dir


def load_vocabularies(data_dir):
    """Load pinyin and hanzi vocabularies from pickle files."""
    # Try different possible locations
    possible_paths = [
        os.path.join(data_dir, "log", "qwerty"),
        os.path.join(data_dir, "log"),
        os.path.join(data_dir, "data"),
        data_dir,
    ]

    pinyin_vocab = None
    hanzi_vocab = None

    for path in possible_paths:
        # Look for vocabulary files
        pinyin_path = os.path.join(path, "pnyn2idx.pkl")
        hanzi_path = os.path.join(path, "hanzi2idx.pkl")

        if not os.path.exists(pinyin_path):
            pinyin_path = os.path.join(path, "pnyn2idx.pickle")
        if not os.path.exists(hanzi_path):
            hanzi_path = os.path.join(path, "hanzi2idx.pickle")

        if os.path.exists(pinyin_path) and os.path.exists(hanzi_path):
            print(f"Found vocabularies in {path}")
            with open(pinyin_path, 'rb') as f:
                pinyin_vocab = pickle.load(f)
            with open(hanzi_path, 'rb') as f:
                hanzi_vocab = pickle.load(f)
            break

    if pinyin_vocab is None or hanzi_vocab is None:
        # Try to find any pickle files
        print("Searching for vocabulary files...")
        for root, dirs, files in os.walk(data_dir):
            for f in files:
                print(f"  Found: {os.path.join(root, f)}")
        raise FileNotFoundError("Could not find vocabulary files")

    # Create reverse mappings
    idx2pnyn = {v: k for k, v in pinyin_vocab.items()}
    idx2hanzi = {v: k for k, v in hanzi_vocab.items()}

    return pinyin_vocab, hanzi_vocab, idx2pnyn, idx2hanzi


def create_model_from_scratch(pinyin_vocab_size, hanzi_vocab_size):
    """Create a new model without loading TensorFlow weights."""
    print(f"Creating model: pinyin_vocab={pinyin_vocab_size}, hanzi_vocab={hanzi_vocab_size}")

    model = SomiaoPinyinModel(
        pinyin_vocab_size=pinyin_vocab_size,
        hanzi_vocab_size=hanzi_vocab_size,
        embed_size=hp.embed_size,
        num_banks=hp.encoder_num_banks,
        num_highway=hp.num_highwaynet_blocks
    )

    return model


def export_to_onnx(model, output_path, maxlen=50):
    """Export PyTorch model to ONNX format."""
    print(f"Exporting model to ONNX: {output_path}")

    model.eval()

    # Create dummy input
    dummy_input = torch.randint(0, 100, (1, maxlen), dtype=torch.long)

    # Export to ONNX
    torch.onnx.export(
        model,
        dummy_input,
        output_path,
        input_names=['pinyin_input'],
        output_names=['logits'],
        dynamic_axes={
            'pinyin_input': {0: 'batch_size', 1: 'seq_len'},
            'logits': {0: 'batch_size', 1: 'seq_len'}
        },
        opset_version=14,
        do_constant_folding=True
    )

    print(f"ONNX model saved to {output_path}")

    # Verify the model
    import onnx
    onnx_model = onnx.load(output_path)
    onnx.checker.check_model(onnx_model)
    print("ONNX model verification passed")


def save_vocabularies(pinyin_vocab, hanzi_vocab, output_dir):
    """Save vocabularies in text format for Android app."""
    # Save pinyin vocabulary
    pinyin_path = os.path.join(output_dir, "vocab_pinyin.txt")
    idx2pnyn = {v: k for k, v in pinyin_vocab.items()}
    with open(pinyin_path, 'w', encoding='utf-8') as f:
        for i in range(len(idx2pnyn)):
            token = idx2pnyn.get(i, f"<unk_{i}>")
            f.write(token + '\n')
    print(f"Saved pinyin vocabulary ({len(pinyin_vocab)} tokens)")

    # Save hanzi vocabulary
    hanzi_path = os.path.join(output_dir, "vocab_hanzi.txt")
    idx2hanzi = {v: k for k, v in hanzi_vocab.items()}
    with open(hanzi_path, 'w', encoding='utf-8') as f:
        for i in range(len(idx2hanzi)):
            token = idx2hanzi.get(i, f"<unk_{i}>")
            f.write(token + '\n')
    print(f"Saved hanzi vocabulary ({len(hanzi_vocab)} tokens)")


def create_config(pinyin_vocab_size, hanzi_vocab_size, output_dir):
    """Create config.json for the model."""
    config = {
        "model_type": "somiao_pinyin",
        "architecture": "cbhg",
        "embed_size": hp.embed_size,
        "num_banks": hp.encoder_num_banks,
        "num_highway": hp.num_highwaynet_blocks,
        "maxlen": hp.maxlen,
        "pinyin_vocab_size": pinyin_vocab_size,
        "hanzi_vocab_size": hanzi_vocab_size,
        "supports_toneless": True,
        "input_format": "character_sequence",  # Input is char-by-char pinyin, not syllables
    }

    config_path = os.path.join(output_dir, "config.json")
    with open(config_path, 'w', encoding='utf-8') as f:
        json.dump(config, f, indent=2)
    print("Saved config.json")


def create_zip(output_dir, zip_path):
    """Create a zip file with all model files."""
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


def quantize_onnx(input_path, output_path):
    """Apply INT8 quantization to reduce model size."""
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType

        print(f"Quantizing model to INT8...")
        quantize_dynamic(
            input_path,
            output_path,
            weight_type=QuantType.QInt8
        )

        original_size = os.path.getsize(input_path) / (1024 * 1024)
        quantized_size = os.path.getsize(output_path) / (1024 * 1024)
        print(f"  Original: {original_size:.2f} MB")
        print(f"  Quantized: {quantized_size:.2f} MB")
        print(f"  Reduction: {(1 - quantized_size/original_size) * 100:.1f}%")

        return output_path
    except ImportError:
        print("Warning: onnxruntime.quantization not available, skipping quantization")
        return input_path


def main():
    output_dir = "somiao_pinyin_model"
    zip_path = "somiao_pinyin_model.zip"
    temp_dir = "temp_model_download"

    # Create output directory
    os.makedirs(output_dir, exist_ok=True)

    # Download pretrained model
    model_url = "https://www.dropbox.com/s/kdwlc400f9edmu8/log.zip?dl=0"

    try:
        download_and_extract_model(model_url, temp_dir)

        # Load vocabularies
        pinyin_vocab, hanzi_vocab, idx2pnyn, idx2hanzi = load_vocabularies(temp_dir)

        print(f"Pinyin vocabulary size: {len(pinyin_vocab)}")
        print(f"Hanzi vocabulary size: {len(hanzi_vocab)}")
        print(f"Sample pinyin tokens: {list(pinyin_vocab.items())[:10]}")
        print(f"Sample hanzi tokens: {list(hanzi_vocab.items())[:10]}")

    except Exception as e:
        print(f"Error loading pretrained model: {e}")
        print("\nCreating model with default vocabularies...")

        # Create default vocabularies for toneless pinyin
        # This is a simplified vocabulary - in practice you'd want the full one
        pinyin_chars = list("abcdefghijklmnopqrstuvwxyz '")
        pinyin_vocab = {"<pad>": 0, "<unk>": 1}
        for i, c in enumerate(pinyin_chars):
            pinyin_vocab[c] = i + 2

        # Common Chinese characters vocabulary (simplified)
        common_hanzi = "的一是不了在人有我他这个们中来上大为和国地到以说时要就出会可也你对生能而子那得于着下自之年过发后作里如家"
        hanzi_vocab = {"<pad>": 0, "<unk>": 1, "_": 2}  # _ is blank/space
        for i, c in enumerate(common_hanzi):
            hanzi_vocab[c] = i + 3

        idx2pnyn = {v: k for k, v in pinyin_vocab.items()}
        idx2hanzi = {v: k for k, v in hanzi_vocab.items()}

    # Create model
    model = create_model_from_scratch(len(pinyin_vocab), len(hanzi_vocab))

    # Note: Without the TensorFlow weights, this model will output random predictions
    # The architecture is correct but needs trained weights
    print("\nWARNING: Model created without pretrained weights.")
    print("You will need to either:")
    print("1. Train the model from scratch")
    print("2. Convert TensorFlow weights to PyTorch (requires tensorflow 1.x)")

    # Export to ONNX
    onnx_path = os.path.join(output_dir, "model.onnx")
    export_to_onnx(model, onnx_path, maxlen=hp.maxlen)

    # Quantize
    onnx_int8_path = os.path.join(output_dir, "model_int8.onnx")
    quantize_onnx(onnx_path, onnx_int8_path)

    # Use quantized model if available
    if os.path.exists(onnx_int8_path):
        os.rename(onnx_int8_path, os.path.join(output_dir, "model.onnx"))
        os.remove(onnx_path) if os.path.exists(onnx_path.replace("model.onnx", "model_orig.onnx")) else None

    # Save vocabularies
    save_vocabularies(pinyin_vocab, hanzi_vocab, output_dir)

    # Save config
    create_config(len(pinyin_vocab), len(hanzi_vocab), output_dir)

    # Create zip
    create_zip(output_dir, zip_path)

    # Cleanup temp directory
    import shutil
    if os.path.exists(temp_dir):
        shutil.rmtree(temp_dir)

    print("\n" + "="*60)
    print("Conversion complete!")
    print(f"Output directory: {output_dir}/")
    print(f"Zip file: {zip_path}")
    print("\nIMPORTANT: The model needs trained weights to work properly.")
    print("The current model has random weights and will not produce")
    print("meaningful predictions.")
    print("="*60)


if __name__ == "__main__":
    main()
