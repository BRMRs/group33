#!/usr/bin/env python3
"""Validate repository hygiene and pull-request metadata without third-party tools."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[1]
TITLE_PATTERN = re.compile(
    r"^(feat|fix|test|docs|refactor|perf|build|ci|chore|revert)"
    r"\([a-z0-9][a-z0-9-]*\): [a-z0-9].+[^.]$"
)
BRANCH_PATTERN = re.compile(
    r"^(feature|fix|chore|docs|test|refactor)/[1-9][0-9]*-[a-z0-9]+(?:-[a-z0-9]+)*$"
)
MARKDOWN_LINK_PATTERN = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
FORBIDDEN_PARTS = {".gradle", "build", "bin", ".idea"}
FORBIDDEN_NAMES = {".DS_Store", "local.properties", ".env"}


def tracked_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return [
        ROOT / item.decode()
        for item in result.stdout.split(b"\0")
        if item and not item.decode().startswith(".git/")
    ]


def check_metadata(branch: str, title: str, errors: list[str]) -> None:
    if branch and not (branch.startswith("dependabot/") or BRANCH_PATTERN.fullmatch(branch)):
        errors.append(
            "Branch name must match "
            "<feature|fix|chore|docs|test|refactor>/<issue>-<short-name>: "
            f"{branch}"
        )
    if title and not TITLE_PATTERN.fullmatch(title):
        errors.append(
            "PR title must match '<type>(<scope>): <imperative summary>' with an allowed type "
            f"and no trailing period: {title}"
        )


def check_repository(files: list[Path], errors: list[str]) -> None:
    for path in files:
        relative = path.relative_to(ROOT)
        if any(part in FORBIDDEN_PARTS for part in relative.parts):
            errors.append(f"Generated or IDE path is tracked: {relative}")
        if relative.name in FORBIDDEN_NAMES or (
            relative.name.startswith(".env.") and relative.name != ".env.example"
        ):
            errors.append(f"Local configuration is tracked: {relative}")

        if path.suffix == ".json":
            try:
                json.loads(path.read_text(encoding="utf-8"))
            except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
                errors.append(f"Invalid JSON in {relative}: {exc}")

        if path.suffix.lower() == ".md":
            check_markdown_links(path, errors)


def check_markdown_links(path: Path, errors: list[str]) -> None:
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        errors.append(f"Cannot read {path.relative_to(ROOT)}: {exc}")
        return

    for raw_target in MARKDOWN_LINK_PATTERN.findall(text):
        target = raw_target.strip().split()[0].strip("<>")
        if not target or target.startswith(("#", "http://", "https://", "mailto:")):
            continue
        target = unquote(target.split("#", 1)[0])
        if not target:
            continue
        resolved = (path.parent / target).resolve()
        try:
            resolved.relative_to(ROOT)
        except ValueError:
            errors.append(
                f"Relative link escapes repository in {path.relative_to(ROOT)}: {raw_target}"
            )
            continue
        if not resolved.exists():
            errors.append(
                f"Broken relative link in {path.relative_to(ROOT)}: {raw_target}"
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--branch", default="")
    parser.add_argument("--pr-title", default="")
    args = parser.parse_args()

    errors: list[str] = []
    check_metadata(args.branch.strip(), args.pr_title.strip(), errors)
    check_repository(tracked_files(), errors)

    if errors:
        print("Repository policy check failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print("Repository policy check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
