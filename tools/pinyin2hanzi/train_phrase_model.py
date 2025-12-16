"""
Train a phrase-based pinyin-to-hanzi model using dynamic programming.

This approach uses:
1. Phrase dictionary from pinyin_phrases.json (239k entries)
2. Character emission probabilities from pinyin_dataset.json
3. Bigram transition probabilities for smoothing

The model uses a Viterbi-like algorithm that prefers longer phrase matches
while falling back to character-level prediction when needed.
"""

import json
import pickle
import bz2
import numpy as np
from collections import defaultdict
from tqdm import tqdm
import os
import re


class PhrasePinyinModel:
    """Phrase-based pinyin to hanzi model"""

    def __init__(self):
        # Phrase dictionary: concatenated_pinyin -> list of hanzi options
        self.phrase_dict = {}

        # Character-level mappings
        self.pinyin_to_chars = defaultdict(list)  # pinyin -> [(char, score), ...]
        self.char_bigrams = defaultdict(lambda: defaultdict(float))  # char1 -> char2 -> score

        # Valid pinyin syllables
        self.valid_syllables = set()

        # Character unigram probabilities
        self.char_unigram = defaultdict(float)

    def load_phrase_dict(self, filepath):
        """Load phrase dictionary"""
        print(f"Loading phrase dictionary from {filepath}...")

        with open(filepath, 'r', encoding='utf-8') as f:
            self.phrase_dict = json.load(f)

        # Extract all valid pinyin from phrases
        for pinyin_concat in self.phrase_dict.keys():
            # Try to identify syllables (this is the concatenated form)
            pass  # We'll use the sentence dataset for syllable extraction

        print(f"Loaded {len(self.phrase_dict)} phrases")

    def train_from_sentences(self, data, boost_factor=10):
        """Train character-level model from sentence data"""
        print("Training from sentence data...")

        # Count character occurrences per pinyin
        pinyin_char_counts = defaultdict(lambda: defaultdict(int))
        bigram_counts = defaultdict(lambda: defaultdict(int))
        unigram_counts = defaultdict(int)

        for item in tqdm(data, desc="Processing sentences"):
            pinyin_tokens = item['pinyin'].split()
            hanzi_chars = list(item['hanzi'])

            # Skip if lengths don't match
            if len(pinyin_tokens) != len(hanzi_chars):
                continue

            # Record valid syllables
            self.valid_syllables.update(pinyin_tokens)

            for i, (py, hz) in enumerate(zip(pinyin_tokens, hanzi_chars)):
                # Character emission
                pinyin_char_counts[py][hz] += 1
                unigram_counts[hz] += 1

                # Bigram transitions
                if i > 0:
                    prev_hz = hanzi_chars[i - 1]
                    bigram_counts[prev_hz][hz] += 1

        # Normalize to scores (higher = more likely)
        print("Normalizing scores...")

        # Character emissions
        for py, char_counts in pinyin_char_counts.items():
            total = sum(char_counts.values())
            sorted_chars = sorted(char_counts.items(), key=lambda x: -x[1])
            self.pinyin_to_chars[py] = [
                (char, count / total) for char, count in sorted_chars
            ]

        # Bigrams
        for c1, c2_counts in bigram_counts.items():
            total = sum(c2_counts.values())
            for c2, count in c2_counts.items():
                self.char_bigrams[c1][c2] = count / total

        # Unigrams
        total_chars = sum(unigram_counts.values())
        for char, count in unigram_counts.items():
            self.char_unigram[char] = count / total_chars

        print(f"  Valid syllables: {len(self.valid_syllables)}")
        print(f"  Pinyin-char mappings: {len(self.pinyin_to_chars)}")

        # Boost phrase dictionary entries in character scores
        if self.phrase_dict:
            print(f"Boosting phrase dictionary entries (factor={boost_factor})...")
            self._boost_phrase_chars(boost_factor)

    def _boost_phrase_chars(self, boost_factor):
        """Boost character scores based on phrase dictionary"""
        boosted = 0

        for pinyin_concat, hanzi_list in self.phrase_dict.items():
            if not hanzi_list:
                continue

            # Get best hanzi for this phrase
            hanzi = hanzi_list[0]

            # Try to segment the pinyin (simple greedy approach)
            syllables = self._segment_pinyin(pinyin_concat)

            if syllables and len(syllables) == len(hanzi):
                # Boost each character's score for its pinyin
                for py, hz in zip(syllables, hanzi):
                    if py in self.pinyin_to_chars:
                        # Find and boost this char
                        for i, (char, score) in enumerate(self.pinyin_to_chars[py]):
                            if char == hz:
                                # Boost this entry
                                new_score = min(1.0, score * boost_factor)
                                self.pinyin_to_chars[py][i] = (char, new_score)
                                boosted += 1
                                break
                        else:
                            # Add new entry if not found
                            self.pinyin_to_chars[py].append((hz, 0.5))
                            boosted += 1

        print(f"  Boosted {boosted} character entries")

    def _segment_pinyin(self, pinyin_concat):
        """Segment concatenated pinyin into syllables using greedy matching"""
        syllables = []
        remaining = pinyin_concat.lower()

        while remaining:
            # Try longest match first
            matched = False
            for length in range(min(6, len(remaining)), 0, -1):
                candidate = remaining[:length]
                if candidate in self.valid_syllables:
                    syllables.append(candidate)
                    remaining = remaining[length:]
                    matched = True
                    break

            if not matched:
                # No valid syllable found - take one character
                syllables.append(remaining[0])
                remaining = remaining[1:]

        return syllables

    def predict(self, pinyin_str):
        """Predict hanzi from space-separated pinyin"""
        syllables = pinyin_str.strip().lower().split()

        if not syllables:
            return ""

        # Try to find phrase matches first
        result = self._phrase_based_decode(syllables)
        return result

    def _phrase_based_decode(self, syllables):
        """Decode using phrase dictionary with character fallback"""
        n = len(syllables)

        # DP: best[i] = (score, prev_idx, hanzi_segment)
        # best[i] means best way to decode syllables[0:i]
        best = [(-float('inf'), -1, "")] * (n + 1)
        best[0] = (0.0, -1, "")

        for i in range(n):
            if best[i][0] == -float('inf'):
                continue

            # Try phrase matches of different lengths
            for length in range(1, min(8, n - i) + 1):
                # Get concatenated pinyin for this span
                span_syllables = syllables[i:i+length]
                pinyin_concat = ''.join(span_syllables)

                # Check phrase dictionary
                if pinyin_concat in self.phrase_dict and self.phrase_dict[pinyin_concat]:
                    hanzi = self.phrase_dict[pinyin_concat][0]

                    # Score: prefer longer matches
                    phrase_score = length * 2.0  # Bonus for phrase match

                    new_score = best[i][0] + phrase_score
                    if new_score > best[i + length][0]:
                        best[i + length] = (new_score, i, hanzi)

                # Also try character-by-character for length 1
                if length == 1:
                    py = span_syllables[0]
                    if py in self.pinyin_to_chars and self.pinyin_to_chars[py]:
                        # Get best character
                        char, char_score = self.pinyin_to_chars[py][0]

                        # Apply bigram bonus if applicable
                        if i > 0 and best[i][2]:
                            prev_char = best[i][2][-1]
                            if prev_char in self.char_bigrams:
                                bigram_bonus = self.char_bigrams[prev_char].get(char, 0.0)
                                char_score += bigram_bonus * 0.5

                        new_score = best[i][0] + char_score
                        if new_score > best[i + 1][0]:
                            best[i + 1] = (new_score, i, char)

        # Backtrack to get result
        if best[n][0] == -float('inf'):
            # Fallback: just use top character for each syllable
            return ''.join([
                self.pinyin_to_chars[py][0][0] if py in self.pinyin_to_chars and self.pinyin_to_chars[py]
                else '?'
                for py in syllables
            ])

        # Backtrack
        result = []
        idx = n
        while idx > 0:
            score, prev_idx, hanzi = best[idx]
            result.append(hanzi)
            idx = prev_idx

        result.reverse()
        return ''.join(result)

    def save(self, filepath):
        """Save model to file"""
        print(f"Saving model to {filepath}...")

        model_data = {
            'phrase_dict': self.phrase_dict,
            'pinyin_to_chars': dict(self.pinyin_to_chars),
            'char_bigrams': {k: dict(v) for k, v in self.char_bigrams.items()},
            'char_unigram': dict(self.char_unigram),
            'valid_syllables': list(self.valid_syllables),
        }

        if filepath.endswith('.bz2'):
            with bz2.open(filepath, 'wb') as f:
                pickle.dump(model_data, f)
        else:
            with open(filepath, 'wb') as f:
                pickle.dump(model_data, f)

        size_mb = os.path.getsize(filepath) / (1024 * 1024)
        print(f"Model saved ({size_mb:.2f} MB)")

    @classmethod
    def load(cls, filepath):
        """Load model from file"""
        print(f"Loading model from {filepath}...")

        if filepath.endswith('.bz2'):
            with bz2.open(filepath, 'rb') as f:
                model_data = pickle.load(f)
        else:
            with open(filepath, 'rb') as f:
                model_data = pickle.load(f)

        model = cls()
        model.phrase_dict = model_data['phrase_dict']
        model.pinyin_to_chars = defaultdict(list, model_data['pinyin_to_chars'])
        model.char_bigrams = defaultdict(
            lambda: defaultdict(float),
            {k: defaultdict(float, v) for k, v in model_data['char_bigrams'].items()}
        )
        model.char_unigram = defaultdict(float, model_data['char_unigram'])
        model.valid_syllables = set(model_data['valid_syllables'])

        print(f"Model loaded (phrases={len(model.phrase_dict)}, syllables={len(model.valid_syllables)})")
        return model


