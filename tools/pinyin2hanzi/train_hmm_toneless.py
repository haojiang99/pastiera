"""
Train a Hidden Markov Model for Toneless Pinyin to Hanzi conversion.

Based on the Duyu/Pinyin2Hanzi-HMM approach, but adapted for toneless pinyin input.
Uses pinyin_dataset.json which contains toneless pinyin-hanzi pairs.

Key concepts:
- States: Chinese characters (Hanzi)
- Observations: Pinyin syllables (toneless)
- Initial probabilities: P(first character)
- Transition probabilities: P(char_j | char_i)
- Emission probabilities: P(pinyin | char)
"""

import json
import pickle
import bz2
import numpy as np
from collections import defaultdict
from tqdm import tqdm
import os


class TonelessPinyinHMM:
    """HMM model for toneless pinyin to hanzi conversion"""

    def __init__(self):
        # Vocabulary mappings
        self.hanzi2idx = {}
        self.idx2hanzi = {}
        self.pinyin2idx = {}
        self.idx2pinyin = {}

        # Probability matrices
        self.initial_prob = None  # P(start with char)
        self.transition_prob = None  # P(char_j | char_i)
        self.emission_prob = None  # P(pinyin | char)

        # Pinyin to possible hanzi mapping (for efficient decoding)
        self.pinyin_to_hanzi = defaultdict(set)

    def build_vocabulary(self, data):
        """Build vocabulary from training data"""
        print("Building vocabulary...")

        hanzi_set = set()
        pinyin_set = set()

        for item in tqdm(data, desc="Scanning vocabulary"):
            pinyin_tokens = item['pinyin'].split()
            hanzi_chars = list(item['hanzi'])

            pinyin_set.update(pinyin_tokens)
            hanzi_set.update(hanzi_chars)

        # Create mappings (reserve 0 for unknown)
        self.hanzi2idx = {'<UNK>': 0}
        self.idx2hanzi = {0: '<UNK>'}
        for i, char in enumerate(sorted(hanzi_set), start=1):
            self.hanzi2idx[char] = i
            self.idx2hanzi[i] = char

        self.pinyin2idx = {'<UNK>': 0}
        self.idx2pinyin = {0: '<UNK>'}
        for i, py in enumerate(sorted(pinyin_set), start=1):
            self.pinyin2idx[py] = i
            self.idx2pinyin[i] = py

        print(f"  Hanzi vocabulary size: {len(self.hanzi2idx)}")
        print(f"  Pinyin vocabulary size: {len(self.pinyin2idx)}")

    def train(self, data, smoothing=1e-6):
        """Train HMM from data"""
        n_hanzi = len(self.hanzi2idx)
        n_pinyin = len(self.pinyin2idx)

        print(f"Training HMM (hanzi={n_hanzi}, pinyin={n_pinyin})...")

        # Count matrices
        initial_counts = np.zeros(n_hanzi) + smoothing
        transition_counts = np.zeros((n_hanzi, n_hanzi)) + smoothing
        emission_counts = np.zeros((n_hanzi, n_pinyin)) + smoothing

        # Count occurrences
        for item in tqdm(data, desc="Counting"):
            pinyin_tokens = item['pinyin'].split()
            hanzi_chars = list(item['hanzi'])

            # Skip if lengths don't match (data quality issue)
            if len(pinyin_tokens) != len(hanzi_chars):
                continue

            for i, (py, hz) in enumerate(zip(pinyin_tokens, hanzi_chars)):
                hz_idx = self.hanzi2idx.get(hz, 0)
                py_idx = self.pinyin2idx.get(py, 0)

                # Record pinyin -> hanzi mapping
                self.pinyin_to_hanzi[py].add(hz)

                # Count emissions
                emission_counts[hz_idx, py_idx] += 1

                if i == 0:
                    # Initial state
                    initial_counts[hz_idx] += 1
                else:
                    # Transition from previous character
                    prev_hz = hanzi_chars[i - 1]
                    prev_idx = self.hanzi2idx.get(prev_hz, 0)
                    transition_counts[prev_idx, hz_idx] += 1

        # Normalize to probabilities (log space for numerical stability)
        print("Normalizing probabilities...")

        self.initial_prob = np.log(initial_counts / initial_counts.sum())

        # Row-wise normalization for transitions
        transition_sums = transition_counts.sum(axis=1, keepdims=True)
        self.transition_prob = np.log(transition_counts / transition_sums)

        # Row-wise normalization for emissions
        emission_sums = emission_counts.sum(axis=1, keepdims=True)
        self.emission_prob = np.log(emission_counts / emission_sums)

        print("Training complete!")

    def viterbi_decode(self, pinyin_sequence):
        """Decode pinyin sequence to hanzi using Viterbi algorithm"""
        if not pinyin_sequence:
            return ""

        n_states = len(self.hanzi2idx)
        T = len(pinyin_sequence)

        # Get candidate hanzi for each pinyin
        candidates = []
        for py in pinyin_sequence:
            if py in self.pinyin_to_hanzi:
                cands = [self.hanzi2idx[hz] for hz in self.pinyin_to_hanzi[py]
                        if hz in self.hanzi2idx]
                if not cands:
                    cands = list(range(n_states))  # Fall back to all states
            else:
                cands = list(range(n_states))  # Unknown pinyin
            candidates.append(cands)

        # Viterbi algorithm with pruning (only consider valid candidates)
        # V[t] = dict mapping state -> (probability, prev_state)
        V = [{}]

        # Initialize
        py_idx = self.pinyin2idx.get(pinyin_sequence[0], 0)
        for state in candidates[0]:
            prob = self.initial_prob[state] + self.emission_prob[state, py_idx]
            V[0][state] = (prob, None)

        # Forward pass
        for t in range(1, T):
            V.append({})
            py_idx = self.pinyin2idx.get(pinyin_sequence[t], 0)

            for curr_state in candidates[t]:
                max_prob = float('-inf')
                best_prev = None

                for prev_state, (prev_prob, _) in V[t-1].items():
                    prob = (prev_prob +
                           self.transition_prob[prev_state, curr_state] +
                           self.emission_prob[curr_state, py_idx])

                    if prob > max_prob:
                        max_prob = prob
                        best_prev = prev_state

                if best_prev is not None:
                    V[t][curr_state] = (max_prob, best_prev)

        # Backtrack to find best path
        if not V[-1]:
            return "?" * T

        # Find best final state
        best_final = max(V[-1].keys(), key=lambda s: V[-1][s][0])

        # Backtrack
        path = [best_final]
        for t in range(T - 1, 0, -1):
            _, prev_state = V[t][path[-1]]
            path.append(prev_state)

        path.reverse()

        # Convert indices to characters
        result = ''.join([self.idx2hanzi[idx] for idx in path])
        return result

    def predict(self, pinyin_str):
        """Predict hanzi from pinyin string"""
        pinyin_sequence = pinyin_str.strip().split()
        return self.viterbi_decode(pinyin_sequence)

    def save(self, filepath):
        """Save model to file"""
        print(f"Saving model to {filepath}...")

        model_data = {
            'hanzi2idx': self.hanzi2idx,
            'idx2hanzi': self.idx2hanzi,
            'pinyin2idx': self.pinyin2idx,
            'idx2pinyin': self.idx2pinyin,
            'initial_prob': self.initial_prob,
            'transition_prob': self.transition_prob,
            'emission_prob': self.emission_prob,
            'pinyin_to_hanzi': dict(self.pinyin_to_hanzi),
        }

        if filepath.endswith('.bz2'):
            with bz2.open(filepath, 'wb') as f:
                pickle.dump(model_data, f)
        else:
            with open(filepath, 'wb') as f:
                pickle.dump(model_data, f)

        # Get file size
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
        model.hanzi2idx = model_data['hanzi2idx']
        model.idx2hanzi = model_data['idx2hanzi']
        model.pinyin2idx = model_data['pinyin2idx']
        model.idx2pinyin = model_data['idx2pinyin']
        model.initial_prob = model_data['initial_prob']
        model.transition_prob = model_data['transition_prob']
        model.emission_prob = model_data['emission_prob']
        model.pinyin_to_hanzi = defaultdict(set, {
            k: set(v) for k, v in model_data['pinyin_to_hanzi'].items()
        })

        print(f"Model loaded (hanzi={len(model.hanzi2idx)}, pinyin={len(model.pinyin2idx)})")
        return model


