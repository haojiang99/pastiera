# Somiao Pinyin-to-Hanzi Model

A deep learning model for converting toneless Pinyin to Chinese characters (Hanzi).

## Overview

This model converts character-level Pinyin input (e.g., `"wo shi zhong guo ren"`) to Chinese characters (e.g., `"我是中国人"`). It uses a CBHG (Convolution Bank + Highway + GRU) architecture inspired by the Tacotron speech synthesis model.

## Model Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Input: Pinyin Characters                  │
│                  (e.g., "ni hao" → [n,i, ,h,a,o])           │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Embedding Layer                          │
│              Token Embedding (vocab_size → 512)              │
│            + Positional Embedding (512 positions)            │
│                   + Embedding Scaling                        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                         Prenet                               │
│         Linear(512→256) → LayerNorm → ReLU → Dropout        │
│         Linear(256→256) → LayerNorm → ReLU → Dropout        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      CBHG Module                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              Conv1D Bank (K=16 filters)               │  │
│  │     16 parallel 1D convolutions (kernel 1→16)         │  │
│  │              + Batch Normalization                     │  │
│  └───────────────────────────────────────────────────────┘  │
│                          │                                   │
│                          ▼                                   │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                   Max Pooling                         │  │
│  └───────────────────────────────────────────────────────┘  │
│                          │                                   │
│                          ▼                                   │
│  ┌───────────────────────────────────────────────────────┐  │
│  │               Conv1D Projections                       │  │
│  │      Conv1D + BN → ReLU → Conv1D + BN                 │  │
│  │              + Residual Connection                     │  │
│  └───────────────────────────────────────────────────────┘  │
│                          │                                   │
│                          ▼                                   │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              Highway Network (4 layers)               │  │
│  │           H(x) * T(x) + x * (1 - T(x))               │  │
│  └───────────────────────────────────────────────────────┘  │
│                          │                                   │
│                          ▼                                   │
│  ┌───────────────────────────────────────────────────────┐  │
│  │            Bidirectional GRU (256 units)              │  │
│  │         Forward + Backward outputs summed             │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Output Projection                         │
│            LayerNorm → Linear(256 → vocab_size)             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                 Output: Hanzi Logits                         │
│              (seq_len × hanzi_vocab_size)                    │
└─────────────────────────────────────────────────────────────┘
```

## Model Specifications

| Parameter | Value |
|-----------|-------|
| **Architecture** | CBHG (Convolution Bank + Highway + GRU) |
| **Model Version** | somiao_cbhg_v2 |
| **Input Type** | Character-level toneless Pinyin |
| **Output Type** | Character-level Chinese (Hanzi) |
| **Max Sequence Length** | 512 tokens |

### Hyperparameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| `embed_size` | 512 | Embedding dimension |
| `prenet_size` | 256 | Prenet hidden dimension |
| `num_banks` | 16 | Number of Conv1D bank filters |
| `num_highways` | 4 | Number of highway network layers |
| `dropout` | 0.1 | Dropout rate |
| `pinyin_vocab_size` | 29 | Input vocabulary (a-z + space + pad + unk) |
| `hanzi_vocab_size` | ~7000 | Output vocabulary (Chinese characters) |

### Model Size

| Format | Size | Description |
|--------|------|-------------|
| PyTorch (FP32) | ~61 MB | `best_model.pt` |
| ONNX (FP32) | 60.56 MB | `model_fp32.onnx` - CPU/GPU compatible |
| ONNX (FP16) | 30.31 MB | `model_mobile_fp16.onnx` - **Mobile recommended** |
| ONNX (INT8) | 17.54 MB | `model_int8.onnx` - Requires CUDA |

**Mobile Deployment**: Use `model_mobile_fp16.onnx` for Android/iOS - 50% smaller, works on mobile GPU.

## Training Configuration

| Parameter | Value |
|-----------|-------|
| **Dataset** | Chinese Wikipedia + News (~100K sentences) |
| **Sentence Length** | 10-50 Chinese characters |
| **Optimizer** | AdamW (weight_decay=0.01) |
| **Learning Rate** | OneCycleLR (max_lr=0.002) |
| **Batch Size** | 512 |
| **Epochs** | 50 |
| **Label Smoothing** | 0.1 |
| **Mixed Precision** | FP16 (AMP) |

## File Structure

```
pinyin_model/
├── best_model.pt        # PyTorch model weights
├── model_fp32.onnx      # ONNX model (FP32)
├── model_int8.onnx      # ONNX model (INT8 quantized)
├── vocab_pinyin.txt     # Input vocabulary
├── vocab_hanzi.txt      # Output vocabulary
└── config.json          # Model configuration
```

## Vocabulary

### Input (Pinyin)

The model accepts character-level Pinyin input:
- 26 lowercase letters (a-z)
- Space character for syllable separation
- Special tokens: `<pad>`, `<unk>`

Example: `"ni hao"` → `['n', 'i', ' ', 'h', 'a', 'o']`

### Output (Hanzi)

- ~7000 common Chinese characters
- Special tokens: `<pad>`, `<unk>`, `_` (blank for alignment)

## Usage

### Python (PyTorch)

```python
import torch
from train_somiao_toneless import SomiaoPinyinModel, Vocabulary

