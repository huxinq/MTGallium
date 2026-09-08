set shell := ["bash", "-euo", "pipefail", "-c"]

gradle := "bash tools/mtgallium-gradle"
search_teacher_modules := ":agent:infoset-core:test :agent:infoset-argentum:test :agent:search-teacher:test"
search_teacher_integration := ":integration:argentum-search-teacher:test"

default:
    @just --list

# Human research interface; generated drafts, attempts and exports stay private.
research-help:
    python3 tools/mtgallium-research --help

research-tools-check:
    python3 -m unittest discover -s tools/tests -p 'test_*.py'
    python3 -m unittest discover -s tools -p 'test_research_build.py'

# Read retained JFR evidence through its finalized manifest; no gameplay or registry startup.
# Read-only: authenticate and summarize finalized gameplay; optional second run stays a separate population.
gameplay-summary RUN COMPARE="":
    @{{gradle}} --quiet :evaluation:search-teacher:run --args={{quote("--suite gameplay-summary --run-directory " + quote(RUN) + if COMPARE == "" { "" } else { " --run-directory " + quote(COMPARE) })}}

search-profile-summary PROFILE:
    @{{gradle}} --quiet :evaluation:search-teacher:run --args={{quote("--suite search-profile-summary --profile " + quote(PROFILE))}}

architecture-check PRIVATE_EVIDENCE_ROOT="/tmp/mtgallium-public-evidence":
    MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT={{quote(PRIVATE_EVIDENCE_ROOT)}} {{gradle}} :quality:architecture:test

check PRIVATE_EVIDENCE_ROOT="/tmp/mtgallium-public-evidence":
    just research-tools-check
    python3 -m unittest discover -s tools/analysis -p 'test_*.py'
    MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT={{quote(PRIVATE_EVIDENCE_ROOT)}} {{gradle}} :quality:architecture:test {{search_teacher_modules}} :evaluation:search-teacher:publicSourceTest {{search_teacher_integration}} :evaluation:argentum:test

search-teacher-check PRIVATE_EVIDENCE_ROOT="/tmp/mtgallium-public-evidence":
    just research-tools-check
    python3 -m unittest discover -s tools/analysis -p 'test_*.py'
    MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT={{quote(PRIVATE_EVIDENCE_ROOT)}} {{gradle}} {{search_teacher_modules}} :evaluation:search-teacher:publicSourceTest {{search_teacher_integration}}
