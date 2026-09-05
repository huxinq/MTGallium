# Local Argentum performance integration

The gitlink currently pins local integration `80ba6e68b218d9ce5fadbc34295006ff0376adac`
on branch `codex/local-performance-integration` in the engine submodule.
It merges these five reviewed contribution heads from base `12589ae4a22e`:

| Contribution | Head | Review |
|---|---|---|
| Hidden-world construction and validation | `ca8b9faf25b1` | Upstream #2247 |
| Projected characteristic collections | `3e47a0e32a83` | Upstream #2248 |
| Component containers and combat cleanup | `10afcbe70e2e` | Upstream #2249 |
| Ability lookup and activation prevention | `914c1563934c` | Upstream #2250 |
| Reusable services for independent AI players | `b7bd529fbe9e` | Review fork #34 |

The first four groups reproduce the previously combined engine source
`b24f2e1191c7`. Adding the factory changes only `AIPlayer.kt` and its focused
regression test relative to that source. MTGallium's heuristic annotator now
uses one lazy factory throughout its world lineage and requests a fresh player
for every action or decision response. Strategy memory and simulator responder
state therefore remain local to each call.

The integration commit is local, and `.gitmodules` keeps the canonical upstream
URL. The existing checkout contains the commit. To transfer this unmerged pin
to another checkout before upstream realignment, supply the integration ref
from this local engine repository (or an owner-provided mirror/bundle) before
running `git submodule update`. The canonical upstream remote alone cannot
currently initialize this pin. No new upstream submission is implied by this
local adoption.

Once upstream incorporates the contributions, replace the gitlink with a
reviewed upstream commit that supplies the same capabilities and rerun the
public checks. Remove this temporary setup note at that point. Exact commit
ancestry may differ if upstream squashes or revises the PRs; check the resulting
source rather than assuming merge status establishes equivalence.

Existing performance measurements retain their original MTGallium revision,
engine revision, configuration, and runtime-bundle identities. They do not
become measurements of this combined pin. New evidence records the new source
and gitlink through the existing provenance capture.
