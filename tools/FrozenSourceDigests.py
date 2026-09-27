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
POST_IDENTITY_PARENT = "26f3af44bd6030f931ddefaff1f29d1d30c3fe90"
RETIRED_BELIEF_PREPARATION = (
    "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/BeliefPreparation.kt"
)
SOURCES = {
    "identity": (
        "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/PolicyIdentity.kt",
        "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/SearchPolicy.kt",
        "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/BeliefTracker.kt",
        "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/BeliefPreparation.kt",
        "agent/argentum-policy/src/main/kotlin/org/mtgallium/agent/argentum/policy/PolicyDefaults.kt",
        "agent/mono-red-models/src/main/kotlin/org/mtgallium/agent/monored/MonoRedInformationEvaluator.kt", # persisted: reviewed source path
        "agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyContract.kt",
    ),
    "behavior": (
        "agent/mono-red-models/src/main/kotlin/org/mtgallium/agent/monored/ValueFeatures.kt",
        "agent/neural-policy/src/main/kotlin/org/mtgallium/agent/neural/FactualPolicyTensors.kt",
        "research/workbench/src/main/kotlin/org/mtgallium/research/workbench/SemanticFeatures.kt", # persisted: reviewed source path
        "research/workbench/src/main/kotlin/org/mtgallium/research/workbench/Kernel.kt",
        "research/workbench/src/main/kotlin/org/mtgallium/research/workbench/Games.kt",
        "agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyContract.kt",
        "agent/infoset-semantics/src/main/kotlin/org/mtgallium/agent/infoset/core/PolicyHistoryCommitment.kt", # persisted: reviewed source path
        "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/ArgentumSearchWorld.kt",
        "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/PerspectiveHistoryEventOrder.kt", # persisted: reviewed source path
        "agent/infoset-argentum/src/main/kotlin/org/mtgallium/agent/infoset/argentum/PerspectiveHistoryObjectReference.kt", # persisted: reviewed source path
    ),
}


def git(*args: str) -> bytes:
    return subprocess.run(("git", "-C", str(ROOT), *args), check=True, capture_output=True).stdout


def selected_sources(parent: str, group: str | None) -> dict[str, tuple[str, ...]]:
    if parent == ROOT_MAIN:
        if group is None:
            return SOURCES
        if group in SOURCES:
            return {group: SOURCES[group]}
    elif parent == POST_IDENTITY_PARENT and group == "identity":
        return {"identity": tuple(path for path in SOURCES["identity"] if path != RETIRED_BELIEF_PREPARATION)}
    raise ValueError("only the original parent accepts both groups; POST requires explicit --group identity")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("parent_sha", help="exact reviewed production parent (40 lowercase hex)")
    parser.add_argument("--group", choices=tuple(SOURCES), help="requested golden group; required for POST identity")
    args = parser.parse_args()
    parent = args.parent_sha
    if not re.fullmatch(r"[0-9a-f]{40}", parent):
        parser.error("parent_sha must be an explicit full lowercase commit SHA")
    try:
        groups = selected_sources(parent, args.group)
    except ValueError as error:
        parser.error(str(error))
    if git("cat-file", "-t", parent).strip() != b"commit":
        parser.error("parent_sha is not a commit")
    git("merge-base", "--is-ancestor", ROOT_MAIN, parent)
    print(f"MTG_GOLDEN_PRODUCTION_PARENT_SHA={parent}")
    for group, paths in groups.items():
        digest = hashlib.sha256()
        for relative in sorted(paths):
            digest.update(relative.encode("utf-8"))
            digest.update(b"\0")
            digest.update(git("show", f"{parent}:{relative}"))
        print(f"MTG_GOLDEN_{group.upper()}_SOURCE_DIGEST={digest.hexdigest()}")


if __name__ == "__main__":
    main()
