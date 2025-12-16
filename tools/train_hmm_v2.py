"""
HMM Model V2 for Toneless Pinyin to Hanzi conversion.

Improvements over V1:
1. Higher TOP_K transitions (200 instead of 100)
2. Data augmentation: combine phrases to create synthetic sentences
3. Better smoothing with character frequency weighting
4. Bigram boosting for common character pairs
5. Larger model size for better accuracy

Output: hmm_model.dat for Android integration
"""

import json
import gzip
import numpy as np
from collections import defaultdict, Counter
from tqdm import tqdm
import os
import random


class ImprovedPinyinHMMV2:
    """HMM V2 with data augmentation and better transitions"""

    def __init__(self):
        self.hanzi2id = {}
        self.id2hanzi = {}
        self.pinyin2id = {}
        self.id2pinyin = {}

        self.log_initial = None
        self.log_transition = None
        self.sparse_trans = {}
        self.sparse_emission = {}
        self.pinyin_to_hanzi = defaultdict(set)
        self.default_trans = -30.0

        # For data augmentation
        self.phrase_bank = []  # List of (pinyin_list, hanzi) tuples

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

        with open(pinyin_dict_path, 'r', encoding='utf-8') as f:
            pinyin_dict = json.load(f)
        syllables = set(pinyin_dict.keys())

        with open(filepath, 'r', encoding='utf-8') as f:
            phrases = json.load(f)

        data = []
        skipped = 0

        for pinyin_concat, hanzi_list in tqdm(phrases.items(), desc="Processing phrases"):
            if not hanzi_list:
                continue
            hanzi = hanzi_list[0]
            parsed = self._parse_pinyin(pinyin_concat, syllables)

            if parsed and len(parsed) == len(hanzi):
                data.append({
                    'pinyin': ' '.join(parsed),
                    'hanzi': hanzi
                })
                # Store for augmentation
                self.phrase_bank.append((parsed, hanzi))
            else:
                skipped += 1

        print(f"  Converted {len(data)} phrase entries (skipped {skipped})")
        print(f"  Phrase bank size: {len(self.phrase_bank)}")
        return data

    def _parse_pinyin(self, concat, syllables):
        """Parse concatenated pinyin into syllables"""
        result = []
        remaining = concat.lower()
        while remaining:
            matched = False
            for length in range(min(6, len(remaining)), 0, -1):
                candidate = remaining[:length]
                if candidate in syllables:
                    result.append(candidate)
                    remaining = remaining[length:]
                    matched = True
                    break
            if not matched:
                return None
        return result

    def generate_augmented_data(self, n_samples=100000):
        """Generate synthetic sentences by combining phrases"""
        print(f"Generating {n_samples} augmented samples...")

        augmented = []
        random.seed(42)

        # Group phrases by length for better combinations
        phrases_by_len = defaultdict(list)
        for pinyin_list, hanzi in self.phrase_bank:
            l = len(hanzi)
            if 2 <= l <= 4:  # Focus on 2-4 char phrases
                phrases_by_len[l].append((pinyin_list, hanzi))

        # Generate combinations
        for _ in tqdm(range(n_samples), desc="Augmenting"):
            # Randomly pick 2-4 phrases to combine
            n_phrases = random.randint(2, 4)
            combined_pinyin = []
            combined_hanzi = ""

            for _ in range(n_phrases):
                # Pick a random phrase length category
                length = random.choice([2, 3, 4])
                if phrases_by_len[length]:
                    pinyin_list, hanzi = random.choice(phrases_by_len[length])
                    combined_pinyin.extend(pinyin_list)
                    combined_hanzi += hanzi

            if combined_pinyin and len(combined_hanzi) >= 4:
                augmented.append({
                    'pinyin': ' '.join(combined_pinyin),
                    'hanzi': combined_hanzi
                })

        print(f"  Generated {len(augmented)} augmented samples")
        return augmented

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

        self.hanzi2id = {char: i for i, char in enumerate(sorted(hanzi_set))}
        self.id2hanzi = {i: char for char, i in self.hanzi2id.items()}
        self.pinyin2id = {py: i for i, py in enumerate(sorted(pinyin_set))}
        self.id2pinyin = {i: py for py, i in self.pinyin2id.items()}

        print(f"  Hanzi vocabulary: {len(self.hanzi2id)}")
        print(f"  Pinyin vocabulary: {len(self.pinyin2id)}")

    def train(self, data, smoothing=1e-10):
        """Train HMM with improved counting"""
        n_hanzi = len(self.hanzi2id)
        n_pinyin = len(self.pinyin2id)

        print(f"Training HMM V2 (hanzi={n_hanzi}, pinyin={n_pinyin})...")

        # Count matrices with minimal smoothing
        initial_counts = np.zeros(n_hanzi) + smoothing
        transition_counts = np.zeros((n_hanzi, n_hanzi)) + smoothing
        emission_counts = np.zeros((n_pinyin, n_hanzi)) + smoothing

        # Character frequency for weighting
        char_freq = Counter()
        bigram_freq = Counter()

        valid_samples = 0
        for item in tqdm(data, desc="Counting"):
            pinyin_tokens = item['pinyin'].split()
            hanzi_chars = list(item['hanzi'])

            if len(pinyin_tokens) != len(hanzi_chars):
                continue

            valid_samples += 1

            for i, (py, hz) in enumerate(zip(pinyin_tokens, hanzi_chars)):
                if hz not in self.hanzi2id or py not in self.pinyin2id:
                    continue

                hz_idx = self.hanzi2id[hz]
                py_idx = self.pinyin2id[py]

                self.pinyin_to_hanzi[py_idx].add(hz_idx)
                emission_counts[py_idx, hz_idx] += 1
                char_freq[hz] += 1

                if i == 0:
                    initial_counts[hz_idx] += 1
                else:
                    prev_hz = hanzi_chars[i - 1]
                    if prev_hz in self.hanzi2id:
                        prev_idx = self.hanzi2id[prev_hz]
                        transition_counts[prev_idx, hz_idx] += 1
                        bigram_freq[(prev_hz, hz)] += 1

        print(f"  Valid samples: {valid_samples}")
        print(f"  Unique bigrams: {len(bigram_freq)}")

        # Boost common bigrams
        print("Boosting common bigrams...")
        top_bigrams = bigram_freq.most_common(10000)
        for (prev_hz, hz), freq in top_bigrams:
            if prev_hz in self.hanzi2id and hz in self.hanzi2id:
                prev_idx = self.hanzi2id[prev_hz]
                hz_idx = self.hanzi2id[hz]
                # Add extra weight to common bigrams
                transition_counts[prev_idx, hz_idx] += freq * 0.5

        # Normalize to log probabilities
        print("Normalizing probabilities...")

        self.log_initial = np.log(initial_counts / initial_counts.sum())

        transition_sums = transition_counts.sum(axis=1, keepdims=True)
        self.log_transition = np.log(transition_counts / transition_sums)

        # Build sparse emission
        for py_idx in range(n_pinyin):
            col = emission_counts[py_idx, :]
            col_sum = col.sum()
            if col_sum > smoothing * n_hanzi:
                log_probs = np.log(col / col_sum)
                hanzi_ids = list(self.pinyin_to_hanzi.get(py_idx, []))
                if hanzi_ids:
                    self.sparse_emission[py_idx] = {
                        hz_id: float(log_probs[hz_id]) for hz_id in hanzi_ids
                    }

        print("Training complete!")

    def create_sparse_transitions(self, top_k=200):
        """Create sparse transition matrix with higher TOP_K"""
        print(f"Creating sparse transitions (top_k={top_k})...")

        n_hanzi = len(self.hanzi2id)
        self.default_trans = float(np.percentile(self.log_transition, 5))  # 5th percentile as default

        self.sparse_trans = {}
        total_entries = 0

        for from_idx in tqdm(range(n_hanzi), desc="Sparsifying transitions"):
            row = self.log_transition[from_idx, :]
            top_indices = np.argsort(row)[-top_k:]

            trans_dict = {}
            for to_idx in top_indices:
                log_prob = float(row[to_idx])
                # Keep if significantly above default
                if log_prob > self.default_trans + 0.5:
                    trans_dict[int(to_idx)] = log_prob

            if trans_dict:
                self.sparse_trans[from_idx] = trans_dict
                total_entries += len(trans_dict)

        print(f"  Sparse transitions: {len(self.sparse_trans)} rows, {total_entries} entries")
        print(f"  Average entries per row: {total_entries/len(self.sparse_trans):.1f}")
        print(f"  Default transition: {self.default_trans:.2f}")

    def viterbi_decode(self, pinyin_sequence):
        """Viterbi decoding"""
        if not pinyin_sequence:
            return ""

        T = len(pinyin_sequence)

        candidates = []
        for py in pinyin_sequence:
            py_idx = self.pinyin2id.get(py)
            if py_idx is not None and py_idx in self.pinyin_to_hanzi:
                candidates.append(list(self.pinyin_to_hanzi[py_idx]))
            else:
                return "?" * T

        if any(len(c) == 0 for c in candidates):
            return "?" * T

        V = [{}]

        py_idx = self.pinyin2id.get(pinyin_sequence[0], -1)
        emissions = self.sparse_emission.get(py_idx, {})

        for state in candidates[0]:
            init_prob = self.log_initial[state]
            emit_prob = emissions.get(state, -50.0)
            V[0][state] = (init_prob + emit_prob, None)

        for t in range(1, T):
            V.append({})
            py_idx = self.pinyin2id.get(pinyin_sequence[t], -1)
            emissions = self.sparse_emission.get(py_idx, {})

            for curr_state in candidates[t]:
                max_prob = float('-inf')
                best_prev = None

                for prev_state, (prev_prob, _) in V[t - 1].items():
                    trans_dict = self.sparse_trans.get(prev_state, {})
                    trans_prob = trans_dict.get(curr_state, self.default_trans)
                    emit_prob = emissions.get(curr_state, -50.0)

                    prob = prev_prob + trans_prob + emit_prob
                    if prob > max_prob:
                        max_prob = prob
                        best_prev = prev_state

                if best_prev is not None:
                    V[t][curr_state] = (max_prob, best_prev)

            if not V[t]:
                for curr_state in candidates[t]:
                    emit_prob = emissions.get(curr_state, -50.0)
                    V[t][curr_state] = (emit_prob, None)

        if not V[-1]:
            return "?" * T

        best_final = max(V[-1].keys(), key=lambda s: V[-1][s][0])

        path = [best_final]
        for t in range(T - 1, 0, -1):
            _, prev_state = V[t][path[-1]]
            if prev_state is not None:
                path.append(prev_state)
            else:
                path.append(candidates[t-1][0] if candidates[t-1] else 0)

        path.reverse()
        return ''.join(self.id2hanzi.get(idx, '?') for idx in path)

    def predict(self, pinyin_str):
        """Predict hanzi from space-separated pinyin"""
        pinyin_sequence = pinyin_str.strip().lower().split()
        return self.viterbi_decode(pinyin_sequence)

    def export_to_json(self, filepath):
        """Export model to gzipped JSON"""
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

        json_str = json.dumps(model_data, ensure_ascii=False)

        with gzip.open(filepath, 'wt', encoding='utf-8') as f:
            f.write(json_str)

        size_mb = os.path.getsize(filepath) / (1024 * 1024)
        print(f"  Model saved ({size_mb:.2f} MB)")
        return size_mb


