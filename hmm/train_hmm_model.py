#!/usr/bin/env python3
"""
Train an HMM (Hidden Markov Model) for toneless pinyin to hanzi conversion.

HMM is better for long sentences because:
- No gradient vanishing/exploding issues
- Captures statistical patterns effectively
- Fast inference using Viterbi algorithm
- Model size is controlled by vocabulary

Based on: https://huggingface.co/Duyu/Pinyin2Hanzi-HMM

Usage:
    python train_hmm_model.py
    python train_hmm_model.py --dataset pinyin_dataset.json --output hmm_model
"""

import os
import sys
import io
import json
import pickle
import bz2
import math
import argparse
from collections import defaultdict
from typing import Dict, List, Tuple, Optional

# Fix Windows encoding
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

try:
    import numpy as np
    from scipy import sparse
except ImportError:
    print("Error: numpy/scipy not installed. Run: pip install numpy scipy")
    sys.exit(1)

try:
    from tqdm import tqdm
except ImportError:
    tqdm = lambda x, **kwargs: x

# ============================================================================
# HMM Model for Pinyin to Hanzi
# ============================================================================

class PinyinHanziHMM:
    """
    Hidden Markov Model for Pinyin to Hanzi conversion.

    States: Chinese characters (hanzi)
    Observations: Pinyin syllables

    Uses Viterbi algorithm for decoding.
    """

    def __init__(self):
        # Vocabularies
        self.hanzi2id: Dict[str, int] = {}
        self.id2hanzi: Dict[int, str] = {}
        self.pinyin2id: Dict[str, int] = {}
        self.id2pinyin: Dict[int, str] = {}

        # Probability matrices (in log space for numerical stability)
        self.log_initial: Optional[np.ndarray] = None  # P(first hanzi)
        self.log_transition: Optional[np.ndarray] = None  # P(hanzi_j | hanzi_i)
        self.log_emission: Optional[Dict[int, np.ndarray]] = None  # P(pinyin | hanzi)

        # Pinyin to possible hanzi mapping (for efficiency)
        self.pinyin_to_hanzi: Dict[int, List[int]] = {}

        # Smoothing parameter
        self.smoothing = 1e-10

    def _build_vocab(self, data: List[dict]) -> None:
        """Build vocabulary from training data."""
        print("Building vocabularies...")

        hanzi_set = set()
        pinyin_set = set()

        for item in tqdm(data, desc="Scanning vocab"):
            # Pinyin syllables (space-separated)
            syllables = item['pinyin'].split()
            pinyin_set.update(syllables)

            # Hanzi characters
            hanzi_set.update(item['hanzi'])

        # Build hanzi vocabulary
        self.hanzi2id = {h: i for i, h in enumerate(sorted(hanzi_set))}
        self.id2hanzi = {i: h for h, i in self.hanzi2id.items()}

        # Build pinyin vocabulary
        self.pinyin2id = {p: i for i, p in enumerate(sorted(pinyin_set))}
        self.id2pinyin = {i: p for p, i in self.pinyin2id.items()}

        print(f"  Hanzi vocab: {len(self.hanzi2id)}")
        print(f"  Pinyin vocab: {len(self.pinyin2id)}")

    def _count_statistics(self, data: List[dict]) -> Tuple[np.ndarray, np.ndarray, Dict]:
        """Count statistics for probability estimation."""
        print("Counting statistics...")

        n_hanzi = len(self.hanzi2id)
        n_pinyin = len(self.pinyin2id)

        # Counts
        initial_count = np.zeros(n_hanzi, dtype=np.float64)
        transition_count = np.zeros((n_hanzi, n_hanzi), dtype=np.float64)
        emission_count = defaultdict(lambda: np.zeros(n_hanzi, dtype=np.float64))

        # Pinyin to hanzi mapping
        pinyin_hanzi_map = defaultdict(set)

        for item in tqdm(data, desc="Counting"):
            syllables = item['pinyin'].split()
            hanzi_chars = list(item['hanzi'])

            # Skip if length mismatch (syllables should match hanzi count)
            if len(syllables) != len(hanzi_chars):
                continue

            for i, (syllable, hanzi) in enumerate(zip(syllables, hanzi_chars)):
                if syllable not in self.pinyin2id or hanzi not in self.hanzi2id:
                    continue

                py_id = self.pinyin2id[syllable]
                hz_id = self.hanzi2id[hanzi]

                # Initial state (first character)
                if i == 0:
                    initial_count[hz_id] += 1

                # Transition (from previous character)
                if i > 0:
                    prev_hanzi = hanzi_chars[i - 1]
                    if prev_hanzi in self.hanzi2id:
                        prev_hz_id = self.hanzi2id[prev_hanzi]
                        transition_count[prev_hz_id, hz_id] += 1

                # Emission (pinyin -> hanzi)
                emission_count[py_id][hz_id] += 1

                # Mapping
                pinyin_hanzi_map[py_id].add(hz_id)

        # Convert mapping to lists
        self.pinyin_to_hanzi = {k: list(v) for k, v in pinyin_hanzi_map.items()}

        return initial_count, transition_count, emission_count

    def _compute_probabilities(self, initial_count: np.ndarray,
                                transition_count: np.ndarray,
                                emission_count: Dict) -> None:
        """Compute log probabilities from counts."""
        print("Computing probabilities...")

        n_hanzi = len(self.hanzi2id)

        # Initial probabilities with smoothing
        initial_sum = initial_count.sum() + self.smoothing * n_hanzi
        initial_prob = (initial_count + self.smoothing) / initial_sum
        self.log_initial = np.log(initial_prob + 1e-300)

        # Transition probabilities with smoothing
        transition_sum = transition_count.sum(axis=1, keepdims=True) + self.smoothing * n_hanzi
        transition_prob = (transition_count + self.smoothing) / transition_sum
        self.log_transition = np.log(transition_prob + 1e-300)

        # Emission probabilities (only for observed pinyin-hanzi pairs)
        self.log_emission = {}
        for py_id, counts in emission_count.items():
            total = counts.sum() + self.smoothing * n_hanzi
            prob = (counts + self.smoothing) / total
            self.log_emission[py_id] = np.log(prob + 1e-300)

        print(f"  Initial prob shape: {self.log_initial.shape}")
        print(f"  Transition prob shape: {self.log_transition.shape}")
        print(f"  Emission probs: {len(self.log_emission)} pinyin entries")

    def train(self, data: List[dict]) -> None:
        """Train HMM model from data."""
        print("="*60)
        print("TRAINING HMM MODEL")
        print("="*60)
        print(f"Training samples: {len(data)}")

        # Build vocabularies
        self._build_vocab(data)

        # Count statistics
        initial_count, transition_count, emission_count = self._count_statistics(data)

        # Compute probabilities
        self._compute_probabilities(initial_count, transition_count, emission_count)

        print("Training complete!")

    def viterbi(self, pinyin_syllables: List[str]) -> List[str]:
        """
        Optimized Viterbi algorithm - only considers candidate hanzi for each pinyin.

        Args:
            pinyin_syllables: List of pinyin syllables

        Returns:
            List of hanzi characters
        """
        if not pinyin_syllables:
            return []

        T = len(pinyin_syllables)

        # Get candidate hanzi for each position
        candidates = []
        for syllable in pinyin_syllables:
            if syllable in self.pinyin2id:
                py_id = self.pinyin2id[syllable]
                if py_id in self.pinyin_to_hanzi:
                    candidates.append(self.pinyin_to_hanzi[py_id])
                else:
                    # Fallback to all hanzi
                    candidates.append(list(range(len(self.hanzi2id))))
            else:
                # Unknown pinyin - use all hanzi
                candidates.append(list(range(len(self.hanzi2id))))

        # Initialize Viterbi tables (sparse - only for candidates)
        # viterbi_prob[t] = {hanzi_id: log_prob}
        viterbi_prob = [{} for _ in range(T)]
        backpointer = [{} for _ in range(T)]

        # Initialization step (t=0)
        first_syllable = pinyin_syllables[0]
        first_py_id = self.pinyin2id.get(first_syllable, -1)

        for hz_id in candidates[0]:
            if first_py_id >= 0 and first_py_id in self.log_emission:
                emission = self.log_emission[first_py_id][hz_id]
            else:
                emission = np.log(1.0 / len(self.hanzi2id))
            viterbi_prob[0][hz_id] = self.log_initial[hz_id] + emission
            backpointer[0][hz_id] = -1

        # Recursion step
        for t in range(1, T):
            syllable = pinyin_syllables[t]
            py_id = self.pinyin2id.get(syllable, -1)

            for hz_id in candidates[t]:
                # Get emission probability
                if py_id >= 0 and py_id in self.log_emission:
                    emission = self.log_emission[py_id][hz_id]
                else:
                    emission = np.log(1.0 / len(self.hanzi2id))

                # Find best previous state
                best_prob = -np.inf
                best_prev = -1

                for prev_hz_id, prev_prob in viterbi_prob[t-1].items():
                    trans_prob = self.log_transition[prev_hz_id, hz_id]
                    total_prob = prev_prob + trans_prob
                    if total_prob > best_prob:
                        best_prob = total_prob
                        best_prev = prev_hz_id

                viterbi_prob[t][hz_id] = best_prob + emission
                backpointer[t][hz_id] = best_prev

        # Termination - find best final state
        best_last_state = max(viterbi_prob[T-1], key=viterbi_prob[T-1].get)

        # Backtrack
        best_path = [best_last_state]
        for t in range(T-1, 0, -1):
            best_path.append(backpointer[t][best_path[-1]])
        best_path.reverse()

        # Convert to hanzi
        result = [self.id2hanzi[state_id] for state_id in best_path]

        return result

    def predict(self, pinyin_str: str) -> str:
        """
        Convert pinyin string to hanzi.

        Args:
            pinyin_str: Space-separated pinyin syllables

        Returns:
            Hanzi string
        """
        syllables = pinyin_str.strip().split()
        hanzi_list = self.viterbi(syllables)
        return ''.join(hanzi_list)

    def save(self, model_dir: str, compress: bool = True) -> None:
        """Save model to directory."""
        os.makedirs(model_dir, exist_ok=True)

        model_data = {
            'hanzi2id': self.hanzi2id,
            'id2hanzi': self.id2hanzi,
            'pinyin2id': self.pinyin2id,
            'id2pinyin': self.id2pinyin,
            'log_initial': self.log_initial,
            'log_transition': self.log_transition,
            'log_emission': self.log_emission,
            'pinyin_to_hanzi': self.pinyin_to_hanzi,
        }

        if compress:
            model_path = os.path.join(model_dir, 'hmm_model.pkl.bz2')
            print(f"Saving compressed model to {model_path}...")
            with bz2.open(model_path, 'wb') as f:
                pickle.dump(model_data, f)
        else:
            model_path = os.path.join(model_dir, 'hmm_model.pkl')
            print(f"Saving model to {model_path}...")
            with open(model_path, 'wb') as f:
                pickle.dump(model_data, f)

        # Save config
        config = {
            'model_type': 'hmm',
            'hanzi_vocab_size': len(self.hanzi2id),
            'pinyin_vocab_size': len(self.pinyin2id),
            'compressed': compress,
        }
        config_path = os.path.join(model_dir, 'config.json')
        with open(config_path, 'w', encoding='utf-8') as f:
            json.dump(config, f, indent=2)

        # Print size
        model_size = os.path.getsize(model_path) / (1024 * 1024)
        print(f"Model size: {model_size:.2f} MB")

    @classmethod
    def load(cls, model_dir: str) -> 'PinyinHanziHMM':
        """Load model from directory."""
        model = cls()

        # Try compressed first
        model_path = os.path.join(model_dir, 'hmm_model.pkl.bz2')
        if os.path.exists(model_path):
            print(f"Loading compressed model from {model_path}...")
            with bz2.open(model_path, 'rb') as f:
                model_data = pickle.load(f)
        else:
            model_path = os.path.join(model_dir, 'hmm_model.pkl')
            print(f"Loading model from {model_path}...")
            with open(model_path, 'rb') as f:
                model_data = pickle.load(f)

        model.hanzi2id = model_data['hanzi2id']
        model.id2hanzi = model_data['id2hanzi']
        model.pinyin2id = model_data['pinyin2id']
        model.id2pinyin = model_data['id2pinyin']
        model.log_initial = model_data['log_initial']
        model.log_transition = model_data['log_transition']
        model.log_emission = model_data['log_emission']
        model.pinyin_to_hanzi = model_data['pinyin_to_hanzi']

        print(f"Loaded: {len(model.hanzi2id)} hanzi, {len(model.pinyin2id)} pinyin")

        return model