def evaluate(model, test_data, n_samples=100):
    """Evaluate model"""
    print(f"\nEvaluating on {n_samples} samples...")

    char_correct = 0
    char_total = 0
    exact_match = 0

    samples = test_data[:n_samples]

    for item in tqdm(samples, desc="Evaluating"):
        pinyin = item['pinyin']
        expected = item['hanzi']
        predicted = model.predict(pinyin)

        if predicted == expected:
            exact_match += 1

        min_len = min(len(predicted), len(expected))
        for i in range(min_len):
            if predicted[i] == expected[i]:
                char_correct += 1
        char_total += len(expected)

    print(f"\nResults:")
    print(f"  Exact match: {exact_match}/{n_samples} = {100*exact_match/n_samples:.2f}%")
    print(f"  Character accuracy: {char_correct}/{char_total} = {100*char_correct/char_total:.2f}%")

    # Show examples
    print("\nSample predictions:")
    for item in test_data[:5]:
        pinyin = item['pinyin']
        expected = item['hanzi']
        predicted = model.predict(pinyin)
        match = "✓" if predicted == expected else "✗"
        print(f"  {match} Input: {pinyin[:50]}...")
        print(f"      Expected:  {expected[:40]}...")
        print(f"      Predicted: {predicted[:40]}...")
        print()


