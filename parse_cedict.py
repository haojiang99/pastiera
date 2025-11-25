#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Parse CC-CEDICT and merge with existing Pinyin dictionary.
"""

import json
import re
import gzip
from collections import defaultdict

def strip_tones(pinyin):
    """Remove tone numbers from pinyin."""
    return re.sub(r'[0-9]', '', pinyin)

def parse_cedict_line(line):
    """Parse a single CC-CEDICT entry line.
    Format: Traditional Simplified [pinyin] /definition/
    Returns: (simplified, pinyin_no_tones, char_count) or None
    """
    if line.startswith('#') or not line.strip():
        return None

    # Match pattern: word word [pinyin] /definition/
    match = re.match(r'^(\S+)\s+(\S+)\s+\[([^\]]+)\]', line)
    if not match:
        return None

    traditional = match.group(1)
    simplified = match.group(2)
    pinyin = match.group(3)

    # Strip tone numbers
    pinyin_clean = strip_tones(pinyin).lower().replace(' ', '')

    # Count characters (excluding non-Chinese)
    char_count = len([c for c in simplified if '\u4e00' <= c <= '\u9fff'])

    return (simplified, pinyin_clean, char_count)

def load_existing_dict(filepath):
    """Load existing JSON dictionary."""
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            return json.load(f)
    except FileNotFoundError:
        return {}

def merge_dictionaries(existing, new_data):
    """Merge new data into existing dictionary, preserving existing entries."""
    for key, values in new_data.items():
        if key in existing:
            # Merge lists, preserving order and removing duplicates
            existing_set = set(existing[key])
            for val in values:
                if val not in existing_set:
                    existing[key].append(val)
                    existing_set.add(val)
        else:
            existing[key] = values
    return existing

def main():
    print("Parsing CC-CEDICT...")

    # Dictionaries to store parsed data
    char_dict = defaultdict(list)  # pinyin -> [characters]
    phrase_dict = defaultdict(list)  # pinyin -> [phrases]

    # Parse CC-CEDICT
    with gzip.open('cedict_1_0_ts_utf-8_mdbg.txt.gz', 'rt', encoding='utf-8') as f:
        for i, line in enumerate(f):
            if (i + 1) % 10000 == 0:
                print(f"Processed {i + 1} lines...")

            result = parse_cedict_line(line)
            if not result:
                continue

            simplified, pinyin, char_count = result

            # Single character -> char_dict
            if char_count == 1:
                if simplified not in char_dict[pinyin]:
                    char_dict[pinyin].append(simplified)
            # Multi-character -> phrase_dict
            elif char_count > 1:
                if simplified not in phrase_dict[pinyin]:
                    phrase_dict[pinyin].append(simplified)

    print(f"\nParsed {len(char_dict)} unique pinyin syllables")
    print(f"Parsed {len(phrase_dict)} unique phrase pinyin combinations")

    # Load existing dictionaries
    print("\nLoading existing dictionaries...")
    existing_chars = load_existing_dict('app/src/main/assets/common/pinyin/pinyin_dict.json')
    existing_phrases = load_existing_dict('app/src/main/assets/common/pinyin/pinyin_phrases.json')

    print(f"Existing chars dict: {len(existing_chars)} entries")
    print(f"Existing phrases dict: {len(existing_phrases)} entries")

    # Merge dictionaries
    print("\nMerging dictionaries...")
    merged_chars = merge_dictionaries(existing_chars.copy(), dict(char_dict))
    merged_phrases = merge_dictionaries(existing_phrases.copy(), dict(phrase_dict))

    print(f"Merged chars dict: {len(merged_chars)} entries")
    print(f"Merged phrases dict: {len(merged_phrases)} entries")

    # Save merged dictionaries
    print("\nSaving merged dictionaries...")
    with open('app/src/main/assets/common/pinyin/pinyin_dict.json', 'w', encoding='utf-8') as f:
        json.dump(merged_chars, f, ensure_ascii=False, separators=(',', ':'))

    with open('app/src/main/assets/common/pinyin/pinyin_phrases.json', 'w', encoding='utf-8') as f:
        json.dump(merged_phrases, f, ensure_ascii=False, separators=(',', ':'))

    print("\nDone! Dictionaries updated successfully.")
    print(f"Characters: {sum(len(v) for v in merged_chars.values())} total entries")
    print(f"Phrases: {sum(len(v) for v in merged_phrases.values())} total entries")

if __name__ == '__main__':
    main()
