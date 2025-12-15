# Somiao Pinyin Training

Train a CBHG neural network for toneless pinyin to Chinese character conversion.

## Setup (Windows with CUDA)

1. Install Python 3.10+
2. Install PyTorch with CUDA support:
   ```
   pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121
   ```
3. Install other dependencies:
   ```
   pip install -r requirements.txt
   ```

## Training

```bash
python train_somiao_toneless.py
```

The script will:
- Download the Duyu/Pinyin-Hanzi dataset from HuggingFace (first run only)
- Remove tones from pinyin (e.g., "wo3" -> "wo")
- Train a CBHG seq2seq model
- Export to ONNX format

## Output Files

After training, you'll find in `pinyin_model/`:
- `model.onnx` - The trained model
- `vocab_pinyin.txt` - Input vocabulary
- `vocab_hanzi.txt` - Output vocabulary
- `config.json` - Model configuration

Plus `pinyin_model.zip` containing all files for deployment.

## Configuration

Edit these variables in the script to adjust training:
- `MAX_SAMPLES = 200000` - Number of training samples
- `BATCH_SIZE = 64` - Batch size (increase for 4090)
- `EPOCHS = 15` - Number of epochs
- `EMBED_SIZE = 256` - Embedding dimension
- `NUM_BANKS = 16` - CBHG conv banks

For RTX 4090, you can increase batch size to 128 or 256 for faster training.
