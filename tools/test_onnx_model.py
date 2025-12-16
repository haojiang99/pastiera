#!/usr/bin/env python3
"""
Test script for ONNX INT8 Pinyin-to-Hanzi model.

Features:
- Interactive mode: Type pinyin and see predictions
- Single input testing via command line
- Greedy and beam search decoding
- Performance benchmarking

Usage:
    # Interactive testing
    python test_onnx_model.py --model-dir ../output/onnx --interactive

    # Test specific input
    python test_onnx_model.py --model-dir ../output/onnx --input "nihao"

    # Test with examples
    python test_onnx_model.py --model-dir ../output/onnx --examples
"""

import argparse
import json
import sys
import time
from pathlib import Path
from typing import List, Tuple, Dict, Optional

# Fix Windows console encoding for Chinese characters
if sys.platform == 'win32':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

try:
    import numpy as np
    import onnxruntime as ort
    HAS_ORT = True
except ImportError:
    HAS_ORT = False
    print("Error: onnxruntime not installed.")
    print("Install with: pip install onnxruntime")

# Special tokens
PAD_IDX = 0
SOS_IDX = 1
EOS_IDX = 2
UNK_IDX = 3


class Vocabulary:
    """Vocabulary for mapping tokens to indices."""

    def __init__(self):
        self.token2idx = {}
        self.idx2token = {}

    def __len__(self):
        return len(self.token2idx)

    def encode(self, tokens: List[str]) -> List[int]:
        return [self.token2idx.get(t, UNK_IDX) for t in tokens]

    def decode(self, indices: List[int], skip_special: bool = True) -> List[str]:
        tokens = []
        for i in indices:
            if skip_special and i in [PAD_IDX, SOS_IDX, EOS_IDX]:
                continue
            token = self.idx2token.get(i, '<UNK>')
            if skip_special and token == '<EOS>':
                break
            tokens.append(token)
        return tokens

    @classmethod
    def load(cls, path: str) -> 'Vocabulary':
        vocab = cls()
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)
            vocab.token2idx = data['token2idx']
            vocab.idx2token = {int(k): v for k, v in data['idx2token'].items()}
        return vocab


