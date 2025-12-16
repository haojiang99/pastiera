#!/usr/bin/env python3
"""
Convert BiLSTM Pinyin model to INT8 ONNX for Android deployment.

This script exports the encoder and decoder separately for efficient inference,
then applies INT8 quantization for smaller model size on mobile devices.

Usage:
    python convert_to_onnx.py --model ../output/bilstm_pinyin_final.pt --output ../output/onnx

Output files:
    - encoder.onnx: Encoder model (processes pinyin input)
    - decoder.onnx: Decoder model (generates hanzi output step by step)
    - encoder_int8.onnx: INT8 quantized encoder
    - decoder_int8.onnx: INT8 quantized decoder
    - vocab_pinyin.json: Pinyin vocabulary
    - vocab_hanzi.json: Hanzi vocabulary
    - config.json: Model configuration for inference
"""

import argparse
import json
import shutil
from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F

try:
    import onnx
    from onnxruntime.quantization import quantize_dynamic, QuantType
    HAS_ONNX = True
except ImportError:
    HAS_ONNX = False
    print("Warning: onnx or onnxruntime not installed.")
    print("Install with: pip install onnx onnxruntime")

# Special tokens
PAD_IDX = 0
SOS_IDX = 1
EOS_IDX = 2
UNK_IDX = 3


class BahdanauAttention(nn.Module):
    """Bahdanau attention mechanism."""

    def __init__(self, encoder_dim: int, decoder_dim: int, attention_dim: int):
        super().__init__()
        self.encoder_att = nn.Linear(encoder_dim, attention_dim)
        self.decoder_att = nn.Linear(decoder_dim, attention_dim)
        self.full_att = nn.Linear(attention_dim, 1)

    def forward(self, encoder_outputs, decoder_hidden, mask=None):
        att1 = self.encoder_att(encoder_outputs)
        att2 = self.decoder_att(decoder_hidden).unsqueeze(1)
        att = torch.tanh(att1 + att2)
        scores = self.full_att(att).squeeze(-1)

        if mask is not None:
            scores = scores.masked_fill(mask == 0, float('-inf'))

        weights = F.softmax(scores, dim=-1)
        context = torch.bmm(weights.unsqueeze(1), encoder_outputs).squeeze(1)
        return context, weights


class Encoder(nn.Module):
    """Bidirectional LSTM encoder."""

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

    def forward(self, x):
        embedded = self.dropout(self.layer_norm(self.embedding(x)))
        outputs, (hidden, cell) = self.lstm(embedded)
        outputs = self.output_norm(outputs)

        batch_size = x.size(0)
        hidden = hidden.view(self.num_layers, 2, batch_size, self.hidden_dim)
        hidden = torch.cat([hidden[:, 0, :, :], hidden[:, 1, :, :]], dim=-1)

        cell = cell.view(self.num_layers, 2, batch_size, self.hidden_dim)
        cell = torch.cat([cell[:, 0, :, :], cell[:, 1, :, :]], dim=-1)

        return outputs, hidden, cell


class Decoder(nn.Module):
    """LSTM decoder with attention."""

    def __init__(self, vocab_size: int, embed_dim: int, hidden_dim: int,
                 encoder_dim: int, attention_dim: int, num_layers: int = 2,
                 dropout: float = 0.3):
        super().__init__()
        self.vocab_size = vocab_size
        self.embedding = nn.Embedding(vocab_size, embed_dim, padding_idx=PAD_IDX)
        self.layer_norm = nn.LayerNorm(embed_dim)
        self.attention = BahdanauAttention(encoder_dim, hidden_dim, attention_dim)
        self.lstm = nn.LSTM(embed_dim + encoder_dim, hidden_dim, num_layers=num_layers,
                           batch_first=True, dropout=dropout if num_layers > 1 else 0)
        self.fc1 = nn.Linear(hidden_dim + encoder_dim, hidden_dim)
        self.fc2 = nn.Linear(hidden_dim, vocab_size)
        self.dropout = nn.Dropout(dropout)

    def forward(self, input_token, encoder_outputs, hidden, cell, mask=None):
        embedded = self.dropout(self.layer_norm(self.embedding(input_token.unsqueeze(1))))
        context, attn_weights = self.attention(encoder_outputs, hidden[-1], mask)
        lstm_input = torch.cat([embedded, context.unsqueeze(1)], dim=-1)
        output, (hidden, cell) = self.lstm(lstm_input, (hidden, cell))
        combined = torch.cat([output.squeeze(1), context], dim=-1)
        combined = self.dropout(F.relu(self.fc1(combined)))
        prediction = self.fc2(combined)
        return prediction, hidden, cell


class EncoderForExport(nn.Module):
    """Encoder wrapper for ONNX export."""

    def __init__(self, encoder):
        super().__init__()
        self.encoder = encoder

    def forward(self, pinyin_ids):
        outputs, hidden, cell = self.encoder(pinyin_ids)
        return outputs, hidden, cell


