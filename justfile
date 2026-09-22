set shell := ["bash", "-euo", "pipefail", "-c"]

gradle := "bash tools/mtgallium-gradle"
policy_modules := ":agent:infoset-semantics:test :agent:infoset-planning:test :agent:infoset-argentum:test :agent:neural-policy:test :agent:mono-red-models:test :agent:argentum-policy:test"
policy_integration := ":integration:argentum-policy:test"

default:
    @just --list

# Direct research commands; generated private data stays outside public source.
research-help:
    python3 tools/mtgallium-research --help

research-tools-check:
    node --test tools/durable-run/test/*.test.mjs
    python3 -m unittest discover -s tools/tests -p 'test_*.py'

architecture-check:
    {{gradle}} checkArchitecture

check PRIVATE_EVIDENCE_ROOT="/tmp/mtgallium-public-evidence":
    just research-tools-check
    python3 -m unittest discover -s tools/analysis -p 'test_*.py'
    MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT={{quote(PRIVATE_EVIDENCE_ROOT)}} {{gradle}} checkArchitecture {{policy_modules}} :research:workbench:test {{policy_integration}} :evaluation:argentum:test

policy-check PRIVATE_EVIDENCE_ROOT="/tmp/mtgallium-public-evidence":
    just research-tools-check
    python3 -m unittest discover -s tools/analysis -p 'test_*.py'
    MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT={{quote(PRIVATE_EVIDENCE_ROOT)}} {{gradle}} {{policy_modules}} :research:workbench:test {{policy_integration}}
