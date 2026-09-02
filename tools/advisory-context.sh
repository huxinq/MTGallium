#!/usr/bin/env bash
# Generate a local conceptual snapshot from this public checkout. The packet is
# intentionally external to the repository and contains no private evidence.
set -euo pipefail

if (( $# > 1 )); then
    printf 'Usage: %s [output-path]\n' "$0" >&2
    exit 2
fi

script_dir="$(cd -- "$(dirname -- "$0")" && pwd -P)"
repository="$(git -C "$script_dir/.." rev-parse --show-toplevel)"
repository="$(cd -- "$repository" && pwd -P)"
cd "$repository"

output="${1:-${TMPDIR:-/tmp}/mtgallium-advisory-context.md}"
output_dir="$(dirname -- "$output")"
mkdir -p -- "$output_dir"
output_dir="$(cd -- "$output_dir" && pwd -P)"
output="$output_dir/$(basename -- "$output")"

case "$output" in
    "$repository"|"$repository"/*)
        printf 'Advisory context output must stay outside the source checkout: %s\n' "$output" >&2
        exit 2
        ;;
esac

branch="$(git branch --show-current)"
branch="${branch:-(detached HEAD)}"
head="$(git rev-parse HEAD)"
gitlink="$(git ls-tree "$head" -- third_party/argentum-engine)"
read -r mode type argentum_revision path <<< "$gitlink"
if [[ "$mode" != "160000" || "$type" != "commit" ||
    ! "$argentum_revision" =~ ^[0-9a-f]{40}$ ||
    "$path" != "third_party/argentum-engine" ]]; then
    printf 'Expected third_party/argentum-engine to be a gitlink at HEAD %s\n' "$head" >&2
    exit 1
fi
status="$(git status --short)"

temporary="$(mktemp "$output_dir/.mtgallium-advisory-context.XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT
{
    printf '# MTGallium advisory context\n\n'
    printf -- '- Generated at (UTC): `%s`\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf -- '- Branch: `%s`\n' "$branch"
    printf -- '- MTGallium HEAD: `%s`\n' "$head"
    printf -- '- Argentum gitlink: `%s`\n' "$argentum_revision"
    if [[ -z "$status" ]]; then
        printf -- '- Working tree: CLEAN\n'
    else
        printf -- '- Working tree: DIRTY\n\n'
        printf '```text\n%s\n```\n' "$status"
    fi

    printf '\n## How to use this packet\n\n'
    printf '%s\n' 'This local conceptual snapshot corresponds to the public source identity recorded above. Public source remains authoritative for implementation details; this packet is not a substitute for current source. Private research evidence is separate and may supplement it independently.'

    printf '\n## Project orientation\n\n'
    cat AGENTS.md

    printf '\n\n## Current architecture\n\n'
    cat docs/architecture.md

    printf '\n## Recent repository history\n\n'
    git log -12 --pretty=format:'- %h %ad %s' --date=short
    printf '\n'
} > "$temporary"
mv -- "$temporary" "$output"
trap - EXIT
printf 'Wrote %s\n' "$output"
