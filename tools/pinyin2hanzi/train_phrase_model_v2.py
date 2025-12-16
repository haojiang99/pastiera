"""
Improved phrase-based pinyin-to-hanzi model v2.

Key improvements over v1:
1. Beam search with top-k candidates at each position
2. Better integration of phrase dictionary with character bigrams
3. Extract common word patterns from training sentences
4. Improved scoring that balances phrase matches vs character sequences
5. Context-aware character selection using trigrams
"""

import json
import pickle
import bz2
import numpy as np
from collections import defaultdict, Counter
from tqdm import tqdm
import os
import re
import math


class ImprovedPhrasePinyinModel:
    """Improved phrase-based pinyin to hanzi model with beam search"""

    def __init__(self):
        # Phrase dictionary: concatenated_pinyin -> list of (hanzi, score) tuples
        self.phrase_dict = {}

        # Character-level mappings
        self.pinyin_to_chars = defaultdict(list)  # pinyin -> [(char, score), ...]

        # N-gram probabilities (log space)
        self.char_unigram = defaultdict(float)  # P(char)
        self.char_bigram = defaultdict(lambda: defaultdict(float))  # P(c2|c1)
        self.char_trigram = defaultdict(lambda: defaultdict(float))  # P(c3|c1c2)

        # Valid pinyin syllables
        self.valid_syllables = set()

        # Learned word boundaries from training data
        self.learned_phrases = {}  # pinyin_concat -> [(hanzi, freq), ...]

        # Phrase frequencies from training data (to weight phrase dictionary)
        self.phrase_freq = {}  # hanzi -> frequency in training data

        # Beam size for decoding
        self.beam_size = 5

        # Scoring weights
        self.phrase_bonus = 5.0  # Bonus for phrase dictionary match (increased)
        self.learned_phrase_bonus = 3.0  # Bonus for learned phrase
        self.bigram_weight = 0.8  # Reduced to not override phrases
        self.trigram_weight = 1.0

    def load_phrase_dict(self, filepath):
        """Load phrase dictionary with frequency weighting"""
        print(f"Loading phrase dictionary from {filepath}...")

        with open(filepath, 'r', encoding='utf-8') as f:
            raw_dict = json.load(f)

        # Convert to scored format (first entry = highest score)
        for pinyin_concat, hanzi_list in raw_dict.items():
            if hanzi_list:
                # Assign decreasing scores based on position
                scored_list = []
                for i, hanzi in enumerate(hanzi_list[:5]):  # Keep top 5
                    score = 1.0 / (i + 1)  # 1.0, 0.5, 0.33, 0.25, 0.2
                    scored_list.append((hanzi, score))
                self.phrase_dict[pinyin_concat] = scored_list

        print(f"Loaded {len(self.phrase_dict)} phrases")

    def train_from_sentences(self, data, boost_factor=10):
        """Train character-level model and extract phrases from sentence data"""
        print("Training from sentence data...")

        # Count structures
        pinyin_char_counts = defaultdict(lambda: defaultdict(int))
        unigram_counts = defaultdict(int)
        bigram_counts = defaultdict(lambda: defaultdict(int))
        trigram_counts = defaultdict(lambda: defaultdict(int))

        # Track multi-character sequences (for learning common phrases)
        # Nested defaultdict: length -> pinyin_seq -> hanzi_seq -> count
        sequence_counts = defaultdict(lambda: defaultdict(lambda: defaultdict(int)))

        # Track phrase frequencies from training text
        phrase_freq_counts = defaultdict(int)

        total_chars = 0

        for item in tqdm(data, desc="Processing sentences"):
            pinyin_tokens = item['pinyin'].split()
            hanzi_chars = list(item['hanzi'])

            # Skip if lengths don't match
            if len(pinyin_tokens) != len(hanzi_chars):
                continue

            # Record valid syllables
            self.valid_syllables.update(pinyin_tokens)

            n = len(pinyin_tokens)

            for i, (py, hz) in enumerate(zip(pinyin_tokens, hanzi_chars)):
                # Character emission
                pinyin_char_counts[py][hz] += 1
                unigram_counts[hz] += 1
                total_chars += 1

                # Bigram transitions
                if i > 0:
                    prev_hz = hanzi_chars[i - 1]
                    bigram_counts[prev_hz][hz] += 1

                # Trigram transitions
                if i > 1:
                    prev2_hz = hanzi_chars[i - 2]
                    prev_hz = hanzi_chars[i - 1]
                    context = prev2_hz + prev_hz
                    trigram_counts[context][hz] += 1

                # Extract 2-4 character sequences as potential phrases
                for length in range(2, min(5, n - i + 1)):
                    py_seq = ''.join(pinyin_tokens[i:i+length])
                    hz_seq = ''.join(hanzi_chars[i:i+length])
                    sequence_counts[length][py_seq][hz_seq] += 1

        # Normalize character emissions
        print("Normalizing character emissions...")
        for py, char_counts in pinyin_char_counts.items():
            total = sum(char_counts.values())
            sorted_chars = sorted(char_counts.items(), key=lambda x: -x[1])
            # Keep top 20 candidates
            self.pinyin_to_chars[py] = [
                (char, count / total) for char, count in sorted_chars[:20]
            ]

        # Normalize unigrams (log probabilities)
        print("Computing n-gram probabilities...")
        for char, count in unigram_counts.items():
            self.char_unigram[char] = math.log(count / total_chars)

        # Normalize bigrams with smoothing
        for c1, c2_counts in bigram_counts.items():
            total = sum(c2_counts.values())
            for c2, count in c2_counts.items():
                # Add-k smoothing
                self.char_bigram[c1][c2] = math.log((count + 0.1) / (total + 0.1 * len(unigram_counts)))

        # Normalize trigrams
        for context, c3_counts in trigram_counts.items():
            total = sum(c3_counts.values())
            for c3, count in c3_counts.items():
                self.char_trigram[context][c3] = math.log((count + 0.01) / (total + 0.01 * len(unigram_counts)))

        # Extract learned phrases (sequences that appear frequently)
        print("Extracting common phrases from training data...")
        min_freq = 3  # Minimum frequency to consider
        for length, seq_dict in sequence_counts.items():
            for py_seq, hz_counts in seq_dict.items():
                # Skip if already in phrase dictionary
                if py_seq in self.phrase_dict:
                    continue

                # Get top hanzi sequences
                top_seqs = sorted(hz_counts.items(), key=lambda x: -x[1])
                if top_seqs and top_seqs[0][1] >= min_freq:
                    # Add as learned phrase
                    self.learned_phrases[py_seq] = [
                        (hz, count) for hz, count in top_seqs[:3] if count >= min_freq
                    ]

        print(f"  Valid syllables: {len(self.valid_syllables)}")
        print(f"  Pinyin-char mappings: {len(self.pinyin_to_chars)}")
        print(f"  Bigram entries: {len(self.char_bigram)}")
        print(f"  Trigram entries: {len(self.char_trigram)}")
        print(f"  Learned phrases: {len(self.learned_phrases)}")

        # Boost phrase dictionary entries
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
            hanzi = hanzi_list[0][0]

            # Try to segment the pinyin
            syllables = self._segment_pinyin(pinyin_concat)

            if syllables and len(syllables) == len(hanzi):
                for py, hz in zip(syllables, hanzi):
                    if py in self.pinyin_to_chars:
                        # Find and boost this char
                        for i, (char, score) in enumerate(self.pinyin_to_chars[py]):
                            if char == hz:
                                new_score = min(1.0, score * boost_factor)
                                self.pinyin_to_chars[py][i] = (char, new_score)
                                boosted += 1
                                break
                        else:
                            # Add new entry if not found
                            self.pinyin_to_chars[py].append((hz, 0.3))
                            boosted += 1

        # Re-sort and normalize after boosting
        for py in self.pinyin_to_chars:
            chars = self.pinyin_to_chars[py]
            total = sum(score for _, score in chars)
            self.pinyin_to_chars[py] = sorted(
                [(char, score / total) for char, score in chars],
                key=lambda x: -x[1]
            )[:20]  # Keep top 20

        print(f"  Boosted {boosted} character entries")

    def _segment_pinyin(self, pinyin_concat):
        """Segment concatenated pinyin into syllables using greedy matching"""
        syllables = []
        remaining = pinyin_concat.lower()

        while remaining:
            matched = False
            for length in range(min(6, len(remaining)), 0, -1):
                candidate = remaining[:length]
                if candidate in self.valid_syllables:
                    syllables.append(candidate)
                    remaining = remaining[length:]
                    matched = True
                    break

            if not matched:
                syllables.append(remaining[0])
                remaining = remaining[1:]

        return syllables

    def predict(self, pinyin_str):
        """Predict hanzi from space-separated pinyin using beam search"""
        syllables = pinyin_str.strip().lower().split()

        if not syllables:
            return ""

        return self._beam_decode(syllables)

    def _beam_decode(self, syllables):
        """Decode using beam search with phrase and character integration"""
        n = len(syllables)

        # Beam: list of (score, hanzi_so_far, position)
        # Use a dict to keep best score per (position, hanzi) to avoid duplicates
        beam = [(0.0, "", 0)]

        for step in range(n * 2):  # Max steps = 2 * n (safety limit)
            # Get candidates that haven't finished
            active_beam = [b for b in beam if b[2] < n]
            finished_beam = [b for b in beam if b[2] >= n]

            if not active_beam:
                break

            new_candidates = []

            for score, hanzi, pos in active_beam:
                # Try ALL phrase matches of different lengths (not just longest)
                for length in range(1, min(8, n - pos) + 1):
                    span_syllables = syllables[pos:pos+length]
                    pinyin_concat = ''.join(span_syllables)

                    # For single character
                    if length == 1:
                        py = span_syllables[0]
                        if py in self.pinyin_to_chars:
                            # Try top-k character candidates
                            for char, emit_score in self.pinyin_to_chars[py][:self.beam_size]:
                                new_score = score + math.log(emit_score + 1e-10) + 1.0  # Base score for char

                                # Add bigram bonus
                                if hanzi:
                                    prev_char = hanzi[-1]
                                    if prev_char in self.char_bigram and char in self.char_bigram[prev_char]:
                                        new_score += self.char_bigram[prev_char][char] * self.bigram_weight

                                    # Add trigram bonus
                                    if len(hanzi) >= 2:
                                        context = hanzi[-2] + hanzi[-1]
                                        if context in self.char_trigram and char in self.char_trigram[context]:
                                            new_score += self.char_trigram[context][char] * self.trigram_weight

                                new_candidates.append((new_score, hanzi + char, pos + 1))
                        else:
                            # Unknown pinyin
                            new_candidates.append((score - 10, hanzi + '?', pos + 1))
                    else:
                        # Check phrase dictionary for multi-character
                        if pinyin_concat in self.phrase_dict:
                            for phrase_hanzi, phrase_score in self.phrase_dict[pinyin_concat][:3]:
                                # Score = current + phrase bonus * length + phrase position score
                                new_score = score + self.phrase_bonus * length + phrase_score * 2.0

                                # Add bigram transition bonus from previous
                                if hanzi:
                                    prev_char = hanzi[-1]
                                    first_char = phrase_hanzi[0]
                                    if prev_char in self.char_bigram and first_char in self.char_bigram[prev_char]:
                                        new_score += self.char_bigram[prev_char][first_char] * self.bigram_weight

                                new_candidates.append((new_score, hanzi + phrase_hanzi, pos + length))

                        # Check learned phrases
                        if pinyin_concat in self.learned_phrases:
                            for phrase_hanzi, freq in self.learned_phrases[pinyin_concat][:2]:
                                new_score = score + self.learned_phrase_bonus * length + math.log(freq + 1) * 0.3

                                if hanzi:
                                    prev_char = hanzi[-1]
                                    first_char = phrase_hanzi[0]
                                    if prev_char in self.char_bigram and first_char in self.char_bigram[prev_char]:
                                        new_score += self.char_bigram[prev_char][first_char] * self.bigram_weight

                                new_candidates.append((new_score, hanzi + phrase_hanzi, pos + length))

            # Merge with finished candidates
            all_candidates = new_candidates + finished_beam

            # Deduplicate by (position, hanzi), keeping highest score
            seen = {}
            for cand in all_candidates:
                key = (cand[2], cand[1])  # (position, hanzi)
                if key not in seen or cand[0] > seen[key][0]:
                    seen[key] = cand

            # Prune to top-k by score
            beam = sorted(seen.values(), key=lambda x: -x[0])[:self.beam_size * 3]

        # Return best completed sequence
        finished = [b for b in beam if b[2] >= n]
        if finished:
            return finished[0][1]
        elif beam:
            return beam[0][1]
        return '?' * n

    def save(self, filepath):
        """Save model to file"""
        print(f"Saving model to {filepath}...")

        model_data = {
            'phrase_dict': self.phrase_dict,
            'pinyin_to_chars': dict(self.pinyin_to_chars),
            'char_unigram': dict(self.char_unigram),
            'char_bigram': {k: dict(v) for k, v in self.char_bigram.items()},
            'char_trigram': {k: dict(v) for k, v in self.char_trigram.items()},
            'valid_syllables': list(self.valid_syllables),
            'learned_phrases': self.learned_phrases,
            'beam_size': self.beam_size,
            'phrase_bonus': self.phrase_bonus,
            'learned_phrase_bonus': self.learned_phrase_bonus,
            'bigram_weight': self.bigram_weight,
            'trigram_weight': self.trigram_weight,
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
        model.char_unigram = defaultdict(float, model_data['char_unigram'])
        model.char_bigram = defaultdict(
            lambda: defaultdict(float),
            {k: defaultdict(float, v) for k, v in model_data['char_bigram'].items()}
        )
        model.char_trigram = defaultdict(
            lambda: defaultdict(float),
            {k: defaultdict(float, v) for k, v in model_data.get('char_trigram', {}).items()}
        )
        model.valid_syllables = set(model_data['valid_syllables'])
        model.learned_phrases = model_data.get('learned_phrases', {})
        model.beam_size = model_data.get('beam_size', 5)
        model.phrase_bonus = model_data.get('phrase_bonus', 3.0)
        model.learned_phrase_bonus = model_data.get('learned_phrase_bonus', 2.0)
        model.bigram_weight = model_data.get('bigram_weight', 1.5)
        model.trigram_weight = model_data.get('trigram_weight', 2.0)

        print(f"Model loaded (phrases={len(model.phrase_dict)}, syllables={len(model.valid_syllables)}, learned={len(model.learned_phrases)})")
        return model


def evaluate(model, test_data, n_samples=200):
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
        print(f"  {match} Input: {pinyin[:60]}...")
        print(f"      Expected:  {expected[:50]}...")
        print(f"      Predicted: {predicted[:50]}...")
        print()

    return exact_match / n_samples, char_correct / char_total


def main():
    import argparse

    parser = argparse.ArgumentParser(description='Train improved phrase-based pinyin model v2')
    parser.add_argument('--dataset', type=str,
                       default='/Users/coolwulf/Documents/GitHub/pastiera/pinyin_dataset.json')
    parser.add_argument('--phrases', type=str,
                       default='/Users/coolwulf/Documents/GitHub/pastiera/app/src/main/assets/common/pinyin/pinyin_phrases.json')
    parser.add_argument('--output', type=str,
                       default='phrase_model_v2.pkl.bz2')
    parser.add_argument('--test', action='store_true')
    parser.add_argument('--beam-size', type=int, default=5)
    args = parser.parse_args()

    if args.test:
        model = ImprovedPhrasePinyinModel.load(args.output)

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
            "ni chi fan le ma",
            "wo men yi qi qu",
            "zhe ge wen ti hen fu za",
            "qing wen xi shou jian zai na li",
            "wo xiang he yi bei ka fei",
            "ta shi wo de hao peng you",
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
    model = ImprovedPhrasePinyinModel()
    model.beam_size = args.beam_size

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
        "jin tian tian qi hen hao",
        "wo shi zhong guo ren",
        "ni chi fan le ma",
    ]
    for pinyin in test_inputs:
        result = model.predict(pinyin)
        print(f"  '{pinyin}' -> '{result}'")


if __name__ == "__main__":
    main()
