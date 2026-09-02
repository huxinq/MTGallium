# Publication decision record: license and IP boundary

Status: approved for the initial source publication. The root `LICENSE` grants
MIT rights for original MTGallium first-party code only. It does not grant rights
in Argentum, Wizards of the Coast / Magic: The Gathering material, or any other
third-party material.

## First-party candidate paths

The exporter treats the authored Kotlin/JavaScript/Gradle source under `agent`,
`integration`, `quality`, and `evaluation` as candidate first-party source,
subject to the exclusions below. Generated historical evidence is not
first-party source merely because it is tracked.

## Excluded pending review

- `fixtures/decks/mono-red-standard-2026-07-30.json` (deck-list provenance).
- The Mono-Red teacher profile and search-grid resource (card/deck material).
- `privileged-replay-v1.jsonl` (canonical/privileged replay-shaped fixture).
- All `reports/**`, including safe-looking derived artifacts and any canonical
  replay, seed, referee, review, or model payload.
- Maintained Argentum source, card definitions, tests, assets, authorship, and
  licenses. It remains a separate gitlink and needs its own audited public fork.

## Publication boundary

The initial public source redistributes no Argentum source or third-party
card-data asset. The maintained Argentum fork preserves its own MIT license and
notices. Included examples and fixtures are subject to the final public scan;
questionable material is excluded rather than treated as sanitized private
evidence. MTGallium is unofficial and is not affiliated with or endorsed by
Wizards of the Coast.
