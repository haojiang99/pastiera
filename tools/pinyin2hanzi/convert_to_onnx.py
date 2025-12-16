"""
Convert Pinyin2Hanzi-Transformer PyTorch model to ONNX format for Android deployment.
This script exports the encoder and decoder separately for efficient inference.
"""

import torch
import torch.nn as nn
import numpy as np
import json
import os

# Import model classes from run.py
# We need to define them here since run.py has some execution code

class PositionalEncoding(nn.Module):
    def __init__(self, d_model, dropout=0.1, max_len=512):
        super(PositionalEncoding, self).__init__()
        self.dropout = nn.Dropout(p=dropout)

        position = torch.arange(max_len).unsqueeze(1)
        div_term = torch.exp(torch.arange(0, d_model, 2) * (-np.log(10000.0) / d_model))
        pe = torch.zeros(max_len, 1, d_model)
        pe[:, 0, 0::2] = torch.sin(position * div_term)
        pe[:, 0, 1::2] = torch.cos(position * div_term)
        self.register_buffer('pe', pe)

    def forward(self, x):
        x = x + self.pe[:x.size(0)]
        return self.dropout(x)


class TransformerModel(nn.Module):
    def __init__(self, pinyin_vocab_size, hanzi_vocab_size, d_model=256, nhead=8, num_encoder_layers=6,
                 num_decoder_layers=6, dim_feedforward=1024, dropout=0.075):
        super(TransformerModel, self).__init__()

        self.d_model = d_model

        # Pinyin embedding layer
        self.pinyin_embedding = nn.Embedding(pinyin_vocab_size, d_model)
        # Hanzi embedding layer
        self.hanzi_embedding = nn.Embedding(hanzi_vocab_size, d_model)

        # Positional encoding
        self.positional_encoding = PositionalEncoding(d_model, dropout)

        # Transformer model
        self.transformer = nn.Transformer(
            d_model=d_model,
            nhead=nhead,
            num_encoder_layers=num_encoder_layers,
            num_decoder_layers=num_decoder_layers,
            dim_feedforward=dim_feedforward,
            dropout=dropout
        )

        # Output layer
        self.fc_out = nn.Linear(d_model, hanzi_vocab_size)

    def forward(self, pinyin, hanzi_input):
        # Embedding layer
        pinyin_embedded = self.pinyin_embedding(pinyin) * np.sqrt(self.d_model)
        hanzi_embedded = self.hanzi_embedding(hanzi_input) * np.sqrt(self.d_model)

        # Positional encoding
        pinyin_embedded = self.positional_encoding(pinyin_embedded)
        hanzi_embedded = self.positional_encoding(hanzi_embedded)

        # Adjust dimension order: (seq_len, batch_size, d_model)
        pinyin_embedded = pinyin_embedded.permute(1, 0, 2)
        hanzi_embedded = hanzi_embedded.permute(1, 0, 2)

        # Create masks
        src_mask = self._generate_square_subsequent_mask(pinyin_embedded.size(0))
        tgt_mask = self._generate_square_subsequent_mask(hanzi_embedded.size(0))

        # Transformer forward pass
        output = self.transformer(
            src=pinyin_embedded,
            tgt=hanzi_embedded,
            src_key_padding_mask=self._create_padding_mask(pinyin),
            tgt_key_padding_mask=self._create_padding_mask(hanzi_input),
            memory_key_padding_mask=self._create_padding_mask(pinyin),
            src_mask=src_mask,
            tgt_mask=tgt_mask
        )

        # Output layer
        output = output.permute(1, 0, 2)
        output = self.fc_out(output)

        return output

    def _generate_square_subsequent_mask(self, sz):
        return torch.triu(torch.full((sz, sz), float('-inf')), diagonal=1)

    def _create_padding_mask(self, seq):
        return seq == 0  # Assuming <pad> id is 0


class TransformerEncoder(nn.Module):
    """Encoder-only model for ONNX export"""
    def __init__(self, original_model):
        super(TransformerEncoder, self).__init__()
        self.d_model = original_model.d_model
        self.pinyin_embedding = original_model.pinyin_embedding
        self.positional_encoding = original_model.positional_encoding
        self.encoder = original_model.transformer.encoder

    def forward(self, pinyin):
        # Embedding
        pinyin_embedded = self.pinyin_embedding(pinyin) * np.sqrt(self.d_model)
        pinyin_embedded = self.positional_encoding(pinyin_embedded)

        # Adjust dimension: (seq_len, batch_size, d_model)
        pinyin_embedded = pinyin_embedded.permute(1, 0, 2)

        # Create padding mask
        src_key_padding_mask = (pinyin == 0)

        # Encode
        memory = self.encoder(pinyin_embedded, src_key_padding_mask=src_key_padding_mask)

        return memory