class DecoderForExport(nn.Module):
    """Decoder wrapper for ONNX export (single step)."""

    def __init__(self, decoder):
        super().__init__()
        self.decoder = decoder

    def forward(self, input_token, encoder_outputs, hidden, cell):
        # No mask for simplicity in ONNX export
        output, new_hidden, new_cell = self.decoder(input_token, encoder_outputs, hidden, cell, mask=None)
        return output, new_hidden, new_cell


def load_model(model_path: str):
    """Load trained model and config."""
    checkpoint = torch.load(model_path, map_location='cpu')
    config = checkpoint.get('config', {})

    # Get model parameters
    embed_dim = config.get('embed_dim', 256)
    hidden_dim = config.get('hidden_dim', 512)
    attention_dim = config.get('attention_dim', 256)
    num_layers = config.get('num_layers', 3)
    dropout = config.get('dropout', 0.3)
    pinyin_vocab_size = config.get('pinyin_vocab_size', 30)
    hanzi_vocab_size = config.get('hanzi_vocab_size', 7000)

    # Create encoder
    encoder = Encoder(
        vocab_size=pinyin_vocab_size,
        embed_dim=embed_dim,
        hidden_dim=hidden_dim,
        num_layers=num_layers,
        dropout=dropout
    )

    # Create decoder
    decoder = Decoder(
        vocab_size=hanzi_vocab_size,
        embed_dim=embed_dim,
        hidden_dim=hidden_dim * 2,
        encoder_dim=hidden_dim * 2,
        attention_dim=attention_dim,
        num_layers=num_layers,
        dropout=dropout
    )

    # Load state dict
    state_dict = checkpoint['model_state_dict']

    # Extract encoder and decoder weights
    encoder_state = {k.replace('encoder.', ''): v for k, v in state_dict.items() if k.startswith('encoder.')}
    decoder_state = {k.replace('decoder.', ''): v for k, v in state_dict.items() if k.startswith('decoder.')}

    encoder.load_state_dict(encoder_state)
    decoder.load_state_dict(decoder_state)

    return encoder, decoder, config


def export_encoder(encoder, output_path: str, pinyin_vocab_size: int):
    """Export encoder to ONNX."""
    encoder.eval()
    encoder_wrapper = EncoderForExport(encoder)

    # Dummy input
    dummy_input = torch.randint(0, pinyin_vocab_size, (1, 50), dtype=torch.long)

    torch.onnx.export(
        encoder_wrapper,
        (dummy_input,),
        output_path,
        input_names=['pinyin_ids'],
        output_names=['encoder_outputs', 'hidden', 'cell'],
        dynamic_axes={
            'pinyin_ids': {0: 'batch', 1: 'seq_len'},
            'encoder_outputs': {0: 'batch', 1: 'seq_len'},
            'hidden': {1: 'batch'},
            'cell': {1: 'batch'}
        },
        opset_version=14,
        do_constant_folding=True
    )
    print(f"Encoder exported to {output_path}")


def export_decoder(decoder, output_path: str, hidden_dim: int, num_layers: int, hanzi_vocab_size: int):
    """Export decoder to ONNX."""
    decoder.eval()
    decoder_wrapper = DecoderForExport(decoder)

    # Dummy inputs
    batch_size = 1
    seq_len = 50
    encoder_dim = hidden_dim * 2

    dummy_token = torch.tensor([SOS_IDX], dtype=torch.long)
    dummy_encoder_outputs = torch.randn(batch_size, seq_len, encoder_dim)
    dummy_hidden = torch.randn(num_layers, batch_size, encoder_dim)
    dummy_cell = torch.randn(num_layers, batch_size, encoder_dim)

    torch.onnx.export(
        decoder_wrapper,
        (dummy_token, dummy_encoder_outputs, dummy_hidden, dummy_cell),
        output_path,
        input_names=['input_token', 'encoder_outputs', 'hidden', 'cell'],
        output_names=['output', 'new_hidden', 'new_cell'],
        dynamic_axes={
            'encoder_outputs': {0: 'batch', 1: 'seq_len'},
            'hidden': {1: 'batch'},
            'cell': {1: 'batch'},
            'new_hidden': {1: 'batch'},
            'new_cell': {1: 'batch'}
        },
        opset_version=14,
        do_constant_folding=True
    )
    print(f"Decoder exported to {output_path}")


def quantize_model(input_path: str, output_path: str):
    """Apply INT8 dynamic quantization."""
    quantize_dynamic(
        input_path,
        output_path,
        weight_type=QuantType.QUInt8
    )
    print(f"Quantized model saved to {output_path}")


def get_model_size(path: str) -> float:
    """Get model file size in MB."""
    return Path(path).stat().st_size / (1024 * 1024)


