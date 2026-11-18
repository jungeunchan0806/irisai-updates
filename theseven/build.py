"""Repack the supplied APK with only the native UI patch (unsigned output)."""
import argparse
import sys
import zipfile
from pathlib import Path

from patch import patch
from verify import BASELINE_SHA256, LIB, sha

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'bshack'))
from repack import repack

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('original', type=Path)
    parser.add_argument('unsigned_output', type=Path)
    args = parser.parse_args()
    if sha(args.original) != BASELINE_SHA256:
        raise SystemExit('Unrecognized original APK; no output written')
    if args.original.resolve() == args.unsigned_output.resolve():
        raise SystemExit('Output must not overwrite the original APK')
    with zipfile.ZipFile(args.original) as z:
        modified = patch(z.read(LIB))
    repack(args.original, args.unsigned_output, {LIB: modified})
