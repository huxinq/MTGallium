set shell := ["bash", "-euo", "pipefail", "-c"]

gradle := "bash tools/mtgallium-gradle"
search_teacher_modules := ":agent:infoset-core:test :agent:infoset-argentum:test :agent:search-teacher:test"
search_teacher_integration := ":integration:argentum-search-teacher:test"

default:
    @just --list

architecture-check PRIVATE_EVIDENCE_ROOT="/tmp/mtgallium-public-evidence":
    MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT={{quote(PRIVATE_EVIDENCE_ROOT)}} {{gradle}} :quality:architecture:test

check PRIVATE_EVIDENCE_ROOT="/tmp/mtgallium-public-evidence":
    python3 -m unittest discover -s tools/analysis -p 'test_*.py'
    MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT={{quote(PRIVATE_EVIDENCE_ROOT)}} {{gradle}} :quality:architecture:test {{search_teacher_modules}} :evaluation:search-teacher:publicSourceTest {{search_teacher_integration}} :evaluation:argentum:test

search-teacher-check PRIVATE_EVIDENCE_ROOT="/tmp/mtgallium-public-evidence":
    python3 -m unittest discover -s tools/analysis -p 'test_*.py'
    MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT={{quote(PRIVATE_EVIDENCE_ROOT)}} {{gradle}} {{search_teacher_modules}} :evaluation:search-teacher:publicSourceTest {{search_teacher_integration}}
