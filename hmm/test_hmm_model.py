#!/usr/bin/env python3
"""
Test HMM model for pinyin to hanzi conversion.

Usage:
    python test_hmm_model.py                          # Test with built-in phrases
    python test_hmm_model.py -i                       # Interactive mode
    python test_hmm_model.py --dataset test.json      # Test on dataset
    python test_hmm_model.py --benchmark              # Speed benchmark
"""

import os
import sys
import io
import json
import time
import argparse
from collections import defaultdict

# Import HMM model
from train_hmm_model import PinyinHanziHMM

# ============================================================================
# Test Functions
# ============================================================================

def create_test_data():
    """Create test data with various lengths."""
    return [
        # Short phrases (2-4 hanzi)
        {"pinyin": "ni hao", "hanzi": "你好", "category": "short"},
        {"pinyin": "xie xie", "hanzi": "谢谢", "category": "short"},
        {"pinyin": "zai jian", "hanzi": "再见", "category": "short"},
        {"pinyin": "dui bu qi", "hanzi": "对不起", "category": "short"},
        {"pinyin": "mei guan xi", "hanzi": "没关系", "category": "short"},

        # Medium phrases (5-8 hanzi)
        {"pinyin": "wo shi zhong guo ren", "hanzi": "我是中国人", "category": "medium"},
        {"pinyin": "jin tian tian qi hen hao", "hanzi": "今天天气很好", "category": "medium"},
        {"pinyin": "wo xiang he ka fei", "hanzi": "我想喝咖啡", "category": "medium"},
        {"pinyin": "hen gao xing ren shi ni", "hanzi": "很高兴认识你", "category": "medium"},
        {"pinyin": "zhu ni sheng ri kuai le", "hanzi": "祝你生日快乐", "category": "medium"},

        # Technology terms
        {"pinyin": "ren gong zhi neng", "hanzi": "人工智能", "category": "tech"},
        {"pinyin": "ji qi xue xi", "hanzi": "机器学习", "category": "tech"},
        {"pinyin": "shen du xue xi", "hanzi": "深度学习", "category": "tech"},
        {"pinyin": "da shu ju", "hanzi": "大数据", "category": "tech"},
        {"pinyin": "yun ji suan", "hanzi": "云计算", "category": "tech"},
        {"pinyin": "wu lian wang", "hanzi": "物联网", "category": "tech"},
        {"pinyin": "qu kuai lian", "hanzi": "区块链", "category": "tech"},
        {"pinyin": "zi ran yu yan chu li", "hanzi": "自然语言处理", "category": "tech"},

        # Long sentences (10+ hanzi)
        {"pinyin": "zhong guo shi yi ge wei da de guo jia", "hanzi": "中国是一个伟大的国家", "category": "long"},
        {"pinyin": "wo men yao nu li xue xi mei tian jin bu", "hanzi": "我们要努力学习每天进步", "category": "long"},
        {"pinyin": "ke xue ji shu shi di yi sheng chan li", "hanzi": "科学技术是第一生产力", "category": "long"},
        {"pinyin": "jiao yu shi guo jia fa zhan de ji chu", "hanzi": "教育是国家发展的基础", "category": "long"},
        {"pinyin": "huan jing bao hu shi mei ge ren de ze ren", "hanzi": "环境保护是每个人的责任", "category": "long"},

        # Very long sentences (15+ hanzi)
        {"pinyin": "ren gong zhi neng zheng zai gai bian wo men de sheng huo fang shi", "hanzi": "人工智能正在改变我们的生活方式", "category": "very_long"},
        {"pinyin": "zhong hua ren min gong he guo shi shi jie shang zui da de fa zhan zhong guo jia", "hanzi": "中华人民共和国是世界上最大的发展中国家", "category": "very_long"},
        {"pinyin": "wo men ying gai zhen xi shi jian nu li xue xi tian tian xiang shang", "hanzi": "我们应该珍惜时间努力学习天天向上", "category": "very_long"},

        # Idioms
        {"pinyin": "yi lu ping an", "hanzi": "一路平安", "category": "idiom"},
        {"pinyin": "wan shi ru yi", "hanzi": "万事如意", "category": "idiom"},
        {"pinyin": "xin xiang shi cheng", "hanzi": "心想事成", "category": "idiom"},
        {"pinyin": "ma dao cheng gong", "hanzi": "马到成功", "category": "idiom"},
        {"pinyin": "yi fan feng shun", "hanzi": "一帆风顺", "category": "idiom"},
    ]


