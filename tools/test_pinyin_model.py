#!/usr/bin/env python3
"""
Test the trained Somiao pinyin model with toneless pinyin inputs.
"""

import json
import numpy as np
import onnxruntime as ort

MODEL_DIR = "pinyin_model"

def load_vocab(path):
    """Load vocabulary from file."""
    vocab = {}
    with open(path, 'r', encoding='utf-8') as f:
        for idx, line in enumerate(f):
            token = line.rstrip('\n')
            vocab[token] = idx
    return vocab

def load_reverse_vocab(path):
    """Load reverse vocabulary (idx -> token)."""
    vocab = {}
    with open(path, 'r', encoding='utf-8') as f:
        for idx, line in enumerate(f):
            token = line.rstrip('\n')
            vocab[idx] = token
    return vocab

def encode_pinyin(text, vocab, max_len=50):
    """Encode pinyin text to indices."""
    indices = []
    for char in text:
        if char in vocab:
            indices.append(vocab[char])
        else:
            indices.append(vocab.get('<unk>', 1))

    # Pad or truncate
    if len(indices) < max_len:
        indices.extend([0] * (max_len - len(indices)))
    else:
        indices = indices[:max_len]

    return np.array([indices], dtype=np.int64)

def decode_hanzi(indices, vocab, pinyin_len):
    """Decode indices to hanzi text."""
    result = []
    for i, idx in enumerate(indices):
        if i >= pinyin_len:
            break
        if idx in vocab:
            char = vocab[idx]
            if char not in ['<pad>', '<unk>', '_']:
                result.append(char)
    return ''.join(result)

def main():
    # Load config
    with open(f"{MODEL_DIR}/config.json", 'r') as f:
        config = json.load(f)

    print(f"Model type: {config['model_type']}")
    print(f"Toneless: {config['toneless']}")
    print(f"Pinyin vocab size: {config['pinyin_vocab_size']}")
    print(f"Hanzi vocab size: {config['hanzi_vocab_size']}")
    print()

    # Load vocabularies
    pinyin_vocab = load_vocab(f"{MODEL_DIR}/vocab_pinyin.txt")
    hanzi_vocab = load_reverse_vocab(f"{MODEL_DIR}/vocab_hanzi.txt")

    # Load ONNX model
    print("Loading ONNX model...")
    session = ort.InferenceSession(f"{MODEL_DIR}/model.onnx")

    # Get input/output names
    input_name = session.get_inputs()[0].name
    output_name = session.get_outputs()[0].name
    print(f"Input: {input_name}, Output: {output_name}")
    print()

    # Test cases - common toneless pinyin phrases
    test_cases = [
        "wo",           # 我
        "ni hao",       # 你好
        "zhong guo",    # 中国
        "bei jing",     # 北京
        "xie xie",      # 谢谢
        "zai jian",     # 再见
        "wo ai ni",     # 我爱你
        "jin tian",     # 今天
        "ming tian",    # 明天
        "ni hao ma",    # 你好吗
        "wo shi zhong guo ren",  # 我是中国人
        "wo men",       # 我们
        "ta men",       # 他们
        "da jia hao",   # 大家好
        "dian hua",     # 电话
        "shou ji",      # 手机
        "dian nao",     # 电脑
        "gong zuo",     # 工作
        "xue xi",       # 学习
        "chi fan",      # 吃饭
    ]

    print("=" * 60)
    print("Testing toneless pinyin -> Chinese conversion")
    print("=" * 60)

    for pinyin in test_cases:
        # Encode
        input_data = encode_pinyin(pinyin, pinyin_vocab, config['max_len'])

        # Run inference
        outputs = session.run([output_name], {input_name: input_data})
        logits = outputs[0]  # Shape: [1, seq_len, vocab_size]

        # Get predictions (argmax)
        predictions = np.argmax(logits[0], axis=-1)

        # Decode - use pinyin length (without spaces for character count estimation)
        pinyin_chars = len(pinyin.replace(' ', ''))
        hanzi = decode_hanzi(predictions, hanzi_vocab, len(pinyin))

        print(f"  {pinyin:30} -> {hanzi}")

    print()
    print("=" * 60)
    print("Interactive test (type 'quit' to exit)")
    print("=" * 60)

    while True:
        try:
            pinyin = input("\nEnter toneless pinyin: ").strip()
            if pinyin.lower() == 'quit':
                break
            if not pinyin:
                continue

            # Encode and run
            input_data = encode_pinyin(pinyin, pinyin_vocab, config['max_len'])
            outputs = session.run([output_name], {input_name: input_data})
            predictions = np.argmax(outputs[0][0], axis=-1)
            hanzi = decode_hanzi(predictions, hanzi_vocab, len(pinyin))

            print(f"  Result: {hanzi}")

        except KeyboardInterrupt:
            break
        except Exception as e:
            print(f"  Error: {e}")

    print("\nDone!")

if __name__ == "__main__":
    main()