def evaluate(model, test_data, n_samples=500):
    """Evaluate model"""
    print(f"\nEvaluating on {min(n_samples, len(test_data))} samples...")

    correct = 0
    char_correct = 0
    char_total = 0

    samples = test_data[:n_samples]

    for item in tqdm(samples, desc="Evaluating"):
        pinyin = item['pinyin']
        expected = item['hanzi']
        predicted = model.predict(pinyin)

        if predicted == expected:
            correct += 1

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

    parser = argparse.ArgumentParser(description='Train HMM V2 pinyin model')
    parser.add_argument('--dataset', type=str, default='pinyin_dataset.json')
    parser.add_argument('--phrases', type=str, default='app/src/main/assets/common/pinyin/pinyin_phrases.json')
    parser.add_argument('--pinyin-dict', type=str, default='app/src/main/assets/common/pinyin/pinyin_dict.json')
    parser.add_argument('--output', type=str, default='app/src/main/assets/common/pinyin/hmm_model.dat')
    parser.add_argument('--top-k', type=int, default=200, help='Top K transitions per character')
    parser.add_argument('--augment', type=int, default=100000, help='Number of augmented samples')
    args = parser.parse_args()

    model = ImprovedPinyinHMMV2()

    # Load training data
    sentence_data = model.load_pinyin_dataset(args.dataset)
    phrase_data = model.load_pinyin_phrases(args.phrases, args.pinyin_dict)

    # Generate augmented data
    augmented_data = model.generate_augmented_data(args.augment)

    # Combine all data
    all_data = sentence_data + phrase_data + augmented_data
    print(f"\nTotal training samples: {len(all_data)}")

    # Shuffle and split
    random.seed(42)
    random.shuffle(all_data)

    split_idx = int(len(all_data) * 0.95)
    train_data = all_data[:split_idx]
    test_data = all_data[split_idx:]

    print(f"Train: {len(train_data)}, Test: {len(test_data)}")

    # Build and train
    model.build_vocabulary(train_data)
    model.train(train_data)
    model.create_sparse_transitions(top_k=args.top_k)

    # Evaluate
    accuracy = evaluate(model, test_data, n_samples=500)

    # Export
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
        "mei you wen ti",
        "fei chang gan xie",
    ]
    for pinyin in test_inputs:
        result = model.predict(pinyin)
        print(f"  '{pinyin}' -> '{result}'")


if __name__ == "__main__":
    main()
