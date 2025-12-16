#!/usr/bin/env python3
"""
Preprocess pinyin dataset for IME training.

Changes:
1. Remove spaces between pinyin syllables (e.g., "ni hao" -> "nihao")
2. Remove punctuation from hanzi (comma, period, etc.)
3. Filter out samples that become too short or invalid

Usage:
    python preprocess_pinyin_dataset.py --input ../pinyin_dataset.json --output ../pinyin_dataset_ime.json
"""

import argparse
import json
import re
from collections import Counter

# Chinese punctuation to remove
CHINESE_PUNCTUATION = '，。、；：？！""''【】《》（）——…·'
ENGLISH_PUNCTUATION = ',.;:?!"\'-()[]{}/'
ALL_PUNCTUATION = CHINESE_PUNCTUATION + ENGLISH_PUNCTUATION + ' \t\n'

# Create translation table for removing punctuation
PUNCT_TABLE = str.maketrans('', '', ALL_PUNCTUATION)


def remove_punctuation(text: str) -> str:
    """Remove all punctuation from Chinese text."""
    return text.translate(PUNCT_TABLE)


def remove_spaces(pinyin: str) -> str:
    """Remove spaces from pinyin, keeping it as continuous string."""
    return pinyin.replace(' ', '').lower()


def is_valid_sample(pinyin: str, hanzi: str, min_len: int = 2, max_len: int = 50) -> bool:
    """Check if sample is valid after preprocessing."""
    if len(pinyin) < min_len or len(hanzi) < min_len:
        return False
    if len(pinyin) > max_len * 6 or len(hanzi) > max_len:  # ~6 chars per pinyin syllable max
        return False
    # Check pinyin only contains valid characters
    if not re.match(r'^[a-z]+$', pinyin):
        return False
    # Check hanzi contains actual Chinese characters
    if not any('\u4e00' <= c <= '\u9fff' for c in hanzi):
        return False
    return True


def preprocess_dataset(input_path: str, output_path: str, min_len: int = 2, max_len: int = 50):
    """Preprocess dataset for IME training."""
    print(f"Loading dataset from {input_path}...")

    with open(input_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    original_samples = data['data']
    print(f"Original samples: {len(original_samples)}")

    processed_samples = []
    stats = Counter()

    for sample in original_samples:
        original_pinyin = sample['pinyin']
        original_hanzi = sample['hanzi']

        # Process pinyin: remove spaces
        pinyin = remove_spaces(original_pinyin)

        # Process hanzi: remove punctuation
        hanzi = remove_punctuation(original_hanzi)

        # Validate
        if is_valid_sample(pinyin, hanzi, min_len, max_len):
            processed_samples.append({
                'pinyin': pinyin,
                'hanzi': hanzi
            })
            stats['valid'] += 1
        else:
            if len(pinyin) < min_len:
                stats['too_short_pinyin'] += 1
            elif len(hanzi) < min_len:
                stats['too_short_hanzi'] += 1
            elif len(pinyin) > max_len * 6:
                stats['too_long_pinyin'] += 1
            elif len(hanzi) > max_len:
                stats['too_long_hanzi'] += 1
            else:
                stats['invalid_chars'] += 1

    # Calculate length statistics
    pinyin_lengths = [len(s['pinyin']) for s in processed_samples]
    hanzi_lengths = [len(s['hanzi']) for s in processed_samples]

    # Create output data
    output_data = {
        'metadata': {
            'total_samples': len(processed_samples),
            'original_samples': len(original_samples),
            'min_pinyin_len': min(pinyin_lengths) if pinyin_lengths else 0,
            'max_pinyin_len': max(pinyin_lengths) if pinyin_lengths else 0,
            'avg_pinyin_len': sum(pinyin_lengths) / len(pinyin_lengths) if pinyin_lengths else 0,
            'min_hanzi_len': min(hanzi_lengths) if hanzi_lengths else 0,
            'max_hanzi_len': max(hanzi_lengths) if hanzi_lengths else 0,
            'avg_hanzi_len': sum(hanzi_lengths) / len(hanzi_lengths) if hanzi_lengths else 0,
            'format': 'ime',
            'description': 'Pinyin without spaces, hanzi without punctuation'
        },
        'data': processed_samples
    }

    # Save
    print(f"\nSaving to {output_path}...")
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(output_data, f, ensure_ascii=False, indent=2)

    # Print statistics
    print(f"\n{'='*60}")
    print("Preprocessing Statistics")
    print('='*60)
    print(f"Original samples:  {len(original_samples)}")
    print(f"Processed samples: {len(processed_samples)}")
    print(f"Removed samples:   {len(original_samples) - len(processed_samples)}")
    print(f"\nRemoval reasons:")
    for reason, count in stats.items():
        if reason != 'valid':
            print(f"  {reason}: {count}")

    print(f"\nPinyin length: {min(pinyin_lengths)}-{max(pinyin_lengths)} chars (avg: {sum(pinyin_lengths)/len(pinyin_lengths):.1f})")
    print(f"Hanzi length:  {min(hanzi_lengths)}-{max(hanzi_lengths)} chars (avg: {sum(hanzi_lengths)/len(hanzi_lengths):.1f})")

    # Show examples
    print(f"\n{'='*60}")
    print("Example Samples (first 5)")
    print('='*60)
    for i, sample in enumerate(processed_samples[:5]):
        print(f"\n{i+1}. Pinyin: {sample['pinyin']}")
        print(f"   Hanzi:  {sample['hanzi']}")

    print(f"\n{'='*60}")
    print(f"Dataset saved to {output_path}")
    print('='*60)


def main():
    parser = argparse.ArgumentParser(description='Preprocess pinyin dataset for IME')
    parser.add_argument('--input', type=str, default='../pinyin_dataset.json',
                       help='Input dataset path')
    parser.add_argument('--output', type=str, default='../pinyin_dataset_ime.json',
                       help='Output dataset path')
    parser.add_argument('--min-len', type=int, default=2,
                       help='Minimum length (characters)')
    parser.add_argument('--max-len', type=int, default=50,
                       help='Maximum hanzi length')
    args = parser.parse_args()

    preprocess_dataset(args.input, args.output, args.min_len, args.max_len)


if __name__ == '__main__':
    main()