# ============================================================================
# Data Loading
# ============================================================================

def load_data(dataset_path: str) -> List[dict]:
    """Load dataset from JSON file."""
    print(f"Loading dataset from {dataset_path}...")

    with open(dataset_path, 'r', encoding='utf-8') as f:
        dataset = json.load(f)

    if 'data' in dataset:
        data = dataset['data']
    else:
        data = dataset

    print(f"Loaded {len(data)} samples")

    # Filter valid samples (pinyin syllables should match hanzi count)
    valid_data = []
    for item in data:
        syllables = item['pinyin'].split()
        hanzi_chars = list(item['hanzi'])
        if len(syllables) == len(hanzi_chars):
            valid_data.append(item)

    print(f"Valid samples (syllable count = hanzi count): {len(valid_data)}")

    return valid_data


# ============================================================================
# Main
# ============================================================================

def main():
    parser = argparse.ArgumentParser(description='Train HMM model for pinyin to hanzi')
    parser.add_argument('--dataset', '-d', default='pinyin_dataset.json',
                        help='Path to dataset file')
    parser.add_argument('--output', '-o', default='hmm_model',
                        help='Output directory')
    parser.add_argument('--no-compress', action='store_true',
                        help='Save uncompressed model')
    parser.add_argument('--test', '-t', action='store_true',
                        help='Run test after training')

    args = parser.parse_args()

    # Load data
    data = load_data(args.dataset)

    if not data:
        print("Error: No valid data found!")
        sys.exit(1)

    # Train model
    model = PinyinHanziHMM()
    model.train(data)

    # Save model
    model.save(args.output, compress=not args.no_compress)

    # Test
    if args.test:
        print("\n" + "="*60)
        print("TESTING MODEL")
        print("="*60)

        test_cases = [
            "ni hao",
            "wo shi zhong guo ren",
            "jin tian tian qi hen hao",
            "xie xie ni de bang zhu",
            "ren gong zhi neng",
            "ji qi xue xi",
            "shen du xue xi",
        ]

        for pinyin in test_cases:
            result = model.predict(pinyin)
            print(f"  {pinyin} -> {result}")

    print("\n" + "="*60)
    print("TRAINING COMPLETE")
    print("="*60)
    print(f"Model saved to: {args.output}/")

    # List files
    for fname in os.listdir(args.output):
        fpath = os.path.join(args.output, fname)
        size = os.path.getsize(fpath) / (1024 * 1024)
        print(f"  {fname}: {size:.2f} MB")


if __name__ == "__main__":
    main()
