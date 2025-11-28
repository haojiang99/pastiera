#!/usr/bin/env python3
"""
Filter Wubi dictionary to remove characters that may not display correctly on Android devices.

This script removes:
1. Characters from CJK Extension B, C, D, E, F, G (U+20000 and above) - these often show as boxes
2. Characters from CJK Compatibility Ideographs Supplement
3. Any other characters that might not be supported by standard Android fonts

Usage:
    python filter_wubi_dict.py
"""

import json
import os
import re

def is_displayable_cjk(char):
    """
    Check if a CJK character is likely to be displayable on Android.

    Returns True for:
    - CJK Unified Ideographs (U+4E00-U+9FFF) - most common
    - CJK Unified Ideographs Extension A (U+3400-U+4DBF) - less common but usually supported
    - CJK Compatibility Ideographs (U+F900-U+FAFF)
    - Some punctuation and symbols

    Returns False for:
    - CJK Extension B (U+20000-U+2A6DF) - rarely supported
    - CJK Extension C (U+2A700-U+2B73F)
    - CJK Extension D (U+2B740-U+2B81F)
    - CJK Extension E (U+2B820-U+2CEAF)
    - CJK Extension F (U+2CEB0-U+2EBEF)
    - CJK Extension G (U+30000-U+3134F)
    - CJK Extension H (U+31350-U+323AF)
    - CJK Compatibility Supplement (U+2F800-U+2FA1F)
    """
    code = ord(char)

    # Basic CJK Unified Ideographs (most common, always supported)
    if 0x4E00 <= code <= 0x9FFF:
        return True

    # CJK Extension A (usually supported on modern Android)
    if 0x3400 <= code <= 0x4DBF:
        return True

    # CJK Compatibility Ideographs
    if 0xF900 <= code <= 0xFAFF:
        return True

    # Common punctuation and symbols
    if 0x3000 <= code <= 0x303F:  # CJK Symbols and Punctuation
        return True
    if 0xFF00 <= code <= 0xFFEF:  # Halfwidth and Fullwidth Forms
        return True

    # Basic ASCII and Latin
    if code < 0x0100:
        return True

    # Everything else (including Extension B-H) is considered not displayable
    return False


def is_displayable_string(s):
    """Check if all characters in a string are displayable."""
    for char in s:
        if not is_displayable_cjk(char):
            return False
    return True


def filter_wubi_dict(input_path, output_path):
    """Filter the Wubi dictionary to remove non-displayable characters."""

    with open(input_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    filtered_data = {}
    removed_entries = 0
    removed_chars = []
    total_entries = 0

    for key, values in data.items():
        # Skip metadata keys
        if key.startswith('__'):
            filtered_data[key] = values
            continue

        total_entries += len(values)

        # Filter the values list
        filtered_values = []
        for value in values:
            if is_displayable_string(value):
                filtered_values.append(value)
            else:
                removed_entries += 1
                # Collect first few removed chars for reporting
                if len(removed_chars) < 50:
                    removed_chars.append(value)

        # Only add key if there are remaining values
        if filtered_values:
            filtered_data[key] = filtered_values

    # Update entry count in metadata
    remaining_entries = sum(len(v) for k, v in filtered_data.items() if not k.startswith('__'))
    filtered_data['__entries'] = remaining_entries
    filtered_data['__description'] = "Wubi 86 Dictionary - Filtered for Android compatibility"

    # Write output
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(filtered_data, f, ensure_ascii=False, indent=2)

    print(f"Wubi Dictionary Filtering Complete")
    print(f"=" * 50)
    print(f"Total entries processed: {total_entries}")
    print(f"Entries removed: {removed_entries}")
    print(f"Entries remaining: {remaining_entries}")
    print(f"Removal rate: {removed_entries/total_entries*100:.2f}%")
    print()
    print(f"Sample removed characters (first 50):")
    for i, char in enumerate(removed_chars):
        code_points = ' '.join(f'U+{ord(c):04X}' for c in char)
        print(f"  {i+1}. '{char}' ({code_points})")


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)

    input_path = os.path.join(project_root, 'app/src/main/assets/common/wubi/wubi_dict.json')
    output_path = input_path  # Overwrite in place

    # Create backup
    backup_path = input_path + '.backup'

    print(f"Input: {input_path}")
    print(f"Backup: {backup_path}")
    print()

    # Read and backup
    with open(input_path, 'r', encoding='utf-8') as f:
        original_content = f.read()

    with open(backup_path, 'w', encoding='utf-8') as f:
        f.write(original_content)

    print(f"Backup created at: {backup_path}")
    print()

    # Filter
    filter_wubi_dict(input_path, output_path)

    print()
    print(f"Filtered dictionary written to: {output_path}")


if __name__ == '__main__':
    main()
