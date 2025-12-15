#!/usr/bin/env python3
"""
Convert Duyu/Pinyin2Hanzi-Transformer model to ONNX format with INT8 quantization.

This script downloads the pretrained model from HuggingFace, converts it to ONNX,
and applies INT8 quantization to reduce the model size for mobile deployment.

Requirements:
    pip install torch onnx onnxruntime huggingface_hub numpy

Usage:
    python convert_pinyin2hanzi_onnx.py

Output:
    - neural_pinyin_model/model.onnx (full precision)
    - neural_pinyin_model/model_int8.onnx (INT8 quantized)
    - neural_pinyin_model/vocab_pinyin.txt
    - neural_pinyin_model/vocab_hanzi.txt
    - neural_pinyin_model.zip (ready for app)
"""

import os
import sys
import json
import zipfile
import numpy as np

try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
except ImportError:
    print("Error: PyTorch not installed. Run: pip install torch")
    sys.exit(1)

try:
    from huggingface_hub import hf_hub_download
except ImportError:
    print("Error: huggingface_hub not installed. Run: pip install huggingface_hub")
    sys.exit(1)

# Model architecture (must match the original)
class PositionalEncoding(nn.Module):
    def __init__(self, d_model, max_len=512):
        super().__init__()
        # Original model uses shape (max_len, 1, d_model) for transformer (seq_first) format
        pe = torch.zeros(max_len, 1, d_model)
        position = torch.arange(0, max_len, dtype=torch.float).unsqueeze(1)
        div_term = torch.exp(torch.arange(0, d_model, 2).float() * (-np.log(10000.0) / d_model))
        pe[:, 0, 0::2] = torch.sin(position * div_term)
        pe[:, 0, 1::2] = torch.cos(position * div_term)
        self.register_buffer('pe', pe)

    def forward(self, x):
        # x: (seq_len, batch_size, d_model) - transformer format
        return x + self.pe[:x.size(0), :, :]


class TransformerModel(nn.Module):
    def __init__(self, pinyin_vocab_size, hanzi_vocab_size, d_model=512, nhead=16,
                 num_encoder_layers=8, num_decoder_layers=6, dim_feedforward=1024, dropout=0.07):
        super().__init__()
        self.d_model = d_model
        self.pinyin_embedding = nn.Embedding(pinyin_vocab_size, d_model, padding_idx=0)
        self.hanzi_embedding = nn.Embedding(hanzi_vocab_size, d_model, padding_idx=0)
        self.positional_encoding = PositionalEncoding(d_model)
        self.transformer = nn.Transformer(
            d_model=d_model,
            nhead=nhead,
            num_encoder_layers=num_encoder_layers,
            num_decoder_layers=num_decoder_layers,
            dim_feedforward=dim_feedforward,
            dropout=dropout,
            batch_first=False  # Transformer expects (seq_len, batch, d_model)
        )
        self.fc_out = nn.Linear(d_model, hanzi_vocab_size)

    def forward(self, pinyin, hanzi_input):
        # pinyin: (batch_size, src_seq_len)
        # hanzi_input: (batch_size, tgt_seq_len)

        # Create embeddings with scaling
        pinyin_embedded = self.pinyin_embedding(pinyin) * np.sqrt(self.d_model)
        hanzi_embedded = self.hanzi_embedding(hanzi_input) * np.sqrt(self.d_model)

        # Transpose for transformer FIRST (seq_len, batch_size, d_model)
        pinyin_embedded = pinyin_embedded.permute(1, 0, 2)
        hanzi_embedded = hanzi_embedded.permute(1, 0, 2)

        # Add positional encoding (expects seq_first format)
        pinyin_embedded = self.positional_encoding(pinyin_embedded)
        hanzi_embedded = self.positional_encoding(hanzi_embedded)

        # Create masks
        tgt_seq_len = hanzi_embedded.size(0)
        tgt_mask = self.transformer.generate_square_subsequent_mask(tgt_seq_len).to(pinyin.device)

        # Create padding masks
        src_key_padding_mask = (pinyin == 0)  # (batch_size, src_seq_len)
        tgt_key_padding_mask = (hanzi_input == 0)  # (batch_size, tgt_seq_len)

        # Forward pass
        output = self.transformer(
            src=pinyin_embedded,
            tgt=hanzi_embedded,
            tgt_mask=tgt_mask,
            src_key_padding_mask=src_key_padding_mask,
            tgt_key_padding_mask=tgt_key_padding_mask
        )

        # Output projection
        output = output.permute(1, 0, 2)  # (batch_size, tgt_seq_len, d_model)
        output = self.fc_out(output)  # (batch_size, tgt_seq_len, hanzi_vocab_size)

        return output


