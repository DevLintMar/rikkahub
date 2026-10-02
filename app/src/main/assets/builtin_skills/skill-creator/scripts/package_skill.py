#!/usr/bin/env python3
"""RikkaHub Skill Packaging & Distribution Utility.

Zero third-party dependencies. Compatible with pure Python 3 standard library.
Features:
1. Performs pre-packaging static linting via run_eval.py.
2. Filters out temporary files, cache, evaluations, and OS artifacts.
3. Packages compliant .zip files compatible with RikkaHub's 'Import from file'.
4. Detailed file manifest, size statistics, and user-facing installation instructions.
"""

import argparse
import fnmatch
import os
import sys
import zipfile
from pathlib import Path

# Add scripts directory to path
SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import run_eval

EXCLUDE_PATTERNS = [
    ".git*",
    "__pycache__*",
    "*.pyc",
    "*.pyo",
    "*.pyd",
    ".DS_Store",
    "Thumbs.db",
    "*.tmp",
    "*.swp",
    "*~",
    "eval_results.json",
    "eval_results.html",
    "iteration_history.json",
    "iteration_history.html",
    "opt_report.json"
]


def should_exclude(rel_path: str) -> bool:
    """Check if relative path matches any exclusion patterns."""
    parts = rel_path.replace("\\", "/").split("/")
    for part in parts:
        for pat in EXCLUDE_PATTERNS:
            if fnmatch.fnmatch(part, pat) or fnmatch.fnmatch(rel_path, pat):
                return True
    return False


def format_bytes(size: int) -> str:
    """Format file size in human-readable units."""
    if size < 1024:
        return f"{size} B"
    elif size < 1024 * 1024:
        return f"{size / 1024:.1f} KB"
    else:
        return f"{size / (1024 * 1024):.2f} MB"


def package_skill(
    skill_dir: Path,
    output_zip: Path,
    flat_root: bool = False
) -> list[tuple[str, int, int]]:
    """Package skill directory into a compliant zip archive.

    Returns list of (rel_path, original_size, compressed_size).
    """
    skill_name = skill_dir.name
    manifest = []

    # Ensure parent output directory exists
    output_zip.parent.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(output_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(skill_dir):
            dirs.sort()
            files.sort()

            for file in files:
                full_path = Path(root) / file
                rel_to_skill = full_path.relative_to(skill_dir).as_posix()

                if should_exclude(rel_to_skill):
                    continue

                # Target path inside ZIP
                arcname = rel_to_skill if flat_root else f"{skill_name}/{rel_to_skill}"

                # Store file in zip
                zf.write(full_path, arcname)

                # Get size info
                info = zf.getinfo(arcname)
                manifest.append((arcname, info.file_size, info.compress_size))

    return manifest


def main():
    parser = argparse.ArgumentParser(description="RikkaHub Skill Packaging & Distribution Utility")
    parser.add_argument("--skill-path", required=True, help="Path to skill directory to package")
    parser.add_argument("--output", default=None, help="Output .zip path (default: <skill-name>.zip or /workspace/exports/<name>.zip)")
    parser.add_argument("--skip-lint", action="store_true", help="Skip pre-packaging static lint checks")
    parser.add_argument("--flat", action="store_true", help="Do not wrap contents inside top-level skill name folder")
    args = parser.parse_args()

    skill_path = Path(args.skill_path).resolve()
    if not skill_path.is_dir():
        print(f"[ERROR] Skill directory '{skill_path}' does not exist.", file=sys.stderr)
        sys.exit(1)

    # 1. Run static linting
    if not args.skip_lint:
        print(f"=== Running Pre-Packaging Lint on '{skill_path.name}' ===")
        lint_ok, errors, warnings = run_eval.lint_skill(skill_path)
        if errors:
            print(f"[FAIL] Found {len(errors)} error(s). Packaging aborted:")
            for err in errors:
                print(f"  - {err}")
            print("\nPlease fix linting errors before packaging, or pass --skip-lint to bypass.")
            sys.exit(1)
        if warnings:
            print(f"[WARN] {len(warnings)} warning(s):")
            for w in warnings:
                print(f"  - {w}")
        print("[PASS] Pre-packaging lint passed successfully.\n")

    # 2. Determine output path
    skill_name = skill_path.name
    if args.output:
        out_zip = Path(args.output).resolve()
    else:
        # Check if in RikkaHub workspace
        if Path("/workspace").exists():
            out_zip = Path(f"/workspace/exports/{skill_name}.zip").resolve()
        else:
            out_zip = skill_path.parent / f"{skill_name}.zip"

    # 3. Create zip package
    print(f"Packaging skill '{skill_name}' into:\n  {out_zip}\n")
    manifest = package_skill(skill_path, out_zip, flat_root=args.flat)

    if not manifest:
        print("[ERROR] No files were packaged! Please verify skill directory contents.", file=sys.stderr)
        sys.exit(1)

    # 4. Print manifest summary
    total_orig = sum(m[1] for m in manifest)
    total_comp = sum(m[2] for m in manifest)
    ratio = (100.0 - (total_comp / total_orig * 100.0)) if total_orig > 0 else 0.0

    print("---------------- File Manifest ----------------")
    for arcname, orig_sz, comp_sz in manifest:
        print(f"  {arcname:<45} {format_bytes(orig_sz):>10} -> {format_bytes(comp_sz):>10}")
    print("-----------------------------------------------")
    print(f"Total: {len(manifest)} file(s)")
    print(f"Uncompressed: {format_bytes(total_orig)}")
    print(f"Compressed:   {format_bytes(total_comp)} ({ratio:.1f}% space saved)")
    print(f"Package Path: {out_zip}\n")

    # 5. User instructions
    print("=== How to Import in RikkaHub ===")
    print("1. In RikkaHub, navigate to: Settings -> Agent Skills.")
    print("2. Tap the '+' button in the top app bar.")
    print("3. Select 'Import from file'.")
    print(f"4. Choose this zip file ({out_zip.name}).")
    print(f"5. The skill '{skill_name}' will be extracted and ready to enable for your Assistant!")


if __name__ == "__main__":
    main()