class TransformerDecoder(nn.Module):
    """Decoder-only model for ONNX export with memory input"""
    def __init__(self, original_model):
        super(TransformerDecoder, self).__init__()
        self.d_model = original_model.d_model
        self.hanzi_embedding = original_model.hanzi_embedding
        self.positional_encoding = original_model.positional_encoding
        self.decoder = original_model.transformer.decoder
        self.fc_out = original_model.fc_out

    def forward(self, hanzi_input, memory, pinyin_padding_mask):
        # Embedding
        hanzi_embedded = self.hanzi_embedding(hanzi_input) * np.sqrt(self.d_model)
        hanzi_embedded = self.positional_encoding(hanzi_embedded)

        # Adjust dimension: (seq_len, batch_size, d_model)
        hanzi_embedded = hanzi_embedded.permute(1, 0, 2)

        # Create causal mask for decoder
        tgt_len = hanzi_embedded.size(0)
        tgt_mask = torch.triu(torch.full((tgt_len, tgt_len), float('-inf')), diagonal=1)

        # Create padding mask
        tgt_key_padding_mask = (hanzi_input == 0)

        # Decode
        output = self.decoder(
            hanzi_embedded,
            memory,
            tgt_mask=tgt_mask,
            tgt_key_padding_mask=tgt_key_padding_mask,
            memory_key_padding_mask=pinyin_padding_mask
        )

        # Output layer
        output = output.permute(1, 0, 2)
        output = self.fc_out(output)

        return output


class CombinedTransformer(nn.Module):
    """Combined model for simpler ONNX export - single model approach"""
    def __init__(self, original_model):
        super(CombinedTransformer, self).__init__()
        self.d_model = original_model.d_model
        self.pinyin_embedding = original_model.pinyin_embedding
        self.hanzi_embedding = original_model.hanzi_embedding
        self.positional_encoding = original_model.positional_encoding
        self.transformer = original_model.transformer
        self.fc_out = original_model.fc_out

    def forward(self, pinyin, hanzi_input):
        # Embedding
        pinyin_embedded = self.pinyin_embedding(pinyin) * np.sqrt(self.d_model)
        hanzi_embedded = self.hanzi_embedding(hanzi_input) * np.sqrt(self.d_model)

        # Positional encoding
        pinyin_embedded = self.positional_encoding(pinyin_embedded)
        hanzi_embedded = self.positional_encoding(hanzi_embedded)

        # Adjust dimension: (seq_len, batch_size, d_model)
        pinyin_embedded = pinyin_embedded.permute(1, 0, 2)
        hanzi_embedded = hanzi_embedded.permute(1, 0, 2)

        # Create masks - MATCHING THE ORIGINAL MODEL EXACTLY
        src_len = pinyin_embedded.size(0)
        tgt_len = hanzi_embedded.size(0)

        # Original model uses causal mask for BOTH src and tgt
        src_mask = torch.triu(torch.full((src_len, src_len), float('-inf')), diagonal=1)
        tgt_mask = torch.triu(torch.full((tgt_len, tgt_len), float('-inf')), diagonal=1)

        # Padding masks
        src_key_padding_mask = (pinyin == 0)
        tgt_key_padding_mask = (hanzi_input == 0)

        # Transformer forward - MATCHING ORIGINAL MODEL
        output = self.transformer(
            src=pinyin_embedded,
            tgt=hanzi_embedded,
            src_mask=src_mask,
            tgt_mask=tgt_mask,
            src_key_padding_mask=src_key_padding_mask,
            tgt_key_padding_mask=tgt_key_padding_mask,
            memory_key_padding_mask=src_key_padding_mask
        )

        # Output layer
        output = output.permute(1, 0, 2)
        output = self.fc_out(output)

        return output


