#!/usr/bin/env python3
"""
Bump version and release femtolz4.

Steps:
  1. Bump version in pom.xml and README.md
  2. Run tests (mvn test)
  3. Build native libraries (build_native.py)
  4. Package JAR (mvn package -DskipTests)
  5. Commit, tag, push
  6. Create GitHub release with gh CLI

Usage:
  python3 release.py               # bump minor version (default)
  python3 release.py --patch       # bump patch version
  python3 release.py --major       # bump major version
  python3 release.py --dry-run     # show what would happen, change nothing
  python3 release.py --no-push     # skip git push and GitHub release
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from typing import Tuple


ROOT = Path(__file__).resolve().parent


# ── Version helpers ─────────────────────────────────────────────────────────────

def get_version() -> str:
    text = (ROOT / "pom.xml").read_text()
    m = re.search(r"<version>([^<]+)</version>", text)
    if not m:
        raise ValueError("version not found in pom.xml")
    return m.group(1)


def parse_version(v: str) -> Tuple[int, int, int]:
    parts = v.split(".")
    if len(parts) == 2:
        return int(parts[0]), int(parts[1]), 0
    return int(parts[0]), int(parts[1]), int(parts[2])


def bump(v: str, kind: str) -> str:
    major, minor, patch = parse_version(v)
    if kind == "major": return f"{major + 1}.0.0"
    if kind == "minor": return f"{major}.{minor + 1}.0"
    if kind == "patch": return f"{major}.{minor}.{patch + 1}"
    raise ValueError(kind)


# ── File updates ────────────────────────────────────────────────────────────────

def update_pom(old: str, new: str) -> None:
    p = ROOT / "pom.xml"
    p.write_text(p.read_text().replace(f"<version>{old}</version>",
                                        f"<version>{new}</version>", 1))


def update_readme(old: str, new: str) -> None:
    p = ROOT / "README.md"
    if not p.exists():
        return
    p.write_text(p.read_text()
                 .replace(f"femtolz4-{old}.jar", f"femtolz4-{new}.jar")
                 .replace(f"version>{old}<", f"version>{new}<"))


# ── Shell helpers ───────────────────────────────────────────────────────────────

def run(cmd: list[str], desc: str) -> None:
    print(f"\n── {desc}")
    print("   $", " ".join(str(c) for c in cmd))
    r = subprocess.run(cmd, cwd=ROOT)
    if r.returncode != 0:
        print(f"FAILED (exit {r.returncode})")
        sys.exit(r.returncode)


def git(*args: str) -> None:
    run(["git", *args], " ".join(["git", *args]))


# ── Main ────────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--major", action="store_true")
    group.add_argument("--minor", action="store_true")
    group.add_argument("--patch", action="store_true")
    parser.add_argument("--no-push",    action="store_true",
                        help="Skip git push and GitHub release")
    parser.add_argument("--dry-run",    action="store_true",
                        help="Print planned steps, change nothing")
    parser.add_argument("--skip-tests", action="store_true",
                        help="Skip mvn test (use with care)")
    args = parser.parse_args()

    kind = "major" if args.major else "patch" if args.patch else "minor"
    old  = get_version()
    new  = bump(old, kind)

    print(f"Current version : {old}")
    print(f"Next version    : {new}")
    print(f"Bump kind       : {kind}")

    if args.dry_run:
        print("\nDry run — nothing changed.")
        print("Steps that would run:")
        print(f"  1. pom.xml + README.md: {old} → {new}")
        if not args.skip_tests:
            print("  2. mvn test")
        print("  3. python3 build_native.py")
        print("  4. mvn package -DskipTests -Dnative.skip=true")
        print(f"  5. git add + commit + tag v{new}")
        if not args.no_push:
            print("  6. git push + git push --tags")
            print(f"  7. gh release create v{new}")
        return

    # Snapshot so we can revert on failure
    pom_snap    = (ROOT / "pom.xml").read_text()
    readme_snap = (ROOT / "README.md").read_text() if (ROOT / "README.md").exists() else None

    try:
        # 1. Version bumps
        print("\n── Updating version strings")
        update_pom(old, new)
        update_readme(old, new)
        print(f"   pom.xml + README.md: {old} → {new}")

        # 2. Tests
        if not args.skip_tests:
            run(["mvn", "test"], "Running tests")

        # 3. Native libs
        run(["python3", "build_native.py"], "Building native libraries")

        # 4. Package (native libs already built by step 3)
        run(["mvn", "package", "-DskipTests", "-Dnative.skip=true"], "Packaging JAR")

        # 5. Commit + tag
        git("add",
            "pom.xml", "README.md",
            "src/main/resources/native/darwin-aarch64/libfemtolz4.dylib",
            "src/main/resources/native/linux-amd64/libfemtolz4.so")
        git("commit", "-m", f"Release {new}")
        git("tag", "-a", f"v{new}", "-m", f"Release {new}")

        if not args.no_push:
            # 6. Push
            git("push")
            git("push", "--tags")

            # 7. GitHub release
            jar = ROOT / "target" / f"femtolz4-{new}.jar"
            assets = [str(jar)] if jar.exists() else []
            run([
                "gh", "release", "create", f"v{new}",
                "--title", f"femtolz4 {new}",
                "--generate-notes",
                *assets,
            ], f"Creating GitHub release v{new}")

    except SystemExit:
        print("\nRolling back version changes …")
        (ROOT / "pom.xml").write_text(pom_snap)
        if readme_snap is not None:
            (ROOT / "README.md").write_text(readme_snap)
        raise

    print(f"\n✓ Released femtolz4 {new}")
    if jar := (ROOT / "target" / f"femtolz4-{new}.jar"):
        if jar.exists():
            print(f"  JAR: {jar}  ({jar.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
