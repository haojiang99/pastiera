#!/usr/bin/env python3
"""
Test script for SMALL BiLSTM Pinyin-to-Hanzi model (.pt format).

Usage:
    # Test with input
    python test_bilstm_small.py --input "nihao"

    # Interactive mode
    python test_bilstm_small.py --interactive

    # Test examples
    python test_bilstm_small.py --examples

    # Custom model path
    python test_bilstm_small.py --model ../output_small/bilstm_pinyin_final.pt --input "woaini"
"""

import argparse
import json
import sys
import time
from pathlib import Path
from typing import List, Tuple

# Fix Windows console encoding
if sys.platform == 'win32':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import torch
import torch.nn as nn
import torch.nn.functional as F

# Special tokens
PAD_IDX = 0
SOS_IDX = 1
EOS_IDX = 2
UNK_IDX = 3


class Vocabulary:
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


class BahdanauAttention(nn.Module):
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
    def __init__(self, vocab_size: int, embed_dim: int, hidden_dim: int,
                 num_layers: int = 2, dropout: float = 0.2):
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
    def __init__(self, vocab_size: int, embed_dim: int, hidden_dim: int,
                 encoder_dim: int, attention_dim: int, num_layers: int = 2,
                 dropout: float = 0.2):
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
        return prediction, hidden, cell


class Seq2SeqModel(nn.Module):
    def __init__(self, encoder: Encoder, decoder: Decoder, device: torch.device):
        super().__init__()
        self.encoder = encoder
        self.decoder = decoder
        self.device = device

    def create_mask(self, src):
        return (src != PAD_IDX).to(src.device)

    @torch.no_grad()
    def greedy_decode(self, src, max_len: int = 64) -> Tuple[List[int], float]:
        self.eval()
        mask = self.create_mask(src)
        encoder_outputs, hidden, cell = self.encoder(src)
        decoder_input = torch.tensor([SOS_IDX], device=self.device)
        outputs = []
        total_score = 0.0

        for _ in range(max_len):
            output, hidden, cell = self.decoder(decoder_input, encoder_outputs, hidden, cell, mask)
            probs = F.softmax(output, dim=-1)
            top1 = output.argmax(1)
            total_score += torch.log(probs[0, top1.item()] + 1e-10).item()

            if top1.item() == EOS_IDX:
                break
            outputs.append(top1.item())
            decoder_input = top1

        return outputs, total_score

    @torch.no_grad()
    def beam_search(self, src, beam_width: int = 3, max_len: int = 64) -> List[Tuple[List[int], float]]:
        self.eval()
        mask = self.create_mask(src)
        encoder_outputs, hidden, cell = self.encoder(src)

        beams = [([SOS_IDX], 0.0, hidden, cell)]
        completed = []

        for _ in range(max_len):
            new_beams = []
            for seq, score, h, c in beams:
                if seq[-1] == EOS_IDX:
                    completed.append((seq[1:], score))
                    continue

                decoder_input = torch.tensor([seq[-1]], device=self.device)
                output, new_h, new_c = self.decoder(decoder_input, encoder_outputs, h, c, mask)
                log_probs = F.log_softmax(output, dim=-1)
                top_probs, top_indices = log_probs.topk(beam_width)

                for prob, idx in zip(top_probs[0], top_indices[0]):
                    new_seq = seq + [idx.item()]
                    new_score = score + prob.item()
                    new_beams.append((new_seq, new_score, new_h.clone(), new_c.clone()))

            new_beams.sort(key=lambda x: x[1], reverse=True)
            beams = new_beams[:beam_width]
            if not beams:
                break

        for seq, score, _, _ in beams:
            if seq[-1] == EOS_IDX:
                completed.append((seq[1:-1], score))
            else:
                completed.append((seq[1:], score))

        completed.sort(key=lambda x: x[1] / max(len(x[0]), 1), reverse=True)
        return completed[:beam_width]


def load_model(model_path: str, device: torch.device):
    model_dir = Path(model_path).parent
    checkpoint = torch.load(model_path, map_location=device, weights_only=False)
    config = checkpoint.get('config', {})

    # Load vocabularies
    pinyin_vocab = Vocabulary.load(str(model_dir / 'pinyin_vocab.json'))
    hanzi_vocab = Vocabulary.load(str(model_dir / 'hanzi_vocab.json'))

    # Get config (with small model defaults)
    embed_dim = config.get('embed_dim', 128)
    hidden_dim = config.get('hidden_dim', 256)
    attention_dim = config.get('attention_dim', 128)
    num_layers = config.get('num_layers', 2)
    dropout = config.get('dropout', 0.2)

    encoder = Encoder(len(pinyin_vocab), embed_dim, hidden_dim, num_layers, dropout)
    decoder = Decoder(len(hanzi_vocab), embed_dim, hidden_dim * 2, hidden_dim * 2,
                      attention_dim, num_layers, dropout)

    model = Seq2SeqModel(encoder, decoder, device)
    model.load_state_dict(checkpoint['model_state_dict'])
    model.to(device)
    model.eval()

    char_level = config.get('char_level_pinyin', True)

    print(f"Model loaded: {model_path}")
    print(f"  Architecture: embed={embed_dim}, hidden={hidden_dim}, layers={num_layers}")
    print(f"  Pinyin vocab: {len(pinyin_vocab)}, Hanzi vocab: {len(hanzi_vocab)}")
    print(f"  Pinyin format: {'character-level' if char_level else 'syllable-level'}")

    total_params = sum(p.numel() for p in model.parameters())
    print(f"  Parameters: {total_params:,} ({total_params * 4 / 1024 / 1024:.1f} MB FP32)")

    return model, pinyin_vocab, hanzi_vocab, config