# Load model
model = SomiaoPinyinModel(...)
model.load_state_dict(torch.load('pinyin_model/best_model.pt'))
model.eval()

# Load vocabularies
pinyin_vocab = Vocabulary.load('pinyin_model/vocab_pinyin.txt')
hanzi_vocab = Vocabulary.load('pinyin_model/vocab_hanzi.txt')

# Inference
pinyin = "ni hao"
input_ids = pinyin_vocab.encode(list(pinyin))
input_tensor = torch.tensor([input_ids])

with torch.no_grad():
    output_ids = model.predict(input_tensor)

result = ''.join(hanzi_vocab.decode(output_ids[0].tolist()))
print(result)  # 你好
```

### Python (ONNX Runtime)

```python
import numpy as np
import onnxruntime as ort

# Load INT8 model
session = ort.InferenceSession('pinyin_model/model_int8.onnx')

# Prepare input
pinyin = "ni hao"
input_ids = np.array([[vocab.get(c, 1) for c in pinyin]], dtype=np.int64)

# Inference
outputs = session.run(None, {'pinyin_input': input_ids})
logits = outputs[0]
pred_ids = np.argmax(logits, axis=-1)
```

### Command Line

```bash
# Run test suite
python test_model.py

# Interactive mode
python test_model.py -i

# Evaluate on long sentences
python evaluate_long_sentences.py
```

## Limitations

1. **Toneless Input**: The model accepts toneless Pinyin only. Tones are not distinguished.

2. **Character-to-Character Mapping**: Each Pinyin character maps to one output position. The model uses `_` (blank) tokens to handle length mismatches.

3. **Context Dependency**: Accuracy depends on context. Short phrases may have more ambiguity than longer sentences.

4. **Vocabulary Coverage**: Characters not in the training vocabulary will be mapped to `<unk>`.

## Performance

| Metric | Value |
|--------|-------|
| Character Accuracy | ~70-80% |
| Exact Match (short phrases) | ~40-60% |
| Exact Match (long sentences) | ~10-20% |

*Note: Actual performance depends on training duration and data quality.*

## Mobile Deployment

### Android (Kotlin)

```kotlin
// build.gradle
dependencies {
    implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.16.0'
}

// MainActivity.kt
class PinyinConverter(context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open("model_mobile_fp16.onnx").readBytes()
        val options = OrtSession.SessionOptions().apply {
            addNnapi()  // Use NNAPI for GPU acceleration
        }
        session = env.createSession(modelBytes, options)
    }

    fun convert(pinyin: String): LongArray {
        val inputIds = pinyin.map { it.code.toLong() % 29 }.toLongArray()
        val inputTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
        val results = session.run(mapOf("pinyin_input" to inputTensor))
        val output = results[0].value as Array<Array<FloatArray>>
        return output[0].map { it.indices.maxByOrNull { i -> it[i] }!!.toLong() }.toLongArray()
    }
}
```

### iOS (Swift)

```swift
// Podfile
pod 'onnxruntime-objc', '~> 1.16.0'

// PinyinConverter.swift
import onnxruntime_objc

class PinyinConverter {
    private let session: ORTSession

    init() throws {
        let env = try ORTEnv(loggingLevel: .warning)
        let options = try ORTSessionOptions()
        try options.appendCoreMLExecutionProvider()  // Use CoreML for GPU

        let modelPath = Bundle.main.path(forResource: "model_mobile_fp16", ofType: "onnx")!
        session = try ORTSession(env: env, modelPath: modelPath, sessionOptions: options)
    }

    func convert(pinyin: String) throws -> [Int64] {
        let inputIds = pinyin.map { Int64($0.asciiValue! % 29) }
        // Create tensor and run inference...
    }
}
```

### Files to Include in Mobile App

```
assets/
├── model_mobile_fp16.onnx   # 30 MB - Recommended
├── vocab_pinyin.txt         # Input vocabulary
└── vocab_hanzi.txt          # Output vocabulary
```

## References

- [Tacotron: Towards End-to-End Speech Synthesis](https://arxiv.org/abs/1703.10135) - CBHG architecture
- [Somiao Pinyin](https://github.com/pdsuwwz/somiao-pinyin) - Original implementation inspiration
- [Neural Chinese Transliterator](https://github.com/Kyubyong/neural_chinese_transliterator) - Reference architecture

## License

This model is provided for educational and research purposes.
