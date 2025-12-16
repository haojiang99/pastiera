#!/usr/bin/env python3
"""
Test script for BiLSTM Pinyin-to-Hanzi model.

Features:
- Interactive mode: Type pinyin and see predictions
- Batch evaluation: Test on dataset with metrics
- Beam search decoding for better results
- Character and sentence-level accuracy

Usage:
    # Interactive testing
    python test_bilstm_model.py --model output/bilstm_pinyin_final.pt --interactive

    # Evaluate on dataset
    python test_bilstm_model.py --model output/bilstm_pinyin_final.pt --dataset ../pinyin_dataset.json

    # Test specific sentences
    python test_bilstm_model.py --model output/bilstm_pinyin_final.pt --input "ni hao shi jie"
"""

import argparse
import json
import sys
import time
from typing import List, Dict, Tuple, Optional
from pathlib import Path

# Fix Windows console encoding for Chinese characters
if sys.platform == 'win32':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import torch
import torch.nn as nn
import torch.nn.functional as F

# Special tokens
PAD_TOKEN = '<PAD>'
SOS_TOKEN = '<SOS>'
EOS_TOKEN = '<EOS>'
UNK_TOKEN = '<UNK>'

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
            token = self.idx2token.get(i, UNK_TOKEN)
            if skip_special and token == EOS_TOKEN:
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


class BahdanauAttention(nn.Module):
    """Bahdanau attention mechanism."""

    def __init__(self, encoder_dim: int, decoder_dim: int, attention_dim: int):
        super().__init__()
        self.encoder_att = nn.Linear(encoder_dim, attention_dim)
        self.decoder_att = nn.Linear(decoder_dim, attention_dim)
        self.full_att = nn.Linear(attention_dim, 1)

    def forward(self, encoder_outputs, decoder_hidden, mask=None):
        att1 = self.encoder_att(encoder_outputs)
        att2 = self.decoder_att(decoder_hidden).unsqueeze(1)
        att = torch.tanh(att1 + att2)
        scores = self.full_att(att).squeeze(-1)

        if mask is not None:
            scores = scores.masked_fill(mask == 0, float('-inf'))

        weights = F.softmax(scores, dim=-1)
        context = torch.bmm(weights.unsqueeze(1), encoder_outputs).squeeze(1)
        return context, weights


class Encoder(nn.Module):
    """Bidirectional LSTM encoder."""

    def __init__(self, vocab_size: int, embed_dim: int, hidden_dim: int,
                 num_layers: int = 2, dropout: float = 0.3):
        super().__init__()
        self.embedding = nn.Embedding(vocab_size, embed_dim, padding_idx=PAD_IDX)
        self.layer_norm = nn.LayerNorm(embed_dim)
        self.lstm = nn.LSTM(embed_dim, hidden_dim, num_layers=num_layers,
                           batch_first=True, bidirectional=True,
                           dropout=dropout if num_layers > 1 else 0)
        self.output_norm = nn.LayerNorm(hidden_dim * 2)
        self.dropout = nn.Dropout(dropout)
        self.hidden_dim = hidden_dim
        self.num_layers = num_layers

    def forward(self, x, lengths=None):
        embedded = self.dropout(self.layer_norm(self.embedding(x)))
        outputs, (hidden, cell) = self.lstm(embedded)
        outputs = self.output_norm(outputs)

        batch_size = x.size(0)
        hidden = hidden.view(self.num_layers, 2, batch_size, self.hidden_dim)
        hidden = torch.cat([hidden[:, 0, :, :], hidden[:, 1, :, :]], dim=-1)

        cell = cell.view(self.num_layers, 2, batch_size, self.hidden_dim)
        cell = torch.cat([cell[:, 0, :, :], cell[:, 1, :, :]], dim=-1)

        return outputs, hidden, cell


