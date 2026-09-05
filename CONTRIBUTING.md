# Contributing to MTGallium

The current public source is authoritative for new first-party source changes.
Follow the [semantic authority rule in AGENTS.md](AGENTS.md#semantic-authority)
for when to seek owner review, and preserve its research-critical invariants
and working defaults, including the restriction on changing the Argentum pin.

Use focused tests while developing and run `just check` before proposing a
change where the public dependency gate permits it. Passing tests alone do not
establish a research conclusion.

Never attach real private replay, referee-state, hidden-information, seed, or
private evidence material to a public issue or pull request. Use independently
reviewable synthetic fixtures for public tests.

Contributions are submitted under the project MIT License (inbound=outbound).
Use a Developer Certificate of Origin sign-off on every commit, for example
`git commit -s`. By contributing, you certify that you have the right to submit
the change under those terms. No CLA is currently required. Do not submit
material whose redistribution terms are unclear.