def load_model(filepath, device='cpu'):
    """Load PyTorch model from file"""
    save_data = torch.load(filepath, map_location=device)

    # Get vocabularies
    hanzi_vocab = save_data['hanzi_vocab']
    pinyin_vocab = save_data['pinyin_vocab']
    hanzi2idx = save_data['hanzi2idx']
    pinyin2idx = save_data['pinyin2idx']
    idx2hanzi = save_data['idx2hanzi']
    idx2pinyin = save_data['idx2pinyin']
    max_length = save_data['max_length']
    config = save_data['config']

    # Initialize model
    model = TransformerModel(
        pinyin_vocab_size=len(pinyin_vocab),
        hanzi_vocab_size=len(hanzi_vocab),
        **config
    )
    model.load_state_dict(save_data['model_state_dict'])
    model.eval()

    return model, {
        'hanzi_vocab': hanzi_vocab,
        'pinyin_vocab': pinyin_vocab,
        'hanzi2idx': hanzi2idx,
        'pinyin2idx': pinyin2idx,
        'idx2hanzi': idx2hanzi,
        'idx2pinyin': idx2pinyin,
        'max_length': max_length,
        'config': config
    }


def export_vocabularies(vocab_data, output_dir):
    """Export vocabulary files for Android app"""
    os.makedirs(output_dir, exist_ok=True)

    # Export pinyin vocabulary (one per line)
    with open(os.path.join(output_dir, 'pinyin_vocab.txt'), 'w', encoding='utf-8') as f:
        for token in vocab_data['pinyin_vocab']:
            f.write(token + '\n')

    # Export hanzi vocabulary (one per line)
    with open(os.path.join(output_dir, 'hanzi_vocab.txt'), 'w', encoding='utf-8') as f:
        for token in vocab_data['hanzi_vocab']:
            f.write(token + '\n')

    # Export config
    config_data = {
        'max_length': vocab_data['max_length'],
        'model_config': vocab_data['config'],
        'pinyin_vocab_size': len(vocab_data['pinyin_vocab']),
        'hanzi_vocab_size': len(vocab_data['hanzi_vocab']),
        'special_tokens': {
            'pad': 0,
            'unk': 1,
            'sos': 2,
            'eos': 3
        }
    }
    with open(os.path.join(output_dir, 'config.json'), 'w', encoding='utf-8') as f:
        json.dump(config_data, f, indent=2, ensure_ascii=False)

    print(f"Exported vocabularies to {output_dir}")
    print(f"  - Pinyin vocab size: {len(vocab_data['pinyin_vocab'])}")
    print(f"  - Hanzi vocab size: {len(vocab_data['hanzi_vocab'])}")
    print(f"  - Max length: {vocab_data['max_length']}")


def export_to_onnx(model, vocab_data, output_dir, max_length=14):
    """Export model to ONNX format"""
    os.makedirs(output_dir, exist_ok=True)

    # Create combined model for simpler export
    combined_model = CombinedTransformer(model)
    combined_model.eval()

    # Sample inputs - use FIXED sequence lengths (no dynamic shapes)
    # The Android app will need to pad inputs to these exact lengths
    batch_size = 1
    pinyin_input = torch.randint(0, 100, (batch_size, max_length), dtype=torch.long)
    hanzi_input = torch.randint(0, 100, (batch_size, max_length - 1), dtype=torch.long)

    # Export combined model
    onnx_path = os.path.join(output_dir, 'pinyin2hanzi.onnx')

    print("Exporting combined model to ONNX (using legacy exporter)...")
    print(f"  Fixed input shapes: pinyin=[1,{max_length}], hanzi=[1,{max_length-1}]")

    # Use legacy ONNX exporter - NO dynamic axes for maximum compatibility
    torch.onnx.export(
        combined_model,
        (pinyin_input, hanzi_input),
        onnx_path,
        input_names=['pinyin_ids', 'hanzi_ids'],
        output_names=['logits'],
        opset_version=14,
        do_constant_folding=True,
        verbose=False,
        dynamo=False  # Use legacy exporter, not torch.export based
    )

    print(f"Exported ONNX model to {onnx_path}")

    # Get model size
    model_size = os.path.getsize(onnx_path) / (1024 * 1024)
    print(f"Model size: {model_size:.2f} MB")

    return onnx_path