class Decoder(nn.Module):
    """LSTM decoder with attention."""

    def __init__(self, vocab_size: int, embed_dim: int, hidden_dim: int,
                 encoder_dim: int, attention_dim: int, num_layers: int = 2,
                 dropout: float = 0.3):
        super().__init__()
        self.vocab_size = vocab_size
        self.embedding = nn.Embedding(vocab_size, embed_dim, padding_idx=PAD_IDX)
        self.layer_norm = nn.LayerNorm(embed_dim)
        self.attention = BahdanauAttention(encoder_dim, hidden_dim, attention_dim)
        self.lstm = nn.LSTM(embed_dim + encoder_dim, hidden_dim, num_layers=num_layers,
                           batch_first=True, dropout=dropout if num_layers > 1 else 0)
        self.fc1 = nn.Linear(hidden_dim + encoder_dim, hidden_dim)
        self.fc2 = nn.Linear(hidden_dim, vocab_size)
        self.dropout = nn.Dropout(dropout)

    def forward(self, input_token, encoder_outputs, hidden, cell, mask=None):
        embedded = self.dropout(self.layer_norm(self.embedding(input_token.unsqueeze(1))))
        context, attn_weights = self.attention(encoder_outputs, hidden[-1], mask)
        lstm_input = torch.cat([embedded, context.unsqueeze(1)], dim=-1)
        output, (hidden, cell) = self.lstm(lstm_input, (hidden, cell))
        combined = torch.cat([output.squeeze(1), context], dim=-1)
        combined = self.dropout(F.relu(self.fc1(combined)))
        prediction = self.fc2(combined)
        return prediction, hidden, cell, attn_weights


class Seq2SeqModel(nn.Module):
    """Complete Seq2Seq model."""

    def __init__(self, encoder: Encoder, decoder: Decoder, device: torch.device):
        super().__init__()
        self.encoder = encoder
        self.decoder = decoder
        self.device = device

    def create_mask(self, src):
        return (src != PAD_IDX).to(src.device)

    @torch.no_grad()
    def greedy_decode(self, src, max_len: int = 64) -> Tuple[List[int], List[torch.Tensor]]:
        """Greedy decoding."""
        self.eval()
        mask = self.create_mask(src)
        encoder_outputs, hidden, cell = self.encoder(src)

        decoder_input = torch.tensor([SOS_IDX], device=self.device)
        outputs = []
        attentions = []

        for _ in range(max_len):
            output, hidden, cell, attn = self.decoder(decoder_input, encoder_outputs, hidden, cell, mask)
            top1 = output.argmax(1)
            outputs.append(top1.item())
            attentions.append(attn.squeeze(0))

            if top1.item() == EOS_IDX:
                break

            decoder_input = top1

        return outputs, attentions

    @torch.no_grad()
    def beam_search(self, src, beam_width: int = 5, max_len: int = 64) -> List[Tuple[List[int], float]]:
        """Beam search decoding for better results."""
        self.eval()
        mask = self.create_mask(src)
        encoder_outputs, hidden, cell = self.encoder(src)

        # Initialize beams: (sequence, score, hidden, cell)
        beams = [([SOS_IDX], 0.0, hidden, cell)]
        completed = []

        for _ in range(max_len):
            new_beams = []

            for seq, score, h, c in beams:
                if seq[-1] == EOS_IDX:
                    completed.append((seq[1:], score))  # Remove SOS
                    continue

                decoder_input = torch.tensor([seq[-1]], device=self.device)
                output, new_h, new_c, _ = self.decoder(decoder_input, encoder_outputs, h, c, mask)

                log_probs = F.log_softmax(output, dim=-1)
                top_probs, top_indices = log_probs.topk(beam_width)

                for prob, idx in zip(top_probs[0], top_indices[0]):
                    new_seq = seq + [idx.item()]
                    new_score = score + prob.item()
                    new_beams.append((new_seq, new_score, new_h, new_c))

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

        # Sort by score (normalized by length)
        completed.sort(key=lambda x: x[1] / max(len(x[0]), 1), reverse=True)
        return completed[:beam_width]


