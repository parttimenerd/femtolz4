#!/usr/bin/env python3
"""
Bump version and release femtolz4.

Steps:
  1. Bump version in pom.xml and README.md  (skipped with --no-bump)
  2. Run tests (mvn test)
  3. Build native libraries (build_native.py)
  4. Package JAR (mvn package -DskipTests)
  5. Deploy to Maven Central (mvn deploy -P release -DskipTests)
  6. Commit, tag, push
  7. Create GitHub release with gh CLI

Usage:
  python3 release.py                      # bump minor version (default)
  python3 release.py --patch              # bump patch version
  python3 release.py --major              # bump major version
  python3 release.py --no-bump            # release current version as-is (no version change)
  python3 release.py --dry-run            # show what would happen, change nothing
  python3 release.py --no-push            # skip git push and GitHub release
  python3 release.py --no-deploy          # skip Maven Central deployment
  python3 release.py --github-release-only  # only create GitHub release for current version
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from datetime import date
from pathlib import Path
from typing import Tuple


ROOT = Path(__file__).resolve().parent
CHANGELOG_FILE = ROOT / "CHANGELOG.md"


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

def update_changelog(new: str, path: Path | None = None) -> None:
    p = path or CHANGELOG_FILE
    text = p.read_text()
    if "## [Unreleased]" not in text:
        raise ValueError("CHANGELOG.md has no [Unreleased] section")
    today = date.today().isoformat()
    text = text.replace(
        "## [Unreleased]",
        f"## [Unreleased]\n\n## [{new}] - {today}",
        1,
    )
    p.write_text(text)


def get_changelog_entry(version: str) -> str:
    """Extract the released section for `version` from CHANGELOG.md."""
    if not CHANGELOG_FILE.exists():
        return ""
    text = CHANGELOG_FILE.read_text()
    m = re.search(
        rf"## \[{re.escape(version)}\][^\n]*\n(.*?)(?=\n## \[|$)",
        text,
        re.DOTALL,
    )
    if not m:
        return ""
    entry = m.group(1).strip()
    # Drop empty sub-headers
    lines, pending_header = [], None
    for line in entry.splitlines():
        if line.startswith("###"):
            pending_header = line
        elif line.strip():
            if pending_header:
                lines.append(pending_header)
                pending_header = None
            lines.append(line)
    return "\n".join(lines)


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
    group.add_argument("--major",   action="store_true")
    group.add_argument("--minor",   action="store_true")
    group.add_argument("--patch",   action="store_true")
    group.add_argument("--no-bump", action="store_true",
                       help="Release current version without bumping")
    parser.add_argument("--no-push",    action="store_true",
                        help="Skip git push and GitHub release")
    parser.add_argument("--no-deploy",  action="store_true",
                        help="Skip Maven Central deployment")
    parser.add_argument("--github-release-only", action="store_true",
                        help="Only create GitHub release for current version (skip everything else)")
    parser.add_argument("--dry-run",    action="store_true",
                        help="Print planned steps, change nothing")
    parser.add_argument("--skip-tests", action="store_true",
                        help="Skip mvn test (use with care)")
    args = parser.parse_args()

    old = get_version()

    if args.no_bump or args.github_release_only:
        new = old
    else:
        kind = "major" if args.major else "patch" if args.patch else "minor"
        new  = bump(old, kind)

    print(f"Current version : {old}")
    print(f"Release version : {new}")
    if not args.no_bump and not args.github_release_only:
        kind = "major" if args.major else "patch" if args.patch else "minor"
        print(f"Bump kind       : {kind}")

    # ── GitHub-release-only shortcut ─────────────────────────────────────────
    if args.github_release_only:
        jar = ROOT / "target" / f"femtolz4-{new}.jar"
        assets = [str(jar)] if jar.exists() else []
        changelog_entry = get_changelog_entry(new)
        notes_file = ROOT / ".release-notes.md"
        if changelog_entry:
            notes_file.write_text(changelog_entry)
            notes_args: list[str] = ["--notes-file", str(notes_file)]
        else:
            notes_args = ["--generate-notes"]
        try:
            run([
                "gh", "release", "create", f"v{new}",
                "--title", f"femtolz4 {new}",
                *notes_args,
                *assets,
            ], f"Creating GitHub release v{new}")
        finally:
            if notes_file.exists():
                notes_file.unlink()
        print(f"\n✓ GitHub release v{new} created")
        return
    # ─────────────────────────────────────────────────────────────────────────

    if args.dry_run:
        print("\nDry run — nothing changed.")
        print("Steps that would run:")
        if args.no_bump:
            print(f"  1. CHANGELOG.md: promote [Unreleased] → [{new}]")
        else:
            print(f"  1. pom.xml + README.md + CHANGELOG.md: {old} → {new}")
        if not args.skip_tests:
            print("  2. mvn test")
        print("  3. python3 build_native.py")
        print("  4. mvn package -DskipTests -Dnative.skip=true")
        if not args.no_deploy:
            print("  5. mvn deploy -P release -DskipTests -Dnative.skip=true")
        print(f"  6. git add + commit + tag v{new}")
        if not args.no_push:
            print("  7. git push + git push --tags")
            print(f"  8. gh release create v{new}")
        return

    # Snapshot so we can revert on failure
    pom_snap       = (ROOT / "pom.xml").read_text()
    readme_snap    = (ROOT / "README.md").read_text() if (ROOT / "README.md").exists() else None
    changelog_snap = CHANGELOG_FILE.read_text() if CHANGELOG_FILE.exists() else None

    try:
        # 1. Version bumps (skipped when --no-bump)
        if args.no_bump:
            print("\n── Promoting CHANGELOG [Unreleased] → [{new}]")
            update_changelog(new)
        else:
            print("\n── Updating version strings")
            update_pom(old, new)
            update_readme(old, new)
            update_changelog(new)
            print(f"   pom.xml + README.md + CHANGELOG.md: {old} → {new}")

        # 2. Tests
        if not args.skip_tests:
            run(["mvn", "clean", "test"], "Running tests")

        # 3. Native libs
        run(["python3", "build_native.py"], "Building native libraries")

        # 4. Package
        run(["mvn", "clean", "package", "-DskipTests", "-Dnative.skip=true"], "Packaging JAR")

        # 5. Deploy to Maven Central
        if not args.no_deploy:
            run(["mvn", "clean", "deploy", "-P", "release", "-DskipTests", "-Dnative.skip=true"],
                "Deploying to Maven Central")

        # 6. Commit + tag
        git("add", "-u")
        git("commit", "-m", f"Release {new}")
        git("tag", "-a", f"v{new}", "-m", f"Release {new}")

        if not args.no_push:
            # 7. Push
            git("push")
            git("push", "--tags")

            # 8. GitHub release
            jar = ROOT / "target" / f"femtolz4-{new}.jar"
            assets = [str(jar)] if jar.exists() else []

            changelog_entry = get_changelog_entry(new)
            notes_args: list[str]
            if changelog_entry:
                notes_file = ROOT / ".release-notes.md"
                notes_file.write_text(changelog_entry)
                notes_args = ["--notes-file", str(notes_file)]
            else:
                notes_args = ["--generate-notes"]

            try:
                run([
                    "gh", "release", "create", f"v{new}",
                    "--title", f"femtolz4 {new}",
                    *notes_args,
                    *assets,
                ], f"Creating GitHub release v{new}")
            finally:
                notes_file = ROOT / ".release-notes.md"
                if notes_file.exists():
                    notes_file.unlink()

            # Notify parttimenerd.github.io to update releases.json
            run([
                "gh", "api",
                "repos/parttimenerd/parttimenerd.github.io/dispatches",
                "--method", "POST",
                "-f", "event_type=femto-release",
            ], "Notifying parttimenerd.github.io")

    except SystemExit:
        print("\nRolling back version changes …")
        (ROOT / "pom.xml").write_text(pom_snap)
        if readme_snap is not None:
            (ROOT / "README.md").write_text(readme_snap)
        if changelog_snap is not None:
            CHANGELOG_FILE.write_text(changelog_snap)
        raise

    print(f"\n✓ Released femtolz4 {new}")
    jar = ROOT / "target" / f"femtolz4-{new}.jar"
    if jar.exists():
        print(f"  JAR: {jar}  ({jar.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