def run_tests(model, test_data=None, verbose=True):
    """Run tests and compute metrics."""
    if test_data is None:
        test_data = create_test_data()

    results = []
    category_stats = defaultdict(lambda: {'correct': 0, 'total': 0, 'exact': 0, 'count': 0, 'time': 0})
    length_stats = defaultdict(lambda: {'correct': 0, 'total': 0, 'exact': 0, 'count': 0})

    for sample in test_data:
        pinyin = sample['pinyin']
        expected = sample['hanzi']
        category = sample.get('category', 'unknown')

        # Predict with timing
        start = time.perf_counter()
        predicted = model.predict(pinyin)
        elapsed = (time.perf_counter() - start) * 1000

        # Calculate metrics
        correct = sum(1 for p, e in zip(predicted, expected) if p == e)
        total = len(expected)
        char_acc = correct / total if total > 0 else 0
        exact_match = predicted == expected

        result = {
            'pinyin': pinyin,
            'expected': expected,
            'predicted': predicted,
            'correct_chars': correct,
            'total_chars': total,
            'char_accuracy': char_acc,
            'exact_match': exact_match,
            'time_ms': elapsed,
            'category': category,
        }
        results.append(result)

        # Update stats
        category_stats[category]['correct'] += correct
        category_stats[category]['total'] += total
        category_stats[category]['exact'] += 1 if exact_match else 0
        category_stats[category]['count'] += 1
        category_stats[category]['time'] += elapsed

        # Length stats
        hanzi_len = len(expected)
        if hanzi_len <= 4:
            length_key = "1-4"
        elif hanzi_len <= 8:
            length_key = "5-8"
        elif hanzi_len <= 12:
            length_key = "9-12"
        else:
            length_key = "13+"
        length_stats[length_key]['correct'] += correct
        length_stats[length_key]['total'] += total
        length_stats[length_key]['exact'] += 1 if exact_match else 0
        length_stats[length_key]['count'] += 1

    if verbose:
        print("="*70)
        print("TEST RESULTS")
        print("="*70)

        current_category = None
        for result in results:
            if result['category'] != current_category:
                current_category = result['category']
                print(f"\n--- {current_category.upper()} ---")

            status = "O" if result['exact_match'] else "X"
            print(f"[{status}] {result['pinyin']}")
            print(f"    Expected:  {result['expected']}")
            print(f"    Predicted: {result['predicted']}")
            if not result['exact_match']:
                print(f"    Accuracy:  {result['char_accuracy']*100:.0f}%")

        # Summary by category
        print("\n" + "="*70)
        print("RESULTS BY CATEGORY")
        print("="*70)
        print(f"{'Category':<12} {'Samples':>8} {'Char Acc':>10} {'Exact':>10} {'Avg Time':>10}")
        print("-"*52)

        total_correct = 0
        total_chars = 0
        total_exact = 0
        total_count = 0
        total_time = 0

        for category in sorted(category_stats.keys()):
            stats = category_stats[category]
            char_acc = stats['correct'] / stats['total'] if stats['total'] > 0 else 0
            exact_rate = stats['exact'] / stats['count'] if stats['count'] > 0 else 0
            avg_time = stats['time'] / stats['count'] if stats['count'] > 0 else 0

            print(f"{category:<12} {stats['count']:>8} {char_acc*100:>9.1f}% {exact_rate*100:>9.1f}% {avg_time:>9.2f}ms")

            total_correct += stats['correct']
            total_chars += stats['total']
            total_exact += stats['exact']
            total_count += stats['count']
            total_time += stats['time']

        # Summary by length
        print("\n" + "="*70)
        print("RESULTS BY LENGTH")
        print("="*70)
        print(f"{'Length':<12} {'Samples':>8} {'Char Acc':>10} {'Exact':>10}")
        print("-"*42)

        for length_key in ["1-4", "5-8", "9-12", "13+"]:
            if length_key in length_stats:
                stats = length_stats[length_key]
                char_acc = stats['correct'] / stats['total'] if stats['total'] > 0 else 0
                exact_rate = stats['exact'] / stats['count'] if stats['count'] > 0 else 0
                print(f"{length_key:<12} {stats['count']:>8} {char_acc*100:>9.1f}% {exact_rate*100:>9.1f}%")

        # Overall
        overall_char_acc = total_correct / total_chars if total_chars > 0 else 0
        overall_exact = total_exact / total_count if total_count > 0 else 0
        avg_time = total_time / total_count if total_count > 0 else 0

        print("\n" + "="*70)
        print("OVERALL RESULTS")
        print("="*70)
        print(f"Total samples:      {total_count}")
        print(f"Character accuracy: {overall_char_acc*100:.1f}% ({total_correct}/{total_chars})")
        print(f"Exact matches:      {total_exact}/{total_count} ({overall_exact*100:.1f}%)")
        print(f"Avg inference time: {avg_time:.3f} ms")
        print("="*70)

    return results


