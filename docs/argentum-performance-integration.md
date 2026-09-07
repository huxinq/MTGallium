# Argentum performance integration

The gitlink pins upstream commit
`3757f6bd064e2f057401fac2a4226c5afa5cd011`. It descends from
`5021faf88093a93091e4de7914fbe0f411499d58`, which integrated all five
reviewed performance contributions:

| Contribution | Upstream PR |
|---|---|
| Hidden-world construction and validation | [#2247](https://github.com/wingedsheep/argentum-engine/pull/2247) |
| Projected characteristic collections | [#2248](https://github.com/wingedsheep/argentum-engine/pull/2248) |
| Component containers and combat cleanup | [#2249](https://github.com/wingedsheep/argentum-engine/pull/2249) |
| Ability lookup and activation prevention | [#2250](https://github.com/wingedsheep/argentum-engine/pull/2250) |
| Reusable services for independent AI players | [#2251](https://github.com/wingedsheep/argentum-engine/pull/2251) |

All 19 files changed by the former local performance integration
`f8874f795f54543e98b0e9602ca8eea73a986df6` relative to `12589ae4a22e`
were byte-identical at `5021faf`. That revision also incorporated
upstream Lorwyn/Ravnica work, including linked-exile state and draw replacement
changes. The current pin additionally incorporates structural suspension and
paired question/answer continuations, object incarnation and paused-spell
lifecycle handling, deterministic routing and response freshness protections,
and further card/rules fixes. It is not a performance-only engine update.

The canonical remote in `.gitmodules` can supply this commit directly.
No local integration ref or owner-provided bundle is required.
MTGallium's factory reuse and opt-in singleton-selection configuration remain
unchanged by this convergence.

Authoritative fingerprints normalize question routing IDs inside active and
saved nested suspensions. Question/answer payloads, distinct routing
relationships, object incarnations, and allocator state remain fingerprinted.
Synthetic scenario setup now clears the continuation stack that owns a paused
question instead of assigning the removed `pendingDecision` field.

Existing performance measurements retain their original MTGallium revision,
engine revision, configuration, and runtime-bundle identities. They do not
become measurements of this upstream pin. New evidence records the new source
and gitlink through the existing provenance capture. Fresh synthetic replay
checks establish current round-trip compatibility; they do not establish
compatibility of every historical replay with the updated engine state schema.
