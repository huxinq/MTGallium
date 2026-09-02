# Clean public-source preparation

This directory is the private, reviewable authority for producing a clean-root
public-source candidate. It records the approved allowlist and transformation
boundary used for publication.

Run `node tools/public-source-export.mjs export --output <empty-directory>`
from a clean preparation checkout. The exporter reads only Git-tracked source,
uses `publication/public-source-policy.json` as an allowlist, writes transformed
public documents, initializes a new single-commit Git repository, and writes a
private provenance record next to (not inside) the output directory.

The policy intentionally excludes all historical reports, private operational
tools, numbered research notes, and the named fixture/resource files requiring
Wizards/Magic provenance review. `scan` verifies an already-exported candidate.
The private mapping record must remain outside any candidate repository.

The old private checkout remains historical authority for private experimental
SHAs and artifacts. After public cutover, the clean public source repository is
the authority for future first-party source development; no historical evidence
is rebound to the clean root.