class EncoderWrapper(nn.Module):
    """Wrapper to export just the encoder part for ONNX."""
    def __init__(self, model):
        super().__init__()
        self.d_model = model.d_model
        self.pinyin_embedding = model.pinyin_embedding
        self.positional_encoding = model.positional_encoding
        self.encoder = model.transformer.encoder

    def forward(self, pinyin):
        # pinyin: (batch_size, src_seq_len)
        pinyin_embedded = self.pinyin_embedding(pinyin) * np.sqrt(self.d_model)
        pinyin_embedded = pinyin_embedded.permute(1, 0, 2)  # (seq_len, batch, d_model)
        pinyin_embedded = self.positional_encoding(pinyin_embedded)

        src_key_padding_mask = (pinyin == 0)
        memory = self.encoder(pinyin_embedded, src_key_padding_mask=src_key_padding_mask)

        return memory.permute(1, 0, 2)  # (batch, seq_len, d_model)


class DecoderWrapper(nn.Module):
    """Wrapper to export just the decoder part for ONNX."""
    def __init__(self, model):
        super().__init__()
        self.d_model = model.d_model
        self.hanzi_embedding = model.hanzi_embedding
        self.positional_encoding = model.positional_encoding
        self.decoder = model.transformer.decoder
        self.fc_out = model.fc_out

    def forward(self, hanzi_input, memory, src_key_padding_mask):
        # hanzi_input: (batch_size, tgt_seq_len)
        # memory: (batch_size, src_seq_len, d_model)
        # src_key_padding_mask: (batch_size, src_seq_len)

        hanzi_embedded = self.hanzi_embedding(hanzi_input) * np.sqrt(self.d_model)
        hanzi_embedded = hanzi_embedded.permute(1, 0, 2)  # (seq_len, batch, d_model)
        hanzi_embedded = self.positional_encoding(hanzi_embedded)

        tgt_seq_len = hanzi_embedded.size(0)
        tgt_mask = torch.triu(torch.ones(tgt_seq_len, tgt_seq_len) * float('-inf'), diagonal=1)
        tgt_mask = tgt_mask.to(hanzi_input.device)

        tgt_key_padding_mask = (hanzi_input == 0)
        memory_t = memory.permute(1, 0, 2)  # (src_seq_len, batch, d_model)

        output = self.decoder(
            hanzi_embedded,
            memory_t,
            tgt_mask=tgt_mask,
            tgt_key_padding_mask=tgt_key_padding_mask,
            memory_key_padding_mask=src_key_padding_mask
        )

        output = output.permute(1, 0, 2)  # (batch, seq_len, d_model)
        output = self.fc_out(output)  # (batch, seq_len, vocab_size)

        return output


def download_model():
    """Download the pretrained model from HuggingFace."""
    print("Downloading model from HuggingFace...")
    model_path = hf_hub_download(
        repo_id="Duyu/Pinyin2Hanzi-Transformer",
        filename="pinyin2hanzi_transformer.pth",
        cache_dir="./cache"
    )
    print(f"Model downloaded to: {model_path}")
    return model_path


def load_model(model_path):
    """Load the PyTorch model."""
    print("Loading PyTorch model...")
    checkpoint = torch.load(model_path, map_location='cpu')

    # Extract vocabularies
    pinyin_vocab = checkpoint['pinyin_vocab']
    hanzi_vocab = checkpoint['hanzi_vocab']
    config = checkpoint.get('config', {})

    print(f"Pinyin vocabulary size: {len(pinyin_vocab)}")
    print(f"Hanzi vocabulary size: {len(hanzi_vocab)}")
    print(f"Model config: {config}")

    # Create model with correct architecture
    model = TransformerModel(
        pinyin_vocab_size=len(pinyin_vocab),
        hanzi_vocab_size=len(hanzi_vocab),
        d_model=config.get('d_model', 512),
        nhead=config.get('nhead', 16),
        num_encoder_layers=config.get('num_encoder_layers', 8),
        num_decoder_layers=config.get('num_decoder_layers', 6),
        dim_feedforward=config.get('dim_feedforward', 1024),
        dropout=config.get('dropout', 0.07)
    )

    # Load weights
    model.load_state_dict(checkpoint['model_state_dict'])
    model.eval()

    return model, checkpoint