def load_model(model_path: str, device: torch.device) -> Tuple[Seq2SeqModel, Vocabulary, Vocabulary, dict]:
    """Load trained model and vocabularies."""
    model_dir = Path(model_path).parent

    # Load checkpoint
    checkpoint = torch.load(model_path, map_location=device)
    config = checkpoint.get('config', {})

    # Load vocabularies
    pinyin_vocab_path = model_dir / 'pinyin_vocab.json'
    hanzi_vocab_path = model_dir / 'hanzi_vocab.json'

    if not pinyin_vocab_path.exists():
        # Try alternative paths
        pinyin_vocab_path = model_dir / 'bilstm_pinyin_pinyin_vocab.json'
        hanzi_vocab_path = model_dir / 'bilstm_pinyin_hanzi_vocab.json'

    pinyin_vocab = Vocabulary.load(str(pinyin_vocab_path))
    hanzi_vocab = Vocabulary.load(str(hanzi_vocab_path))

    # Get model config
    embed_dim = config.get('embed_dim', 256)
    hidden_dim = config.get('hidden_dim', 512)
    attention_dim = config.get('attention_dim', 256)
    num_layers = config.get('num_layers', 3)
    dropout = config.get('dropout', 0.3)

    # Create model
    encoder = Encoder(
        vocab_size=len(pinyin_vocab),
        embed_dim=embed_dim,
        hidden_dim=hidden_dim,
        num_layers=num_layers,
        dropout=dropout
    )

    decoder = Decoder(
        vocab_size=len(hanzi_vocab),
        embed_dim=embed_dim,
        hidden_dim=hidden_dim * 2,
        encoder_dim=hidden_dim * 2,
        attention_dim=attention_dim,
        num_layers=num_layers,
        dropout=dropout
    )

    model = Seq2SeqModel(encoder, decoder, device)
    model.load_state_dict(checkpoint['model_state_dict'])
    model.to(device)
    model.eval()

    print(f"Model loaded from {model_path}")
    print(f"  Pinyin vocab: {len(pinyin_vocab)} tokens")
    print(f"  Hanzi vocab: {len(hanzi_vocab)} tokens")
    print(f"  Config: embed={embed_dim}, hidden={hidden_dim}, layers={num_layers}")

    return model, pinyin_vocab, hanzi_vocab, config


def predict(model: Seq2SeqModel, pinyin_vocab: Vocabulary, hanzi_vocab: Vocabulary,
            pinyin_str: str, beam_width: int = 1,
            char_level_pinyin: bool = False) -> Tuple[str, List[Tuple[str, float]]]:
    """Predict Chinese characters from pinyin string."""
    # Tokenize pinyin based on format
    pinyin_str = pinyin_str.lower().strip()
    if char_level_pinyin:
        # Character-level: each character is a token (remove spaces if any)
        pinyin_tokens = list(pinyin_str.replace(' ', ''))
    else:
        # Syllable-level: split by space
        pinyin_tokens = pinyin_str.split()
    pinyin_ids = pinyin_vocab.encode(pinyin_tokens)

    # Create tensor
    src = torch.tensor([pinyin_ids], dtype=torch.long, device=model.device)

    if beam_width <= 1:
        # Greedy decoding
        output_ids, _ = model.greedy_decode(src)
        output_ids = [i for i in output_ids if i not in [PAD_IDX, SOS_IDX, EOS_IDX]]
        result = ''.join(hanzi_vocab.decode(output_ids))
        return result, [(result, 0.0)]
    else:
        # Beam search
        beams = model.beam_search(src, beam_width=beam_width)
        results = []
        for ids, score in beams:
            ids = [i for i in ids if i not in [PAD_IDX, SOS_IDX, EOS_IDX]]
            text = ''.join(hanzi_vocab.decode(ids))
            results.append((text, score))
        return results[0][0] if results else "", results


def calculate_metrics(predictions: List[str], targets: List[str]) -> Dict[str, float]:
    """Calculate evaluation metrics."""
    total_chars = 0
    correct_chars = 0
    total_sentences = len(predictions)
    correct_sentences = 0

    for pred, target in zip(predictions, targets):
        # Sentence-level accuracy
        if pred == target:
            correct_sentences += 1

        # Character-level accuracy
        min_len = min(len(pred), len(target))
        for i in range(min_len):
            total_chars += 1
            if pred[i] == target[i]:
                correct_chars += 1
        total_chars += abs(len(pred) - len(target))

    return {
        'char_accuracy': correct_chars / total_chars if total_chars > 0 else 0,
        'sentence_accuracy': correct_sentences / total_sentences if total_sentences > 0 else 0,
        'total_chars': total_chars,
        'correct_chars': correct_chars,
        'total_sentences': total_sentences,
        'correct_sentences': correct_sentences
    }