def load_dataset(filepath):
    """Load pinyin_dataset.json"""
    print(f"Loading dataset from {filepath}...")

    with open(filepath, 'r', encoding='utf-8') as f:
        dataset = json.load(f)

    data = dataset['data']
    print(f"Loaded {len(data)} samples")
    print(f"Metadata: {dataset.get('metadata', {})}")

    return data


def load_phrases_dataset(filepath):
    """Load pinyin_phrases.json and convert to training format"""
    print(f"Loading phrases from {filepath}...")

    with open(filepath, 'r', encoding='utf-8') as f:
        phrases = json.load(f)

    data = []
    for pinyin_concat, hanzi_list in phrases.items():
        # Skip if no hanzi candidates
        if not hanzi_list:
            continue

        # Use the first (most common) hanzi
        hanzi = hanzi_list[0]

        # Try to split concatenated pinyin into syllables
        # This is approximate since we don't have spaces
        # We'll use the hanzi length to guide the split
        if len(hanzi) <= 1:
            # Single character - pinyin is the full string
            data.append({
                'pinyin': pinyin_concat,
                'hanzi': hanzi
            })
        else:
            # Multi-character - we need to split the pinyin
            # For now, skip complex cases or use simple heuristic
            # We'll add these as phrase entries later
            pass

    print(f"Converted {len(data)} phrase entries")
    return data


