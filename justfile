set shell := ["bash", "-euo", "pipefail", "-c"]

gradle := "bash tools/mtgallium-gradle"
search_teacher_modules := ":agent:infoset-core:test :agent:infoset-argentum:test :agent:search-teacher:test"
search_teacher_integration := ":integration:argentum-search-teacher:test"

default:
    @just --list

# Generate a private/local conceptual snapshot from public source. Output stays outside the checkout.
advisory-context OUTPUT_PATH="/tmp/mtgallium-advisory-context.md":
    bash tools/advisory-context.sh {{quote(OUTPUT_PATH)}}

advisory-context-test:
    bash tools/advisory-context.test.sh

architecture-check PRIVATE_EVIDENCE_ROOT="/tmp/mtgallium-public-evidence":
    MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT={{quote(PRIVATE_EVIDENCE_ROOT)}} {{gradle}} :quality:architecture:test

check PRIVATE_EVIDENCE_ROOT="/tmp/mtgallium-public-evidence":
    MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT={{quote(PRIVATE_EVIDENCE_ROOT)}} {{gradle}} :quality:architecture:test {{search_teacher_modules}} :evaluation:search-teacher:publicSourceTest {{search_teacher_integration}} :evaluation:argentum:test

search-teacher-check PRIVATE_EVIDENCE_ROOT="/tmp/mtgallium-public-evidence":
    MTGALLIUM_PUBLIC_SOURCE=1 MTGALLIUM_PRIVATE_EVIDENCE_ROOT={{quote(PRIVATE_EVIDENCE_ROOT)}} {{gradle}} {{search_teacher_modules}} :evaluation:search-teacher:publicSourceTest {{search_teacher_integration}}