def predict(model, pinyin_vocab, hanzi_vocab, pinyin_str: str,
            beam_width: int = 1, char_level: bool = True):
    pinyin_str = pinyin_str.lower().strip()
    if char_level:
        tokens = list(pinyin_str.replace(' ', ''))
    else:
        tokens = pinyin_str.split()

    ids = pinyin_vocab.encode(tokens)
    src = torch.tensor([ids], dtype=torch.long, device=model.device)

    if beam_width <= 1:
        output_ids, score = model.greedy_decode(src)
        result = ''.join(hanzi_vocab.decode(output_ids))
        return result, [(result, score)]
    else:
        beams = model.beam_search(src, beam_width=beam_width)
        results = []
        for ids, score in beams:
            text = ''.join(hanzi_vocab.decode(ids))
            results.append((text, score))
        return results[0][0] if results else "", results


def test_examples(model, pinyin_vocab, hanzi_vocab, config, beam_width: int = 3):
    char_level = config.get('char_level_pinyin', True)

    examples = [
        "nihao", "nihaoma", "woaini", "zhongguo", "beijing",
        "shanghai", "xiexieni", "zaijian", "nichifanlema",
        "woshizhongguoren", "jintiantianqihenhao",
        "womenyiqiquchifanba", "zhonghuarenmingongheguo"
    ]

    print("\n" + "=" * 60)
    print("Testing Common Phrases (Small Model)")
    print("=" * 60)

    total_time = 0
    for pinyin in examples:
        start = time.perf_counter()
        result, candidates = predict(model, pinyin_vocab, hanzi_vocab, pinyin,
                                     beam_width, char_level)
        elapsed = (time.perf_counter() - start) * 1000
        total_time += elapsed

        print(f"\n  {pinyin}")
        print(f"  -> {result} ({elapsed:.1f}ms)")
        if beam_width > 1 and len(candidates) > 1:
            for text, _ in candidates[1:3]:
                print(f"     ({text})")

    print(f"\nAverage: {total_time / len(examples):.1f}ms per inference")


def interactive_mode(model, pinyin_vocab, hanzi_vocab, config, beam_width: int = 3):
    char_level = config.get('char_level_pinyin', True)

    print("\n" + "=" * 60)
    print("Interactive Mode (Small Model)")
    print("=" * 60)
    print("Enter pinyin (no spaces) to get predictions.")
    print("Commands: 'quit' to exit, 'beam N' to set beam width")
    print(f"Beam width: {beam_width}")
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
                print("Usage: beam N")
            continue

        start = time.perf_counter()
        result, candidates = predict(model, pinyin_vocab, hanzi_vocab, user_input,
                                     beam_width, char_level)
        elapsed = (time.perf_counter() - start) * 1000

        print(f"\nResult: {result}")
        if beam_width > 1 and len(candidates) > 1:
            print("Candidates:")
            for i, (text, score) in enumerate(candidates[:5], 1):
                print(f"  {i}. {text} ({score:.2f})")
        print(f"Time: {elapsed:.1f}ms\n")


def main():
    parser = argparse.ArgumentParser(description='Test Small BiLSTM Pinyin model')
    parser.add_argument('--model', type=str, default='../output_small/bilstm_pinyin_final.pt',
                       help='Path to trained model')
    parser.add_argument('--input', type=str, default=None,
                       help='Pinyin input to test')
    parser.add_argument('--interactive', action='store_true',
                       help='Interactive mode')
    parser.add_argument('--examples', action='store_true',
                       help='Test examples')
    parser.add_argument('--beam-width', type=int, default=3,
                       help='Beam search width')
    parser.add_argument('--device', type=str, default='auto',
                       help='Device: auto, cuda, cpu')
    args = parser.parse_args()

    if args.device == 'auto':
        device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    else:
        device = torch.device(args.device)
    print(f"Device: {device}")

    model, pinyin_vocab, hanzi_vocab, config = load_model(args.model, device)
    char_level = config.get('char_level_pinyin', True)

    if args.input:
        start = time.perf_counter()
        result, candidates = predict(model, pinyin_vocab, hanzi_vocab, args.input,
                                     args.beam_width, char_level)
        elapsed = (time.perf_counter() - start) * 1000

        print(f"\nInput:  {args.input}")
        print(f"Output: {result}")
        print(f"Time:   {elapsed:.1f}ms")
        if args.beam_width > 1:
            print("\nCandidates:")
            for i, (text, score) in enumerate(candidates, 1):
                print(f"  {i}. {text} ({score:.2f})")

    elif args.interactive:
        interactive_mode(model, pinyin_vocab, hanzi_vocab, config, args.beam_width)

    elif args.examples:
        test_examples(model, pinyin_vocab, hanzi_vocab, config, args.beam_width)

    else:
        test_examples(model, pinyin_vocab, hanzi_vocab, config, args.beam_width)
        interactive_mode(model, pinyin_vocab, hanzi_vocab, config, args.beam_width)


if __name__ == '__main__':
    main()
