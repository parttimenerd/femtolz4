"""Tests for release.py CHANGELOG handling."""

import textwrap
from datetime import date

import pytest

from release import update_changelog, CHANGELOG_FILE


TODAY = date.today().isoformat()


def make_changelog(content: str) -> str:
    return textwrap.dedent(content).lstrip()


# ── update_changelog ─────────────────────────────────────────────────────────


def test_unreleased_section_is_promoted(tmp_path):
    p = tmp_path / "CHANGELOG.md"
    p.write_text(make_changelog("""
        # Changelog

        ## [Unreleased]

        ### Added
        - Something cool
    """))
    update_changelog("1.2.0", path=p)
    result = p.read_text()
    assert f"## [1.2.0] - {TODAY}" in result
    assert "### Added\n- Something cool" in result


def test_fresh_unreleased_section_added_above_release(tmp_path):
    p = tmp_path / "CHANGELOG.md"
    p.write_text(make_changelog("""
        # Changelog

        ## [Unreleased]

        ### Added
        - Something cool
    """))
    update_changelog("1.2.0", path=p)
    result = p.read_text()
    unreleased_pos = result.index("## [Unreleased]")
    release_pos = result.index("## [1.2.0]")
    assert unreleased_pos < release_pos


def test_fresh_unreleased_section_is_empty(tmp_path):
    p = tmp_path / "CHANGELOG.md"
    p.write_text(make_changelog("""
        # Changelog

        ## [Unreleased]

        ### Added
        - Something cool
    """))
    update_changelog("1.2.0", path=p)
    result = p.read_text()
    between = result.split("## [Unreleased]")[1].split(f"## [1.2.0]")[0].strip()
    assert between == ""


def test_missing_unreleased_section_raises(tmp_path):
    p = tmp_path / "CHANGELOG.md"
    p.write_text("# Changelog\n\n## [1.0.0] - 2024-01-01\n")
    with pytest.raises(ValueError, match="Unreleased"):
        update_changelog("1.2.0", path=p)


def test_changelog_file_constant_points_to_file():
    assert CHANGELOG_FILE.name == "CHANGELOG.md"
