#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
# ///
"""Release helper for SMS Forwarder.

Two uses:

  # Cut a release: compute the changelog from the last tag to HEAD, create an
  # annotated tag whose message IS the changelog, and push it (push triggers
  # the GitHub Actions release pipeline which builds + publishes signed APKs).
  uv run scripts/release.py tag --bump patch --push
  uv run scripts/release.py tag v1.4.0 --push        # explicit version

  # Print the changelog for a tag range (used by CI to build the release notes).
  uv run scripts/release.py changelog --to v1.4.0

Run from the repository root.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys

# Conventional-commit type -> changelog section heading, in display order.
SECTIONS: list[tuple[str, str]] = [
    ("feat", "🚀 Features"),
    ("fix", "🐛 Fixes"),
    ("perf", "⚡ Performance"),
    ("refactor", "♻️ Refactoring"),
    ("docs", "📝 Documentation"),
    ("test", "✅ Tests"),
    ("build", "📦 Build"),
    ("ci", "🤖 CI"),
    ("chore", "🧹 Chores"),
]
_PREFIX = re.compile(r"^(?P<type>\w+)(?:\([^)]*\))?!?:\s*(?P<desc>.+)$")


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"git {' '.join(args)} failed:\n{result.stderr.strip()}")
    return result.stdout.strip()


def last_tag() -> str | None:
    """Most recent tag reachable from HEAD, or None if there are no tags yet."""
    r = subprocess.run(
        ["git", "describe", "--tags", "--abbrev=0"], capture_output=True, text=True
    )
    return r.stdout.strip() or None if r.returncode == 0 else None


def prev_tag(ref: str) -> str | None:
    """The tag immediately preceding `ref`, or None."""
    r = subprocess.run(
        ["git", "describe", "--tags", "--abbrev=0", f"{ref}^"],
        capture_output=True,
        text=True,
    )
    return r.stdout.strip() or None if r.returncode == 0 else None


def commits(rng: str | None) -> list[tuple[str, str]]:
    """(short_sha, subject) for each commit in `rng` (e.g. 'v1.2.0..HEAD'), or all if None."""
    args = ["log", "--no-merges", "--pretty=format:%h%x1f%s"]
    if rng:
        args.append(rng)
    out = git(*args)
    pairs: list[tuple[str, str]] = []
    for line in out.splitlines():
        if "\x1f" in line:
            sha, subject = line.split("\x1f", 1)
            pairs.append((sha, subject))
    return pairs


def build_changelog(prev: str | None, to: str) -> str:
    rng = f"{prev}..{to}" if prev else None
    buckets: dict[str, list[str]] = {key: [] for key, _ in SECTIONS}
    other: list[str] = []
    for sha, subject in commits(rng):
        m = _PREFIX.match(subject)
        if m and m.group("type").lower() in buckets:
            buckets[m.group("type").lower()].append(f"- {m.group('desc')} ({sha})")
        else:
            other.append(f"- {subject} ({sha})")

    lines: list[str] = []
    for key, heading in SECTIONS:
        if buckets[key]:
            lines.append(f"### {heading}")
            lines.extend(buckets[key])
            lines.append("")
    if other:
        lines.append("### Other")
        lines.extend(other)
        lines.append("")
    if not lines:
        lines = ["_No changes recorded._", ""]

    header = f"## {to}"
    if prev:
        header += f"\n\nChanges since **{prev}**."
    return header + "\n\n" + "\n".join(lines).rstrip() + "\n"


def bump(version: str, part: str) -> str:
    """Bump a vMAJOR.MINOR.PATCH tag. `version` may be None-ish -> v0.0.0 base."""
    base = (version or "v0.0.0").lstrip("v")
    nums = re.findall(r"\d+", base)[:3]
    major, minor, patch = (int(nums[i]) if i < len(nums) else 0 for i in range(3))
    if part == "major":
        major, minor, patch = major + 1, 0, 0
    elif part == "minor":
        minor, patch = minor + 1, 0
    else:
        patch += 1
    return f"v{major}.{minor}.{patch}"


def cmd_changelog(args: argparse.Namespace) -> None:
    to = args.to or "HEAD"
    prev = args.frm or (prev_tag(to) if to != "HEAD" else last_tag())
    sys.stdout.write(build_changelog(prev, to))


def cmd_tag(args: argparse.Namespace) -> None:
    prev = last_tag()
    if args.version:
        new = args.version if args.version.startswith("v") else f"v{args.version}"
    else:
        new = bump(prev, args.bump)

    existing = subprocess.run(
        ["git", "rev-parse", "-q", "--verify", f"refs/tags/{new}"],
        capture_output=True,
        text=True,
    )
    if existing.returncode == 0:
        sys.exit(f"Tag {new} already exists.")

    changelog = build_changelog(prev, "HEAD")
    print(f"Previous tag : {prev or '(none)'}")
    print(f"New tag      : {new}")
    print("-" * 60)
    print(changelog)
    print("-" * 60)

    if args.dry_run:
        print("Dry run — no tag created.")
        return

    git("tag", "-a", new, "-m", changelog)
    print(f"Created annotated tag {new}.")
    if args.push:
        remote = args.remote
        git("push", remote, new)
        print(f"Pushed {new} to {remote} — the release workflow will now build and publish APKs.")
    else:
        print(f"Not pushed. Run: git push {args.remote} {new}")


def main() -> None:
    parser = argparse.ArgumentParser(description="SMS Forwarder release helper")
    sub = parser.add_subparsers(dest="command", required=True)

    t = sub.add_parser("tag", help="create (and optionally push) a release tag")
    t.add_argument("version", nargs="?", help="explicit version, e.g. v1.4.0 (default: bump last tag)")
    t.add_argument("--bump", choices=["major", "minor", "patch"], default="patch")
    t.add_argument("--push", action="store_true", help="push the tag to the remote")
    t.add_argument("--remote", default="origin")
    t.add_argument("--dry-run", action="store_true", help="print the changelog without tagging")
    t.set_defaults(func=cmd_tag)

    c = sub.add_parser("changelog", help="print the changelog for a tag range")
    c.add_argument("--from", dest="frm", help="start ref (default: previous tag)")
    c.add_argument("--to", help="end ref (default: HEAD)")
    c.set_defaults(func=cmd_changelog)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