def main():
    parser = argparse.ArgumentParser(description='Convert BiLSTM model to INT8 ONNX')
    parser.add_argument('--model', type=str, default='../output/bilstm_pinyin_final.pt',
                       help='Path to trained PyTorch model')
    parser.add_argument('--output', type=str, default='../output/onnx',
                       help='Output directory for ONNX models')
    parser.add_argument('--no-quantize', action='store_true',
                       help='Skip INT8 quantization')
    args = parser.parse_args()

    if not HAS_ONNX:
        print("Error: Please install onnx and onnxruntime first:")
        print("  pip install onnx onnxruntime")
        return

    # Create output directory
    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Load model
    print("=" * 60)
    print("Loading PyTorch Model")
    print("=" * 60)
    encoder, decoder, config = load_model(args.model)

    print(f"Model: {args.model}")
    print(f"Pinyin vocab size: {config.get('pinyin_vocab_size', 'unknown')}")
    print(f"Hanzi vocab size: {config.get('hanzi_vocab_size', 'unknown')}")
    print(f"Hidden dim: {config.get('hidden_dim', 512)}")
    print(f"Num layers: {config.get('num_layers', 3)}")
    print(f"Char-level pinyin: {config.get('char_level_pinyin', False)}")

    # Export to ONNX
    print("\n" + "=" * 60)
    print("Exporting to ONNX")
    print("=" * 60)

    encoder_path = str(output_dir / 'encoder.onnx')
    decoder_path = str(output_dir / 'decoder.onnx')

    export_encoder(encoder, encoder_path, config.get('pinyin_vocab_size', 30))
    export_decoder(decoder, decoder_path, config.get('hidden_dim', 512),
                   config.get('num_layers', 3), config.get('hanzi_vocab_size', 7000))

    # Quantize to INT8
    if not args.no_quantize:
        print("\n" + "=" * 60)
        print("Applying INT8 Quantization")
        print("=" * 60)

        encoder_int8_path = str(output_dir / 'encoder_int8.onnx')
        decoder_int8_path = str(output_dir / 'decoder_int8.onnx')

        quantize_model(encoder_path, encoder_int8_path)
        quantize_model(decoder_path, decoder_int8_path)

    # Copy vocabulary files
    print("\n" + "=" * 60)
    print("Copying Vocabulary Files")
    print("=" * 60)

    model_dir = Path(args.model).parent
    pinyin_vocab_path = model_dir / 'pinyin_vocab.json'
    hanzi_vocab_path = model_dir / 'hanzi_vocab.json'

    if pinyin_vocab_path.exists():
        shutil.copy(pinyin_vocab_path, output_dir / 'vocab_pinyin.json')
        print(f"Copied {pinyin_vocab_path}")
    else:
        print(f"Warning: {pinyin_vocab_path} not found")

    if hanzi_vocab_path.exists():
        shutil.copy(hanzi_vocab_path, output_dir / 'vocab_hanzi.json')
        print(f"Copied {hanzi_vocab_path}")
    else:
        print(f"Warning: {hanzi_vocab_path} not found")

    # Save inference config
    inference_config = {
        'pinyin_vocab_size': config.get('pinyin_vocab_size', 30),
        'hanzi_vocab_size': config.get('hanzi_vocab_size', 7000),
        'hidden_dim': config.get('hidden_dim', 512),
        'num_layers': config.get('num_layers', 3),
        'char_level_pinyin': config.get('char_level_pinyin', True),
        'max_output_len': 64,
        'special_tokens': {
            'PAD': PAD_IDX,
            'SOS': SOS_IDX,
            'EOS': EOS_IDX,
            'UNK': UNK_IDX
        }
    }

    config_path = output_dir / 'inference_config.json'
    with open(config_path, 'w', encoding='utf-8') as f:
        json.dump(inference_config, f, indent=2)
    print(f"Saved inference config to {config_path}")

    # Print summary
    print("\n" + "=" * 60)
    print("Conversion Summary")
    print("=" * 60)

    print(f"\nFP32 Models:")
    print(f"  Encoder: {get_model_size(encoder_path):.2f} MB")
    print(f"  Decoder: {get_model_size(decoder_path):.2f} MB")
    print(f"  Total:   {get_model_size(encoder_path) + get_model_size(decoder_path):.2f} MB")

    if not args.no_quantize:
        print(f"\nINT8 Models:")
        print(f"  Encoder: {get_model_size(encoder_int8_path):.2f} MB")
        print(f"  Decoder: {get_model_size(decoder_int8_path):.2f} MB")
        print(f"  Total:   {get_model_size(encoder_int8_path) + get_model_size(decoder_int8_path):.2f} MB")

        reduction = (1 - (get_model_size(encoder_int8_path) + get_model_size(decoder_int8_path)) /
                    (get_model_size(encoder_path) + get_model_size(decoder_path))) * 100
        print(f"\nSize reduction: {reduction:.1f}%")

    print(f"\nOutput files in: {output_dir}")
    print("\nFor Android deployment, use the INT8 models:")
    print("  - encoder_int8.onnx")
    print("  - decoder_int8.onnx")
    print("  - vocab_pinyin.json")
    print("  - vocab_hanzi.json")
    print("  - inference_config.json")


if __name__ == '__main__':
    main()