def evaluate_dataset(model: Seq2SeqModel, pinyin_vocab: Vocabulary, hanzi_vocab: Vocabulary,
                     dataset_path: str, num_samples: int = 1000, beam_width: int = 1,
                     show_examples: int = 10, char_level_pinyin: bool = False) -> Dict[str, float]:
    """Evaluate model on dataset."""
    print(f"\nEvaluating on {dataset_path}...")
    print(f"Samples: {num_samples}, Beam width: {beam_width}")
    print(f"Pinyin format: {'character-level' if char_level_pinyin else 'syllable-level'}")

    with open(dataset_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    samples = data['data'][:num_samples]

    predictions = []
    targets = []
    examples = []

    start_time = time.time()

    for i, sample in enumerate(samples):
        pinyin = sample['pinyin']
        target = sample['hanzi']

        pred, _ = predict(model, pinyin_vocab, hanzi_vocab, pinyin, beam_width=beam_width,
                         char_level_pinyin=char_level_pinyin)

        predictions.append(pred)
        targets.append(target)

        if len(examples) < show_examples:
            examples.append({
                'pinyin': pinyin,
                'target': target,
                'prediction': pred,
                'correct': pred == target
            })

        if (i + 1) % 100 == 0:
            print(f"  Processed {i + 1}/{len(samples)} samples...")

    elapsed = time.time() - start_time
    metrics = calculate_metrics(predictions, targets)

    print(f"\n{'='*60}")
    print("Evaluation Results")
    print('='*60)
    print(f"Character Accuracy: {metrics['char_accuracy']*100:.2f}%")
    print(f"Sentence Accuracy:  {metrics['sentence_accuracy']*100:.2f}%")
    print(f"Total characters:   {metrics['total_chars']}")
    print(f"Correct characters: {metrics['correct_chars']}")
    print(f"Total sentences:    {metrics['total_sentences']}")
    print(f"Correct sentences:  {metrics['correct_sentences']}")
    print(f"Time elapsed:       {elapsed:.1f}s ({len(samples)/elapsed:.1f} samples/sec)")

    print(f"\n{'='*60}")
    print("Example Predictions")
    print('='*60)
    for ex in examples:
        status = "✓" if ex['correct'] else "✗"
        print(f"\n[{status}] Pinyin: {ex['pinyin']}")
        print(f"    Target:     {ex['target']}")
        print(f"    Prediction: {ex['prediction']}")

    return metrics


def interactive_mode(model: Seq2SeqModel, pinyin_vocab: Vocabulary, hanzi_vocab: Vocabulary,
                     beam_width: int = 5, char_level_pinyin: bool = False):
    """Interactive testing mode."""
    print("\n" + "="*60)
    print("Interactive Mode")
    print("="*60)
    if char_level_pinyin:
        print("Enter pinyin (continuous, no spaces) to get predictions.")
        print("Example: nihao, woaini, zhongguo")
    else:
        print("Enter pinyin (space-separated) to get predictions.")
        print("Example: ni hao, wo ai ni, zhong guo")
    print("Commands: 'quit' to exit, 'beam N' to set beam width")
    print(f"Current beam width: {beam_width}")
    print(f"Pinyin format: {'character-level' if char_level_pinyin else 'syllable-level'}")
    print("="*60 + "\n")

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

        start_time = time.time()
        result, all_results = predict(model, pinyin_vocab, hanzi_vocab, user_input, beam_width=beam_width,
                                      char_level_pinyin=char_level_pinyin)
        elapsed = (time.time() - start_time) * 1000

        print(f"\nResult: {result}")
        if beam_width > 1 and len(all_results) > 1:
            print("All candidates:")
            for i, (text, score) in enumerate(all_results[:5], 1):
                print(f"  {i}. {text} (score: {score:.2f})")
        print(f"Time: {elapsed:.1f}ms\n")


def test_examples(model: Seq2SeqModel, pinyin_vocab: Vocabulary, hanzi_vocab: Vocabulary,
                  beam_width: int = 3, char_level_pinyin: bool = False):
    """Test with predefined examples."""
    # Examples in syllable format (will be converted if char_level)
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

    # Convert to char-level format if needed
    if char_level_pinyin:
        examples = [ex.replace(' ', '') for ex in examples_syllable]
    else:
        examples = examples_syllable

    print("\n" + "="*60)
    print("Testing Common Phrases")
    print(f"Pinyin format: {'character-level' if char_level_pinyin else 'syllable-level'}")
    print("="*60)

    for pinyin in examples:
        result, all_results = predict(model, pinyin_vocab, hanzi_vocab, pinyin, beam_width=beam_width,
                                      char_level_pinyin=char_level_pinyin)
        print(f"\n  {pinyin}")
        print(f"  -> {result}")
        if beam_width > 1 and len(all_results) > 1:
            for text, score in all_results[1:3]:
                print(f"    ({text})")


def main():
    parser = argparse.ArgumentParser(description='Test BiLSTM Pinyin model')
    parser.add_argument('--model', type=str, default='output/bilstm_pinyin_final.pt',
                       help='Path to trained model')
    parser.add_argument('--dataset', type=str, default=None,
                       help='Path to dataset for evaluation')
    parser.add_argument('--input', type=str, default=None,
                       help='Single pinyin input to test')
    parser.add_argument('--interactive', action='store_true',
                       help='Run interactive mode')
    parser.add_argument('--examples', action='store_true',
                       help='Test with predefined examples')
    parser.add_argument('--beam-width', type=int, default=3,
                       help='Beam search width (1 for greedy)')
    parser.add_argument('--num-samples', type=int, default=1000,
                       help='Number of samples for evaluation')
    parser.add_argument('--device', type=str, default='auto',
                       help='Device: auto, cuda, cpu')
    args = parser.parse_args()

    # Device selection
    if args.device == 'auto':
        device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    else:
        device = torch.device(args.device)
    print(f"Using device: {device}")

    # Load model
    model, pinyin_vocab, hanzi_vocab, config = load_model(args.model, device)

    # Get char_level_pinyin from config
    char_level_pinyin = config.get('char_level_pinyin', False)
    print(f"Pinyin format: {'character-level (IME)' if char_level_pinyin else 'syllable-level'}")

    # Run tests
    if args.input:
        result, all_results = predict(model, pinyin_vocab, hanzi_vocab, args.input, args.beam_width,
                                      char_level_pinyin=char_level_pinyin)
        print(f"\nInput:  {args.input}")
        print(f"Output: {result}")
        if args.beam_width > 1:
            print("\nAll candidates:")
            for i, (text, score) in enumerate(all_results, 1):
                print(f"  {i}. {text} (score: {score:.2f})")

    elif args.dataset:
        evaluate_dataset(model, pinyin_vocab, hanzi_vocab, args.dataset,
                        num_samples=args.num_samples, beam_width=args.beam_width,
                        char_level_pinyin=char_level_pinyin)

    elif args.interactive:
        interactive_mode(model, pinyin_vocab, hanzi_vocab, args.beam_width,
                        char_level_pinyin=char_level_pinyin)

    elif args.examples:
        test_examples(model, pinyin_vocab, hanzi_vocab, args.beam_width,
                     char_level_pinyin=char_level_pinyin)

    else:
        # Default: show examples then enter interactive mode
        test_examples(model, pinyin_vocab, hanzi_vocab, args.beam_width,
                     char_level_pinyin=char_level_pinyin)
        interactive_mode(model, pinyin_vocab, hanzi_vocab, args.beam_width,
                        char_level_pinyin=char_level_pinyin)


if __name__ == '__main__':
    main()
