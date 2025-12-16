"""
Improved HMM Model for Toneless Pinyin to Hanzi conversion.

Improvements over original:
1. Combined training data (pinyin_dataset + pinyin_phrases)
2. Word frequency weighting for initial probabilities
3. Higher transition retention (TOP_K=100)
4. Better smoothing for rare transitions
5. Direct JSON export for Android

Output: hmm_model_improved.json.gz for Android integration
"""

import json
import gzip
import numpy as np
from collections import defaultdict
from tqdm import tqdm
import os
import re


class ImprovedPinyinHMM:
    """Improved HMM model with combined data and better transitions"""

    def __init__(self):
        # Vocabulary mappings
        self.hanzi2id = {}
        self.id2hanzi = {}
        self.pinyin2id = {}
        self.id2pinyin = {}

        # Probability arrays (log space)
        self.log_initial = None
        self.log_transition = None  # Full matrix during training
        self.log_emission = None

        # Sparse representations for export
        self.sparse_trans = {}  # from_id -> {to_id -> log_prob}
        self.sparse_emission = {}  # pinyin_id -> {hanzi_id -> log_prob}

        # Pinyin to candidate hanzi mapping
        self.pinyin_to_hanzi = defaultdict(set)

        # Word frequency data
        self.word_freq = {}

    def load_word_frequency(self, filepath):
        """Load word frequency data for better initial probabilities"""
        print(f"Loading word frequency from {filepath}...")
        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                self.word_freq = json.load(f)
            print(f"  Loaded {len(self.word_freq)} word frequencies")
        except Exception as e:
            print(f"  Warning: Could not load word frequency: {e}")
            self.word_freq = {}

    def load_pinyin_dataset(self, filepath):
        """Load pinyin_dataset.json"""
        print(f"Loading dataset from {filepath}...")
        with open(filepath, 'r', encoding='utf-8') as f:
            dataset = json.load(f)
        data = dataset.get('data', [])
        print(f"  Loaded {len(data)} sentence samples")
        return data

    def load_pinyin_phrases(self, filepath, pinyin_dict_path):
        """Load pinyin_phrases.json and convert to training format"""
        print(f"Loading phrases from {filepath}...")

        # First load pinyin_dict to get syllable list
        with open(pinyin_dict_path, 'r', encoding='utf-8') as f:
            pinyin_dict = json.load(f)

        # Build syllable set for parsing
        syllables = set(pinyin_dict.keys())

        with open(filepath, 'r', encoding='utf-8') as f:
            phrases = json.load(f)

        data = []
        skipped = 0

        for pinyin_concat, hanzi_list in tqdm(phrases.items(), desc="Processing phrases"):
            if not hanzi_list:
                continue

            # Use the first (most common) hanzi
            hanzi = hanzi_list[0]

            # Try to parse concatenated pinyin into syllables
            parsed = self._parse_pinyin(pinyin_concat, syllables)

            if parsed and len(parsed) == len(hanzi):
                data.append({
                    'pinyin': ' '.join(parsed),
                    'hanzi': hanzi
                })
            else:
                skipped += 1

        print(f"  Converted {len(data)} phrase entries (skipped {skipped})")
        return data

    def _parse_pinyin(self, concat, syllables):
        """Parse concatenated pinyin into syllables using greedy longest match"""
        result = []
        remaining = concat.lower()

        while remaining:
            # Try longest match first
            matched = False
            for length in range(min(6, len(remaining)), 0, -1):
                candidate = remaining[:length]
                if candidate in syllables:
                    result.append(candidate)
                    remaining = remaining[length:]
                    matched = True
                    break

            if not matched:
                # No valid syllable found
                return None

        return result

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

        # Create mappings
        self.hanzi2id = {char: i for i, char in enumerate(sorted(hanzi_set))}
        self.id2hanzi = {i: char for char, i in self.hanzi2id.items()}

        self.pinyin2id = {py: i for i, py in enumerate(sorted(pinyin_set))}
        self.id2pinyin = {i: py for py, i in self.pinyin2id.items()}

        print(f"  Hanzi vocabulary: {len(self.hanzi2id)}")
        print(f"  Pinyin vocabulary: {len(self.pinyin2id)}")

    def train(self, data, smoothing=1e-8):
        """Train HMM from data with improved smoothing"""
        n_hanzi = len(self.hanzi2id)
        n_pinyin = len(self.pinyin2id)

        print(f"Training HMM (hanzi={n_hanzi}, pinyin={n_pinyin})...")

        # Count matrices
        initial_counts = np.zeros(n_hanzi) + smoothing
        transition_counts = np.zeros((n_hanzi, n_hanzi)) + smoothing
        emission_counts = np.zeros((n_pinyin, n_hanzi)) + smoothing  # Note: pinyin x hanzi

        # Apply word frequency boost to initial counts
        if self.word_freq:
            print("Applying word frequency weights...")
            for word, freq in self.word_freq.items():
                if word and word[0] in self.hanzi2id:
                    idx = self.hanzi2id[word[0]]
                    initial_counts[idx] += freq * 10  # Boost factor

        # Count occurrences
        valid_samples = 0
        for item in tqdm(data, desc="Counting"):
            pinyin_tokens = item['pinyin'].split()
            hanzi_chars = list(item['hanzi'])

            # Skip if lengths don't match
            if len(pinyin_tokens) != len(hanzi_chars):
                continue

            valid_samples += 1

            for i, (py, hz) in enumerate(zip(pinyin_tokens, hanzi_chars)):
                if hz not in self.hanzi2id or py not in self.pinyin2id:
                    continue

                hz_idx = self.hanzi2id[hz]
                py_idx = self.pinyin2id[py]

                # Record pinyin -> hanzi mapping
                self.pinyin_to_hanzi[py_idx].add(hz_idx)

                # Count emissions (pinyin given hanzi)
                emission_counts[py_idx, hz_idx] += 1

                if i == 0:
                    # Initial state
                    initial_counts[hz_idx] += 1
                else:
                    # Transition from previous character
                    prev_hz = hanzi_chars[i - 1]
                    if prev_hz in self.hanzi2id:
                        prev_idx = self.hanzi2id[prev_hz]
                        transition_counts[prev_idx, hz_idx] += 1

        print(f"  Valid samples: {valid_samples}")

        # Normalize to log probabilities
        print("Normalizing probabilities...")

        self.log_initial = np.log(initial_counts / initial_counts.sum())

        # Row-wise normalization for transitions
        transition_sums = transition_counts.sum(axis=1, keepdims=True)
        self.log_transition = np.log(transition_counts / transition_sums)

        # Column-wise normalization for emissions (P(pinyin | hanzi))
        # We store as sparse: pinyin_id -> {hanzi_id -> log_prob}
        for py_idx in range(n_pinyin):
            col = emission_counts[py_idx, :]
            col_sum = col.sum()
            if col_sum > smoothing * n_hanzi:
                log_probs = np.log(col / col_sum)
                # Only keep hanzi that can produce this pinyin
                hanzi_ids = list(self.pinyin_to_hanzi.get(py_idx, []))
                if hanzi_ids:
                    self.sparse_emission[py_idx] = {
                        hz_id: float(log_probs[hz_id]) for hz_id in hanzi_ids
                    }

        print("Training complete!")

    def create_sparse_transitions(self, top_k=100):
        """Create sparse transition matrix keeping top K transitions per character"""
        print(f"Creating sparse transitions (top_k={top_k})...")

        n_hanzi = len(self.hanzi2id)
        default_log_prob = float(np.min(self.log_transition))

        self.sparse_trans = {}
        self.default_trans = default_log_prob

        for from_idx in tqdm(range(n_hanzi), desc="Sparsifying transitions"):
            row = self.log_transition[from_idx, :]

            # Get top K transitions
            top_indices = np.argsort(row)[-top_k:]

            # Only keep transitions significantly above default
            trans_dict = {}
            for to_idx in top_indices:
                log_prob = float(row[to_idx])
                if log_prob > default_log_prob + 1.0:  # At least e^1 = 2.7x more likely
                    trans_dict[int(to_idx)] = log_prob

            if trans_dict:
                self.sparse_trans[from_idx] = trans_dict

        total_entries = sum(len(v) for v in self.sparse_trans.values())
        print(f"  Sparse transitions: {len(self.sparse_trans)} rows, {total_entries} entries")
        print(f"  Default transition: {default_log_prob:.2f}")

    def viterbi_decode(self, pinyin_sequence):
        """Decode pinyin sequence to hanzi using Viterbi algorithm"""
        if not pinyin_sequence:
            return ""

        T = len(pinyin_sequence)

        # Get candidate hanzi for each position
        candidates = []
        for py in pinyin_sequence:
            py_idx = self.pinyin2id.get(py)
            if py_idx is not None and py_idx in self.pinyin_to_hanzi:
                candidates.append(list(self.pinyin_to_hanzi[py_idx]))
            else:
                # Unknown pinyin
                return "?" * T

        if any(len(c) == 0 for c in candidates):
            return "?" * T

        # Viterbi with pruning
        # V[t] = {state: (log_prob, prev_state)}
        V = [{}]

        # Initialize
        py_idx = self.pinyin2id.get(pinyin_sequence[0], -1)
        emissions = self.sparse_emission.get(py_idx, {})

        for state in candidates[0]:
            init_prob = self.log_initial[state]
            emit_prob = emissions.get(state, -50.0)
            V[0][state] = (init_prob + emit_prob, None)

        # Forward pass
        for t in range(1, T):
            V.append({})
            py_idx = self.pinyin2id.get(pinyin_sequence[t], -1)
            emissions = self.sparse_emission.get(py_idx, {})

            for curr_state in candidates[t]:
                max_prob = float('-inf')
                best_prev = None

                for prev_state, (prev_prob, _) in V[t - 1].items():
                    # Get transition probability
                    trans_dict = self.sparse_trans.get(prev_state, {})
                    trans_prob = trans_dict.get(curr_state, self.default_trans)

                    # Get emission probability
                    emit_prob = emissions.get(curr_state, -50.0)

                    prob = prev_prob + trans_prob + emit_prob
                    if prob > max_prob:
                        max_prob = prob
                        best_prev = prev_state

                if best_prev is not None:
                    V[t][curr_state] = (max_prob, best_prev)

            if not V[t]:
                # No valid paths, use emission only
                for curr_state in candidates[t]:
                    emit_prob = emissions.get(curr_state, -50.0)
                    V[t][curr_state] = (emit_prob, None)

        # Backtrack
        if not V[-1]:
            return "?" * T

        best_final = max(V[-1].keys(), key=lambda s: V[-1][s][0])

        path = [best_final]
        for t in range(T - 1, 0, -1):
            _, prev_state = V[t][path[-1]]
            if prev_state is not None:
                path.append(prev_state)
            else:
                # Handle broken path
                path.append(candidates[t-1][0] if candidates[t-1] else 0)

        path.reverse()

        return ''.join(self.id2hanzi.get(idx, '?') for idx in path)

    def predict(self, pinyin_str):
        """Predict hanzi from space-separated pinyin string"""
        pinyin_sequence = pinyin_str.strip().lower().split()
        return self.viterbi_decode(pinyin_sequence)

    def export_to_json(self, filepath):
        """Export model to JSON format for Android"""
        print(f"Exporting model to {filepath}...")

        model_data = {
            'hanzi2id': self.hanzi2id,
            'id2hanzi': {str(k): v for k, v in self.id2hanzi.items()},
            'pinyin2id': self.pinyin2id,
            'log_initial': [float(x) for x in self.log_initial],
            'default_trans': float(self.default_trans),
            'sparse_trans': {str(k): {str(k2): v2 for k2, v2 in v.items()}
                            for k, v in self.sparse_trans.items()},
            'sparse_emission': {str(k): {str(k2): v2 for k2, v2 in v.items()}
                               for k, v in self.sparse_emission.items()},
            'pinyin_to_hanzi': {str(k): list(v) for k, v in self.pinyin_to_hanzi.items()},
        }

        # Save as gzipped JSON
        json_str = json.dumps(model_data, ensure_ascii=False)

        with gzip.open(filepath, 'wt', encoding='utf-8') as f:
            f.write(json_str)

        size_mb = os.path.getsize(filepath) / (1024 * 1024)
        print(f"  Model saved ({size_mb:.2f} MB)")

        return size_mb