class ONNXPinyinModel:
    """ONNX model wrapper for Pinyin-to-Hanzi inference."""

    def __init__(self, model_dir: str, use_int8: bool = True):
        model_dir = Path(model_dir)

        # Select model files
        if use_int8:
            encoder_path = model_dir / 'encoder_int8.onnx'
            decoder_path = model_dir / 'decoder_int8.onnx'
        else:
            encoder_path = model_dir / 'encoder.onnx'
            decoder_path = model_dir / 'decoder.onnx'

        # Load ONNX models
        print(f"Loading encoder: {encoder_path}")
        print(f"Loading decoder: {decoder_path}")

        # Use CPU provider for compatibility
        providers = ['CPUExecutionProvider']

        # Try to use CUDA if available
        if 'CUDAExecutionProvider' in ort.get_available_providers():
            providers = ['CUDAExecutionProvider', 'CPUExecutionProvider']
            print("Using CUDA for inference")
        else:
            print("Using CPU for inference")

        self.encoder = ort.InferenceSession(str(encoder_path), providers=providers)
        self.decoder = ort.InferenceSession(str(decoder_path), providers=providers)

        # Load vocabularies
        self.pinyin_vocab = Vocabulary.load(str(model_dir / 'vocab_pinyin.json'))
        self.hanzi_vocab = Vocabulary.load(str(model_dir / 'vocab_hanzi.json'))

        # Load config
        config_path = model_dir / 'inference_config.json'
        if config_path.exists():
            with open(config_path, 'r', encoding='utf-8') as f:
                self.config = json.load(f)
        else:
            self.config = {'char_level_pinyin': True, 'max_output_len': 64}

        self.char_level_pinyin = self.config.get('char_level_pinyin', True)
        self.max_output_len = self.config.get('max_output_len', 64)

        print(f"Pinyin vocab: {len(self.pinyin_vocab)} tokens")
        print(f"Hanzi vocab: {len(self.hanzi_vocab)} tokens")
        print(f"Pinyin format: {'character-level' if self.char_level_pinyin else 'syllable-level'}")

    def tokenize_pinyin(self, pinyin_str: str) -> List[int]:
        """Tokenize pinyin string to IDs."""
        pinyin_str = pinyin_str.lower().strip()
        if self.char_level_pinyin:
            # Character-level: each character is a token
            tokens = list(pinyin_str.replace(' ', ''))
        else:
            # Syllable-level: split by space
            tokens = pinyin_str.split()
        return self.pinyin_vocab.encode(tokens)

    def greedy_decode(self, pinyin_str: str) -> Tuple[str, float]:
        """Greedy decoding for inference."""
        # Tokenize input
        pinyin_ids = self.tokenize_pinyin(pinyin_str)
        pinyin_input = np.array([pinyin_ids], dtype=np.int64)

        # Run encoder
        encoder_outputs, hidden, cell = self.encoder.run(
            None, {'pinyin_ids': pinyin_input}
        )

        # Decode step by step
        output_ids = []
        input_token = np.array([SOS_IDX], dtype=np.int64)
        total_score = 0.0

        for _ in range(self.max_output_len):
            output, hidden, cell = self.decoder.run(
                None, {
                    'input_token': input_token,
                    'encoder_outputs': encoder_outputs,
                    'hidden': hidden,
                    'cell': cell
                }
            )

            # Get top prediction
            probs = self._softmax(output[0])
            top_idx = int(np.argmax(probs))
            total_score += np.log(probs[top_idx] + 1e-10)

            if top_idx == EOS_IDX:
                break

            output_ids.append(top_idx)
            input_token = np.array([top_idx], dtype=np.int64)

        # Decode to text
        result = ''.join(self.hanzi_vocab.decode(output_ids))
        return result, total_score

    def beam_search(self, pinyin_str: str, beam_width: int = 3) -> List[Tuple[str, float]]:
        """Beam search decoding for better results."""
        # Tokenize input
        pinyin_ids = self.tokenize_pinyin(pinyin_str)
        pinyin_input = np.array([pinyin_ids], dtype=np.int64)

        # Run encoder
        encoder_outputs, hidden, cell = self.encoder.run(
            None, {'pinyin_ids': pinyin_input}
        )

        # Initialize beams: (sequence, score, hidden, cell)
        beams = [([SOS_IDX], 0.0, hidden, cell)]
        completed = []

        for _ in range(self.max_output_len):
            new_beams = []

            for seq, score, h, c in beams:
                if seq[-1] == EOS_IDX:
                    completed.append((seq[1:], score))  # Remove SOS
                    continue

                input_token = np.array([seq[-1]], dtype=np.int64)
                output, new_h, new_c = self.decoder.run(
                    None, {
                        'input_token': input_token,
                        'encoder_outputs': encoder_outputs,
                        'hidden': h,
                        'cell': c
                    }
                )

                probs = self._softmax(output[0])
                top_indices = np.argsort(probs)[-beam_width:][::-1]

                for idx in top_indices:
                    new_seq = seq + [int(idx)]
                    new_score = score + np.log(probs[idx] + 1e-10)
                    new_beams.append((new_seq, new_score, new_h.copy(), new_c.copy()))

            # Keep top beams
            new_beams.sort(key=lambda x: x[1], reverse=True)
            beams = new_beams[:beam_width]

            if not beams:
                break

        # Add remaining beams to completed
        for seq, score, _, _ in beams:
            if seq[-1] == EOS_IDX:
                completed.append((seq[1:-1], score))
            else:
                completed.append((seq[1:], score))

        # Sort by score normalized by length
        completed.sort(key=lambda x: x[1] / max(len(x[0]), 1), reverse=True)

        # Convert to text
        results = []
        for ids, score in completed[:beam_width]:
            ids = [i for i in ids if i not in [PAD_IDX, SOS_IDX, EOS_IDX]]
            text = ''.join(self.hanzi_vocab.decode(ids))
            results.append((text, score))

        return results

    def _softmax(self, x):
        """Compute softmax."""
        exp_x = np.exp(x - np.max(x))
        return exp_x / exp_x.sum()

    def predict(self, pinyin_str: str, beam_width: int = 1) -> Tuple[str, List[Tuple[str, float]]]:
        """Predict Chinese characters from pinyin."""
        if beam_width <= 1:
            result, score = self.greedy_decode(pinyin_str)
            return result, [(result, score)]
        else:
            results = self.beam_search(pinyin_str, beam_width)
            return results[0][0] if results else "", results


def test_examples(model: ONNXPinyinModel, beam_width: int = 3):
    """Test with predefined examples."""
    examples_syllable = [
        "ni hao",
        "ni hao ma",
        "wo ai ni",
        "zhong guo",
        "bei jing",
        "shang hai",
        "xie xie ni",
        "zai jian",
        "ni chi fan le ma",
        "wo shi zhong guo ren",
        "jin tian tian qi hen hao",
        "wo men yi qi qu chi fan ba",
        "zhong hua ren min gong he guo",
    ]

    # Convert to char-level if needed
    if model.char_level_pinyin:
        examples = [ex.replace(' ', '') for ex in examples_syllable]
    else:
        examples = examples_syllable

    print("\n" + "=" * 60)
    print("Testing Common Phrases (ONNX INT8)")
    print(f"Pinyin format: {'character-level' if model.char_level_pinyin else 'syllable-level'}")
    print("=" * 60)

    total_time = 0
    for pinyin in examples:
        start = time.perf_counter()
        result, all_results = model.predict(pinyin, beam_width=beam_width)
        elapsed = (time.perf_counter() - start) * 1000
        total_time += elapsed

        print(f"\n  {pinyin}")
        print(f"  -> {result} ({elapsed:.1f}ms)")
        if beam_width > 1 and len(all_results) > 1:
            for text, score in all_results[1:3]:
                print(f"    ({text})")

    print(f"\nAverage inference time: {total_time / len(examples):.1f}ms")


