#!/usr/bin/env bash
set -euo pipefail

repository="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
temporary="$(mktemp -d)"
trap 'rm -rf -- "$temporary"' EXIT
private_root="$temporary/private-evidence"
packet="$temporary/advisory-context.md"
mkdir -p -- "$private_root"
printf 'private-evidence-must-not-be-read\n' > "$private_root/sentinel"

MTGALLIUM_PUBLIC_SOURCE=1 \
MTGALLIUM_PRIVATE_EVIDENCE_ROOT="$private_root" \
    bash "$repository/tools/advisory-context.sh" "$packet"

head="$(git -C "$repository" rev-parse HEAD)"
gitlink="$(git -C "$repository" ls-tree "$head" -- third_party/argentum-engine)"
read -r mode type argentum_revision path <<< "$gitlink"
[[ "$mode" == "160000" && "$type" == "commit" ]]
[[ "$argentum_revision" =~ ^[0-9a-f]{40}$ ]]
[[ "$path" == "third_party/argentum-engine" ]]

grep -Fqx -- "- MTGallium HEAD: \`$head\`" "$packet"
grep -Fqx -- "- Argentum gitlink: \`$argentum_revision\`" "$packet"
grep -Fqx -- '# MTGallium coding-agent guide' "$packet"
grep -Fqx -- '# MTGallium architecture' "$packet"
if grep -Fq -- 'private-evidence-must-not-be-read' "$packet"; then
    printf 'Generator included private evidence content\n' >&2
    exit 1
fi

if bash "$repository/tools/advisory-context.sh" "$repository/advisory-context.md" >/dev/null 2>&1; then
    printf 'Generator accepted an in-checkout output path\n' >&2
    exit 1
fi