def evaluate(model, test_data, n_samples=200):
    """Evaluate model on test samples"""
    print(f"\nEvaluating on {min(n_samples, len(test_data))} samples...")

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

    n = len(samples)
    print(f"\nResults:")
    print(f"  Exact match: {correct}/{n} = {100*correct/n:.1f}%")
    print(f"  Character accuracy: {char_correct}/{char_total} = {100*char_correct/char_total:.1f}%")

    return char_correct / char_total if char_total > 0 else 0


def main():
    import argparse

    parser = argparse.ArgumentParser(description='Train improved HMM pinyin model')
    parser.add_argument('--dataset', type=str,
                       default='pinyin_dataset.json',
                       help='Path to pinyin_dataset.json')
    parser.add_argument('--phrases', type=str,
                       default='app/src/main/assets/common/pinyin/pinyin_phrases.json',
                       help='Path to pinyin_phrases.json')
    parser.add_argument('--pinyin-dict', type=str,
                       default='app/src/main/assets/common/pinyin/pinyin_dict.json',
                       help='Path to pinyin_dict.json')
    parser.add_argument('--word-freq', type=str,
                       default='app/src/main/assets/common/pinyin/word_frequency.json',
                       help='Path to word_frequency.json')
    parser.add_argument('--output', type=str,
                       default='app/src/main/assets/common/pinyin/hmm_model.dat',
                       help='Output model path')
    parser.add_argument('--top-k', type=int, default=100,
                       help='Top K transitions to keep per character')
    args = parser.parse_args()

    # Create model
    model = ImprovedPinyinHMM()

    # Load word frequency
    model.load_word_frequency(args.word_freq)

    # Load training data
    sentence_data = model.load_pinyin_dataset(args.dataset)
    phrase_data = model.load_pinyin_phrases(args.phrases, args.pinyin_dict)

    # Combine data
    all_data = sentence_data + phrase_data
    print(f"\nTotal training samples: {len(all_data)}")

    # Shuffle and split
    import random
    random.seed(42)
    random.shuffle(all_data)

    split_idx = int(len(all_data) * 0.95)
    train_data = all_data[:split_idx]
    test_data = all_data[split_idx:]

    print(f"Train: {len(train_data)}, Test: {len(test_data)}")

    # Build vocabulary and train
    model.build_vocabulary(train_data)
    model.train(train_data)

    # Create sparse transitions
    model.create_sparse_transitions(top_k=args.top_k)

    # Evaluate before export
    accuracy = evaluate(model, test_data, n_samples=500)

    # Export to JSON
    model.export_to_json(args.output)

    # Quick test
    print("\n" + "=" * 60)
    print("Quick test:")
    test_inputs = [
        "ni hao",
        "wo shi zhong guo ren",
        "jin tian tian qi hen hao",
        "ren gong zhi neng",
        "shen du xue xi",
        "zhong hua ren min gong he guo",
        "wo ai ni",
        "xie xie",
    ]
    for pinyin in test_inputs:
        result = model.predict(pinyin)
        print(f"  '{pinyin}' -> '{result}'")


if __name__ == "__main__":
    main()