def interactive_mode(model: ONNXPinyinModel, beam_width: int = 3):
    """Interactive testing mode."""
    print("\n" + "=" * 60)
    print("Interactive Mode (ONNX INT8)")
    print("=" * 60)
    if model.char_level_pinyin:
        print("Enter pinyin (continuous, no spaces) to get predictions.")
        print("Example: nihao, woaini, zhongguo")
    else:
        print("Enter pinyin (space-separated) to get predictions.")
        print("Example: ni hao, wo ai ni, zhong guo")
    print("Commands: 'quit' to exit, 'beam N' to set beam width")
    print(f"Current beam width: {beam_width}")
    print("=" * 60 + "\n")

    while True:
        try:
            user_input = input("Pinyin> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nExiting...")
            break

        if not user_input:
            continue

        if user_input.lower() == 'quit':
            break

        if user_input.lower().startswith('beam '):
            try:
                beam_width = int(user_input.split()[1])
                print(f"Beam width set to {beam_width}")
            except:
                print("Usage: beam N (e.g., beam 5)")
            continue

        start = time.perf_counter()
        result, all_results = model.predict(user_input, beam_width=beam_width)
        elapsed = (time.perf_counter() - start) * 1000

        print(f"\nResult: {result}")
        if beam_width > 1 and len(all_results) > 1:
            print("All candidates:")
            for i, (text, score) in enumerate(all_results[:5], 1):
                print(f"  {i}. {text} (score: {score:.2f})")
        print(f"Time: {elapsed:.1f}ms\n")


def benchmark(model: ONNXPinyinModel, num_runs: int = 100):
    """Benchmark inference speed."""
    test_inputs = [
        "nihao",
        "woaizhongguo",
        "jintiantianqihenhao",
        "zhonghuarenmingongheguo"
    ]

    print("\n" + "=" * 60)
    print("Benchmark (ONNX INT8)")
    print("=" * 60)

    for pinyin in test_inputs:
        times = []
        for _ in range(num_runs):
            start = time.perf_counter()
            model.predict(pinyin, beam_width=1)
            times.append((time.perf_counter() - start) * 1000)

        avg_time = sum(times) / len(times)
        min_time = min(times)
        max_time = max(times)

        print(f"\n  {pinyin} ({len(pinyin)} chars)")
        print(f"    Avg: {avg_time:.2f}ms | Min: {min_time:.2f}ms | Max: {max_time:.2f}ms")


def main():
    parser = argparse.ArgumentParser(description='Test ONNX INT8 Pinyin model')
    parser.add_argument('--model-dir', type=str, default='../output/onnx',
                       help='Directory containing ONNX models')
    parser.add_argument('--input', type=str, default=None,
                       help='Single pinyin input to test')
    parser.add_argument('--interactive', action='store_true',
                       help='Run interactive mode')
    parser.add_argument('--examples', action='store_true',
                       help='Test with predefined examples')
    parser.add_argument('--benchmark', action='store_true',
                       help='Run performance benchmark')
    parser.add_argument('--beam-width', type=int, default=3,
                       help='Beam search width (1 for greedy)')
    parser.add_argument('--fp32', action='store_true',
                       help='Use FP32 models instead of INT8')
    args = parser.parse_args()

    if not HAS_ORT:
        return

    # Load model
    print("=" * 60)
    print("Loading ONNX Model")
    print("=" * 60)
    model = ONNXPinyinModel(args.model_dir, use_int8=not args.fp32)

    # Run tests
    if args.input:
        start = time.perf_counter()
        result, all_results = model.predict(args.input, args.beam_width)
        elapsed = (time.perf_counter() - start) * 1000

        print(f"\nInput:  {args.input}")
        print(f"Output: {result}")
        print(f"Time:   {elapsed:.1f}ms")
        if args.beam_width > 1:
            print("\nAll candidates:")
            for i, (text, score) in enumerate(all_results, 1):
                print(f"  {i}. {text} (score: {score:.2f})")

    elif args.benchmark:
        benchmark(model)

    elif args.interactive:
        interactive_mode(model, args.beam_width)

    elif args.examples:
        test_examples(model, args.beam_width)

    else:
        # Default: show examples then enter interactive mode
        test_examples(model, args.beam_width)
        interactive_mode(model, args.beam_width)


if __name__ == '__main__':
    main()
