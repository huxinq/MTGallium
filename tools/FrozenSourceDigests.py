#!/usr/bin/env python3
"""Compute collector input digests from an explicitly reviewed parent commit.

This reads committed Git objects, not the current checkout or candidate output.
The output is for human review before supplying the values to golden capture.
"""

import argparse
import hashlib
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
ROOT_MAIN = "2a5cfce11fa59815d849d2a1f8fabab2e7eba79f"
SOURCES = {
    "identity": (
        "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/PolicyIdentity.kt",
        "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/SearchPolicy.kt",
        "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/BeliefTracker.kt",
        "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/BeliefPreparation.kt",
        "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/PolicyDefaults.kt",
        "agent/mono-red-models/src/main/kotlin/org/mtgallium/agent/monored/MonoRedInformationEvaluator.kt",
        "agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyContract.kt",
    ),
    "behavior": (
        "agent/mono-red-models/src/main/kotlin/org/mtgallium/agent/monored/ValueFeatures.kt",
        "agent/neural-policy/src/main/kotlin/org/mtgallium/agent/neural/FactualPolicyTensors.kt",
        "research/workbench/src/main/kotlin/org/mtgallium/research/workbench/SemanticFeatures.kt",
        "research/workbench/src/main/kotlin/org/mtgallium/research/workbench/Kernel.kt",
        "research/workbench/src/main/kotlin/org/mtgallium/research/workbench/Games.kt",
        "agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyContract.kt",
        "agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyHistoryCommitment.kt",
        "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/ArgentumSearchWorld.kt",
        "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/PerspectiveHistoryEventOrder.kt",
        "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/PerspectiveHistoryObjectReference.kt",
    ),
}


def git(*args: str) -> bytes:
    return subprocess.run(("git", "-C", str(ROOT), *args), check=True, capture_output=True).stdout


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("parent_sha", help="reviewed unretired production commit (40 lowercase hex)")
    args = parser.parse_args()
    parent = args.parent_sha
    if not re.fullmatch(r"[0-9a-f]{40}", parent):
        parser.error("parent_sha must be an explicit full lowercase commit SHA")
    if parent != ROOT_MAIN:
        parser.error("parent_sha must be the reviewed original unretired production parent")
    if git("cat-file", "-t", parent).strip() != b"commit":
        parser.error("parent_sha is not a commit")
    git("merge-base", "--is-ancestor", ROOT_MAIN, parent)
    print(f"MTG_GOLDEN_PRODUCTION_PARENT_SHA={parent}")
    for group, paths in SOURCES.items():
        digest = hashlib.sha256()
        for relative in sorted(paths):
            digest.update(relative.encode("utf-8"))
            digest.update(b"\0")
            digest.update(git("show", f"{parent}:{relative}"))
        print(f"MTG_GOLDEN_{group.upper()}_SOURCE_DIGEST={digest.hexdigest()}")


if __name__ == "__main__":
    main()