def verify_onnx_model(onnx_path, vocab_data):
    """Verify the exported ONNX model"""
    import onnxruntime as ort

    print("\nVerifying ONNX model...")

    # Load ONNX model
    session = ort.InferenceSession(onnx_path)

    # Get input/output info
    print("Model inputs:")
    for input in session.get_inputs():
        print(f"  {input.name}: {input.shape} ({input.type})")

    print("Model outputs:")
    for output in session.get_outputs():
        print(f"  {output.name}: {output.shape} ({output.type})")

    # Test inference
    max_length = vocab_data['max_length']
    pinyin2idx = vocab_data['pinyin2idx']
    idx2hanzi = vocab_data['idx2hanzi']
    hanzi2idx = vocab_data['hanzi2idx']

    # Test input: "ni hao" (你好)
    test_pinyin = "ni3 hao3"
    pinyin_tokens = ['<sos>'] + test_pinyin.split() + ['<eos>']
    pinyin_ids = [pinyin2idx.get(t, pinyin2idx['<unk>']) for t in pinyin_tokens]
    pinyin_ids = pinyin_ids + [0] * (max_length - len(pinyin_ids))

    # Start with <sos>
    hanzi_ids = [hanzi2idx['<sos>']]

    print(f"\nTest input: '{test_pinyin}'")
    print(f"Pinyin tokens: {pinyin_tokens}")
    print(f"Pinyin IDs: {pinyin_ids}")

    # Autoregressive generation with padded inputs (fixed shape model)
    for i in range(max_length - 1):
        # Pad hanzi_ids to max_length - 1
        padded_hanzi = hanzi_ids + [0] * (max_length - 1 - len(hanzi_ids))

        # Run inference
        outputs = session.run(
            None,
            {
                'pinyin_ids': np.array([pinyin_ids], dtype=np.int64),
                'hanzi_ids': np.array([padded_hanzi], dtype=np.int64)
            }
        )

        logits = outputs[0]  # (1, seq_len, vocab_size)

        # Get prediction for the current position (len(hanzi_ids) - 1, predicts next token)
        next_token_logits = logits[0, len(hanzi_ids) - 1, :]
        next_token_id = int(np.argmax(next_token_logits))

        # Check for <eos>
        if next_token_id == hanzi2idx['<eos>']:
            break

        hanzi_ids.append(next_token_id)

    # Convert to text - idx2hanzi has int keys
    result = ''.join([idx2hanzi.get(idx, '?') for idx in hanzi_ids[1:]])
    print(f"Output: {result}")

    return True


def quantize_to_fp16(onnx_path, output_path):
    """Quantize ONNX model to FP16"""
    from onnxconverter_common import float16

    import onnx

    print(f"\nQuantizing model to FP16...")

    # Load the model
    model = onnx.load(onnx_path)

    # Convert to FP16
    model_fp16 = float16.convert_float_to_float16(model, keep_io_types=True)

    # Save the quantized model
    onnx.save(model_fp16, output_path)

    # Get model size
    model_size = os.path.getsize(output_path) / (1024 * 1024)
    print(f"FP16 model saved to {output_path}")
    print(f"FP16 model size: {model_size:.2f} MB")

    return output_path


def main():
    # Paths
    model_path = "pinyin2hanzi_transformer.pth"
    output_dir = "onnx_model"

    print("=" * 60)
    print("Pinyin2Hanzi Transformer - ONNX Conversion")
    print("=" * 60)

    # Check if model exists
    if not os.path.exists(model_path):
        print(f"Error: Model file not found: {model_path}")
        return

    # Load model
    print(f"\nLoading model from {model_path}...")
    model, vocab_data = load_model(model_path)

    print(f"\nModel configuration:")
    for k, v in vocab_data['config'].items():
        print(f"  {k}: {v}")

    # Export vocabularies
    print("\n" + "-" * 40)
    export_vocabularies(vocab_data, output_dir)

    # Export to ONNX
    print("\n" + "-" * 40)
    onnx_path = export_to_onnx(model, vocab_data, output_dir, vocab_data['max_length'])

    # Quantize to FP16
    print("\n" + "-" * 40)
    try:
        fp16_path = os.path.join(output_dir, 'pinyin2hanzi_fp16.onnx')
        quantize_to_fp16(onnx_path, fp16_path)
    except Exception as e:
        print(f"FP16 quantization failed: {e}")
        fp16_path = None

    # Verify original model
    print("\n" + "-" * 40)
    try:
        verify_onnx_model(onnx_path, vocab_data)
        print("\n✓ ONNX conversion successful!")
    except Exception as e:
        print(f"\n✗ Verification failed: {e}")
        import traceback
        traceback.print_exc()

    # Verify FP16 model if available
    if fp16_path:
        print("\n" + "-" * 40)
        print("Verifying FP16 model...")
        try:
            verify_onnx_model(fp16_path, vocab_data)
            print("\n✓ FP16 ONNX verification successful!")
        except Exception as e:
            print(f"\n✗ FP16 Verification failed: {e}")
            import traceback
            traceback.print_exc()


if __name__ == "__main__":
    main()