def main():
    import argparse

    parser = argparse.ArgumentParser(description='Train phrase-based pinyin model')
    parser.add_argument('--dataset', type=str,
                       default='/Users/coolwulf/Documents/GitHub/pastiera/pinyin_dataset.json')
    parser.add_argument('--phrases', type=str,
                       default='/Users/coolwulf/Documents/GitHub/pastiera/app/src/main/assets/common/pinyin/pinyin_phrases.json')
    parser.add_argument('--output', type=str,
                       default='phrase_model_toneless.pkl.bz2')
    parser.add_argument('--test', action='store_true')
    args = parser.parse_args()

    if args.test:
        model = PhrasePinyinModel.load(args.output)

        test_inputs = [
            "ni hao",
            "wo ai ni",
            "zhong guo",
            "bei jing",
            "shang hai",
            "xie xie",
            "dui bu qi",
            "jin tian tian qi hen hao",
            "wo shi zhong guo ren",
            "chi fan",
            "he shui",
            "shou ji",
            "dian nao",
            "peng you",
        ]

        print("\nTest predictions:")
        for pinyin in test_inputs:
            result = model.predict(pinyin)
            print(f"  '{pinyin}' -> '{result}'")

        return

    # Load datasets
    print("Loading sentence dataset...")
    with open(args.dataset, 'r', encoding='utf-8') as f:
        sentence_data = json.load(f)['data']
    print(f"  {len(sentence_data)} sentences")

    # Split train/test
    split_idx = int(len(sentence_data) * 0.95)
    train_data = sentence_data[:split_idx]
    test_data = sentence_data[split_idx:]

    # Create model
    model = PhrasePinyinModel()

    # Load phrase dictionary
    model.load_phrase_dict(args.phrases)

    # Train from sentences
    model.train_from_sentences(train_data, boost_factor=5)

    # Save
    model.save(args.output)

    # Evaluate
    evaluate(model, test_data, n_samples=200)

    # Quick test
    print("\n" + "="*60)
    print("Quick test:")
    test_inputs = [
        "ni hao",
        "wo ai ni",
        "zhong guo",
        "bei jing",
        "xie xie",
        "chi fan",
        "shou ji",
    ]
    for pinyin in test_inputs:
        result = model.predict(pinyin)
        print(f"  '{pinyin}' -> '{result}'")


if __name__ == "__main__":
    main()