def evaluate(model, test_data, n_samples=100):
    """Evaluate model on test samples"""
    print(f"\nEvaluating on {n_samples} samples...")

    correct = 0
    char_correct = 0
    char_total = 0

    samples = test_data[:n_samples]

    for item in tqdm(samples, desc="Evaluating"):
        pinyin = item['pinyin']
        expected = item['hanzi']
        predicted = model.predict(pinyin)

        # Exact match
        if predicted == expected:
            correct += 1

        # Character-level accuracy
        min_len = min(len(predicted), len(expected))
        for i in range(min_len):
            if predicted[i] == expected[i]:
                char_correct += 1
        char_total += len(expected)

    print(f"\nResults:")
    print(f"  Exact match accuracy: {correct}/{n_samples} = {100*correct/n_samples:.2f}%")
    print(f"  Character accuracy: {char_correct}/{char_total} = {100*char_correct/char_total:.2f}%")

    # Show some examples
    print("\nSample predictions:")
    for item in test_data[:5]:
        pinyin = item['pinyin']
        expected = item['hanzi']
        predicted = model.predict(pinyin)
        match = "✓" if predicted == expected else "✗"
        print(f"  {match} Input: {pinyin[:50]}...")
        print(f"      Expected:  {expected[:30]}...")
        print(f"      Predicted: {predicted[:30]}...")
        print()


def main():
    import argparse

    parser = argparse.ArgumentParser(description='Train toneless pinyin HMM model')
    parser.add_argument('--dataset', type=str,
                       default='/Users/coolwulf/Documents/GitHub/pastiera/pinyin_dataset.json',
                       help='Path to pinyin_dataset.json')
    parser.add_argument('--output', type=str,
                       default='hmm_toneless.pkl.bz2',
                       help='Output model path')
    parser.add_argument('--test', action='store_true',
                       help='Only run test inference on existing model')
    args = parser.parse_args()

    if args.test:
        # Just test existing model
        model = TonelessPinyinHMM.load(args.output)

        # Test some inputs
        test_inputs = [
            "ni hao",
            "wo ai ni",
            "zhong guo",
            "bei jing",
            "shang hai",
            "jin tian tian qi hen hao",
            "wo shi zhong guo ren",
        ]

        print("\nTest predictions:")
        for pinyin in test_inputs:
            result = model.predict(pinyin)
            print(f"  '{pinyin}' -> '{result}'")

        return

    # Load dataset
    data = load_dataset(args.dataset)

    # Split train/test (95/5)
    split_idx = int(len(data) * 0.95)
    train_data = data[:split_idx]
    test_data = data[split_idx:]

    print(f"Train: {len(train_data)}, Test: {len(test_data)}")

    # Create and train model
    model = TonelessPinyinHMM()
    model.build_vocabulary(train_data)
    model.train(train_data)

    # Save model
    model.save(args.output)

    # Evaluate
    evaluate(model, test_data, n_samples=min(100, len(test_data)))

    # Interactive test
    print("\n" + "="*60)
    print("Quick test:")
    test_inputs = [
        "ni hao",
        "wo ai ni",
        "zhong guo",
        "bei jing",
    ]
    for pinyin in test_inputs:
        result = model.predict(pinyin)
        print(f"  '{pinyin}' -> '{result}'")


if __name__ == "__main__":
    main()
