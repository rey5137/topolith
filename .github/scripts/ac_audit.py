#!/usr/bin/env python3
"""Audit acceptance criteria against named tests and owning slices."""
import argparse
import re
from collections import Counter
from pathlib import Path

DEFINITION = re.compile(r'^- (AC-([A-Z]+)-(\d+))\b(?: \((M\d+\.\d+)\))?')
JAVA_METHOD = re.compile(
    r'@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b(?:\s*\([^)]*\))?'
    r'(?:\s|@\w+(?:\s*\([^)]*\))?)*(?:public\s+|protected\s+|private\s+)?'
    r'void\s+(ac_[a-z]+_\d+(?:_\w*)?)\s*\('
)
JAVA_COMMENTS = re.compile(r'//[^\n]*|/\*.*?\*/', re.DOTALL)
PYTHON_TEST = re.compile(r'^\s*def (test_ac_[a-z]+_\d+(?:_\w*)?)\s*\(', re.MULTILINE)


def audit(spec, root):
    if not spec.is_dir():
        print(f'{spec}: spec directory does not exist')
        return 1
    definitions = []
    for path in sorted(spec.rglob('*.md')):
        for line_number, line in enumerate(path.read_text().splitlines(), 1):
            match = DEFINITION.match(line)
            if match:
                definitions.append((path, line_number, match.group(1), match.group(4)))

    if not definitions:
        print(f'{spec}: no criteria found')
        return 1

    names = set()
    for path in root.rglob('*.java'):
        parts = path.relative_to(root).parts
        if 'target' not in parts and any(parts[i:i + 2] == ('src', 'test') for i in range(len(parts) - 1)):
            names.update(JAVA_METHOD.findall(JAVA_COMMENTS.sub('', path.read_text())))
    for path in (root / '.github' / 'scripts').glob('test_*.py'):
        names.update(name[5:] for name in PYTHON_TEST.findall(path.read_text()))

    counts = Counter(item[2] for item in definitions)
    tested = pending = failing = 0
    for path, line, criterion, owner in definitions:
        prefix = criterion.lower().replace('-', '_')
        covered = any(name == prefix or name.startswith(prefix + '_') for name in names)
        reasons = []
        if counts[criterion] > 1:
            reasons.append('duplicate definition')
        if not covered and not owner:
            reasons.append('no named test or owning slice')
        if reasons:
            failing += 1
            print(f'{path}:{line} {criterion}: {", ".join(reasons)}')
        elif covered:
            tested += 1
        else:
            pending += 1
    print(f'{len(definitions)} criteria: {tested} tested, {pending} pending on a slice, {failing} failing')
    return int(failing > 0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--spec', type=Path, default=Path('docs/spec'))
    parser.add_argument('--root', type=Path, default=Path('.'))
    args = parser.parse_args()
    return audit(args.spec, args.root)


if __name__ == '__main__':
    raise SystemExit(main())