def export_to_onnx(model, checkpoint, output_dir):
    """Export model to ONNX format (encoder and decoder separately)."""
    print("Exporting to ONNX format...")

    os.makedirs(output_dir, exist_ok=True)

    # Create wrapper models
    encoder = EncoderWrapper(model)
    decoder = DecoderWrapper(model)
    encoder.eval()
    decoder.eval()

    # Use fixed sequence lengths for export (will still work with shorter sequences)
    batch_size = 1
    src_seq_len = 30   # Fixed length for export
    tgt_seq_len = 20   # Fixed length for export
    d_model = model.d_model

    dummy_pinyin = torch.randint(0, 100, (batch_size, src_seq_len))
    dummy_hanzi = torch.randint(0, 100, (batch_size, tgt_seq_len))
    dummy_memory = torch.randn(batch_size, src_seq_len, d_model)
    dummy_src_mask = torch.zeros(batch_size, src_seq_len, dtype=torch.bool)

    # Export encoder using TorchScript-based exporter (more stable)
    encoder_path = os.path.join(output_dir, "encoder.onnx")
    print(f"Exporting encoder to {encoder_path}...")
    with torch.no_grad():
        torch.onnx.export(
            encoder,
            (dummy_pinyin,),
            encoder_path,
            input_names=['pinyin'],
            output_names=['memory'],
            opset_version=17,
            do_constant_folding=True,
            dynamo=False  # Use legacy TorchScript exporter
        )

    # Export decoder using TorchScript-based exporter
    decoder_path = os.path.join(output_dir, "decoder.onnx")
    print(f"Exporting decoder to {decoder_path}...")
    with torch.no_grad():
        torch.onnx.export(
            decoder,
            (dummy_hanzi, dummy_memory, dummy_src_mask),
            decoder_path,
            input_names=['hanzi_input', 'memory', 'src_key_padding_mask'],
            output_names=['logits'],
            opset_version=17,
            do_constant_folding=True,
            dynamo=False  # Use legacy TorchScript exporter
        )

    # Save vocabularies
    pinyin_vocab = checkpoint['pinyin_vocab']
    hanzi_vocab = checkpoint['hanzi_vocab']

    pinyin_vocab_path = os.path.join(output_dir, "vocab_pinyin.txt")
    with open(pinyin_vocab_path, 'w', encoding='utf-8') as f:
        for token in pinyin_vocab:
            f.write(token + '\n')
    print(f"Saved pinyin vocabulary ({len(pinyin_vocab)} tokens)")

    hanzi_vocab_path = os.path.join(output_dir, "vocab_hanzi.txt")
    with open(hanzi_vocab_path, 'w', encoding='utf-8') as f:
        for token in hanzi_vocab:
            f.write(token + '\n')
    print(f"Saved hanzi vocabulary ({len(hanzi_vocab)} tokens)")

    # Save config
    config = checkpoint.get('config', {})
    config['max_length'] = checkpoint.get('max_length', 14)
    config_path = os.path.join(output_dir, "config.json")
    with open(config_path, 'w', encoding='utf-8') as f:
        json.dump(config, f, indent=2)
    print(f"Saved config")

    return encoder_path, decoder_path


def quantize_onnx(input_path, output_path):
    """Quantize ONNX model to INT8."""
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
    except ImportError:
        print("Warning: onnxruntime.quantization not available, skipping quantization")
        return input_path

    print(f"Quantizing {input_path} to INT8...")
    quantize_dynamic(
        input_path,
        output_path,
        weight_type=QuantType.QInt8
    )

    # Compare sizes
    original_size = os.path.getsize(input_path) / (1024 * 1024)
    quantized_size = os.path.getsize(output_path) / (1024 * 1024)
    print(f"  Original: {original_size:.2f} MB")
    print(f"  Quantized: {quantized_size:.2f} MB")
    print(f"  Reduction: {(1 - quantized_size/original_size) * 100:.1f}%")

    return output_path


def create_zip(output_dir, zip_path):
    """Create a zip file with all model files."""
    print(f"Creating {zip_path}...")

    # Files to include (prioritize INT8 quantized models)
    required_files = ['vocab_pinyin.txt', 'vocab_hanzi.txt', 'config.json']

    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
        # Add INT8 quantized models (renamed without _int8 suffix)
        for model_type in ['encoder', 'decoder']:
            int8_file = f"{model_type}_int8.onnx"
            int8_path = os.path.join(output_dir, int8_file)
            if os.path.exists(int8_path):
                arcname = f"{model_type}.onnx"
                zf.write(int8_path, arcname)
                size_mb = os.path.getsize(int8_path) / (1024 * 1024)
                print(f"  Added: {int8_file} -> {arcname} ({size_mb:.2f} MB)")

        # Add vocabulary and config files
        for filename in required_files:
            filepath = os.path.join(output_dir, filename)
            if os.path.exists(filepath):
                zf.write(filepath, filename)
                size_kb = os.path.getsize(filepath) / 1024
                print(f"  Added: {filename} ({size_kb:.1f} KB)")

    zip_size = os.path.getsize(zip_path) / (1024 * 1024)
    print(f"Final zip size: {zip_size:.2f} MB")


def main():
    output_dir = "neural_pinyin_model"
    zip_path = "neural_pinyin_model.zip"

    # Step 1: Download model
    model_path = download_model()

    # Step 2: Load model
    model, checkpoint = load_model(model_path)

    # Step 3: Export to ONNX
    encoder_path, decoder_path = export_to_onnx(model, checkpoint, output_dir)

    # Step 4: Quantize to INT8
    encoder_int8_path = encoder_path.replace('.onnx', '_int8.onnx')
    decoder_int8_path = decoder_path.replace('.onnx', '_int8.onnx')

    quantize_onnx(encoder_path, encoder_int8_path)
    quantize_onnx(decoder_path, decoder_int8_path)

    # Step 5: Create zip file
    create_zip(output_dir, zip_path)

    print("\n" + "="*60)
    print("Conversion complete!")
    print(f"Output directory: {output_dir}/")
    print(f"Zip file: {zip_path}")
    print("\nTo use in the app:")
    print("1. Copy neural_pinyin_model.zip to your device")
    print("2. In Coolwulf Settings -> Text Input -> Neural Pinyin")
    print("3. Enable Neural Pinyin and select the zip file")
    print("4. Tap 'Extract' to extract the model")
    print("="*60)


if __name__ == "__main__":
    main()
