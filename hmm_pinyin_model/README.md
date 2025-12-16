# HMM Pinyin-to-Hanzi Model

A Hidden Markov Model (HMM) for converting toneless Pinyin to Chinese characters (Hanzi).

## Overview

This model uses statistical learning to convert space-separated Pinyin syllables to Chinese characters. It excels at long sentences where neural seq2seq models typically struggle.

## Model Specifications

| Parameter | Value |
|-----------|-------|
| **Model Type** | Hidden Markov Model (HMM) |
| **Algorithm** | Viterbi Decoding |
| **Model Size** | 1.75 MB (compressed) |
| **Hanzi Vocabulary** | 6,939 characters |
| **Pinyin Vocabulary** | 403 syllables |
| **Character Set** | Simplified Chinese |

## Performance

| Metric | Value |
|--------|-------|
| Character Accuracy | 91.1% |
| Exact Match Rate | 71.0% |
| Avg Inference Time | 1.14 ms |

### Accuracy by Sentence Length

| Hanzi Length | Char Accuracy | Exact Match |
|--------------|---------------|-------------|
| 1-4 chars | 85.7% | 70.6% |
| 5-8 chars | 79.4% | 50.0% |
| 9-12 chars | 96.2% | 80.0% |
| 13+ chars | 100% | 100% |

## How It Works

The HMM model uses three probability distributions:

1. **Initial Probabilities**: P(first character)
2. **Transition Probabilities**: P(character_j | character_i)
3. **Emission Probabilities**: P(pinyin_syllable | character)

Decoding uses the **Viterbi algorithm** to find the most likely character sequence given the input Pinyin.

## File Structure

```
hmm_pinyin_model/
├── hmm_model.pkl.bz2   # Compressed model (1.75 MB)
├── config.json         # Model configuration
└── README.md           # This file
```

## Usage

### Python

```python
from train_hmm_model import PinyinHanziHMM

# Load model
model = PinyinHanziHMM.load('hmm_pinyin_model')

# Convert pinyin to hanzi
result = model.predict("ni hao")
print(result)  # 你好

result = model.predict("ren gong zhi neng zheng zai gai bian wo men de sheng huo")
print(result)  # 人工智能正在改变我们的生活
```

### Command Line

```bash
# Interactive mode
python test_hmm_model.py --model hmm_pinyin_model -i

# Test on built-in phrases
python test_hmm_model.py --model hmm_pinyin_model

# Speed benchmark
python test_hmm_model.py --model hmm_pinyin_model --benchmark
```

## Input Format

- **Pinyin**: Space-separated syllables (toneless)
- **Example**: `"wo shi zhong guo ren"` → `"我是中国人"`

Each syllable maps to exactly one Chinese character.

## Examples

| Pinyin | Hanzi |
|--------|-------|
| ni hao | 你好 |
| xie xie | 谢谢 |
| wo shi zhong guo ren | 我是中国人 |
| jin tian tian qi hen hao | 今天天气很好 |
| ren gong zhi neng | 人工智能 |
| shen du xue xi | 深度学习 |
| zhong hua ren min gong he guo | 中华人民共和国 |

## Training Data

- **Total Samples**: 197,269
- **Sources**: Wikipedia, news articles, common phrases
- **Preprocessing**: Traditional→Simplified conversion, punctuation removal

## Advantages over Neural Models

1. **Long Sentence Handling**: 100% accuracy on 13+ character sentences
2. **Fast Inference**: ~1ms per sentence
3. **Small Model Size**: 1.75 MB vs 30-60 MB for neural models
4. **No GPU Required**: Runs efficiently on CPU
5. **Interpretable**: Based on statistical probabilities

## Limitations

1. **Fixed Vocabulary**: Cannot handle characters not in training data
2. **Context Window**: Considers only bigram transitions
3. **Homophone Ambiguity**: May confuse characters with same pronunciation
4. **No Tone Information**: Toneless pinyin increases ambiguity

## Requirements

```
numpy
scipy
```

## License

This model is provided for educational and research purposes.

## References

- [Pinyin2Hanzi-HMM](https://huggingface.co/Duyu/Pinyin2Hanzi-HMM) - Reference implementation
- [Viterbi Algorithm](https://en.wikipedia.org/wiki/Viterbi_algorithm) - Decoding algorithm