def run_benchmark(model, num_runs=100, warmup=10):
    """Run speed benchmark."""
    test_cases = [
        ("Short", "ni hao"),
        ("Medium", "wo shi zhong guo ren"),
        ("Long", "jin tian tian qi hen hao shi he chu qu san bu"),
        ("Very Long", "ren gong zhi neng zheng zai gai bian wo men de sheng huo fang shi he gong zuo fang shi"),
    ]

    print("="*70)
    print("HMM MODEL BENCHMARK")
    print("="*70)
    print(f"Runs: {num_runs}, Warmup: {warmup}")
    print()

    import numpy as np

    print(f"{'Test Case':<12} {'Syllables':>10} {'Mean':>10} {'Std':>10} {'P50':>10} {'P95':>10}")
    print("-"*64)

    for name, pinyin in test_cases:
        # Warmup
        for _ in range(warmup):
            model.predict(pinyin)

        # Benchmark
        times = []
        for _ in range(num_runs):
            start = time.perf_counter()
            model.predict(pinyin)
            times.append((time.perf_counter() - start) * 1000)

        times = np.array(times)
        syllables = len(pinyin.split())
        print(f"{name:<12} {syllables:>10} {np.mean(times):>9.2f}ms {np.std(times):>9.2f}ms "
              f"{np.percentile(times, 50):>9.2f}ms {np.percentile(times, 95):>9.2f}ms")

    print("="*70)


def interactive_mode(model):
    """Interactive testing mode."""
    print("="*70)
    print("INTERACTIVE HMM MODEL TEST")
    print("="*70)
    print("Enter pinyin (space-separated syllables) to convert to hanzi.")
    print("Type 'quit' or 'exit' to stop.\n")

    while True:
        try:
            pinyin = input("Pinyin: ").strip()
            if pinyin.lower() in ('quit', 'exit', 'q'):
                print("Goodbye!")
                break
            if not pinyin:
                continue

            start = time.perf_counter()
            result = model.predict(pinyin)
            elapsed = (time.perf_counter() - start) * 1000

            print(f"Hanzi:  {result}")
            print(f"Time:   {elapsed:.2f} ms\n")

        except KeyboardInterrupt:
            print("\nGoodbye!")
            break
        except Exception as e:
            print(f"Error: {e}\n")


def load_test_dataset(path, max_samples=100):
    """Load test samples from dataset."""
    with open(path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    samples = data.get('data', data)

    # Filter and sample
    valid = [s for s in samples if len(s['pinyin'].split()) == len(s['hanzi'])]

    if len(valid) > max_samples:
        step = len(valid) // max_samples
        valid = [valid[i] for i in range(0, len(valid), step)][:max_samples]

    # Add category by length
    for item in valid:
        hanzi_len = len(item['hanzi'])
        if hanzi_len <= 4:
            item['category'] = 'short'
        elif hanzi_len <= 8:
            item['category'] = 'medium'
        elif hanzi_len <= 12:
            item['category'] = 'long'
        else:
            item['category'] = 'very_long'

    return valid


# ============================================================================
# Main
# ============================================================================

def main():
    # Fix Windows encoding
    if sys.platform == 'win32':
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

    parser = argparse.ArgumentParser(description='Test HMM pinyin model')
    parser.add_argument('--model', '-m', default='hmm_phrase_model',
                        help='Model directory')
    parser.add_argument('--interactive', '-i', action='store_true',
                        help='Interactive mode')
    parser.add_argument('--benchmark', '-b', action='store_true',
                        help='Run speed benchmark')
    parser.add_argument('--dataset', '-d', default=None,
                        help='Test on dataset file')
    parser.add_argument('--samples', '-n', type=int, default=100,
                        help='Number of samples from dataset')

    args = parser.parse_args()

    # Load model
    print(f"Loading model from {args.model}...")
    model = PinyinHanziHMM.load(args.model)

    if args.interactive:
        interactive_mode(model)
    elif args.benchmark:
        run_benchmark(model)
    else:
        if args.dataset:
            test_data = load_test_dataset(args.dataset, args.samples)
            if not test_data:
                print("No valid samples in dataset")
                return
        else:
            test_data = create_test_data()

        run_tests(model, test_data)


if __name__ == "__main__":
    main()
