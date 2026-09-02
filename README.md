# MTGallium

MTGallium is an experimental, independently developed research codebase for
information-safe planning and learning in Magic: The Gathering. Current
experiments use a deliberately narrow frozen Mono-Red scope. Source publication
is intended for technical collaboration. Current results do not establish
optimal play, general Magic competence, a public benchmark, or a novel research
contribution.

The production policy is a hand-authored information-set Search Teacher over a
pinned Argentum engine; learned-policy work is exploratory rather than the
production policy.

The system separates engine-private refereeing from policy-visible information,
represented player knowledge, semantic action identity, hidden-world search,
and evidence tooling. `agent/infoset-argentum` is the trusted boundary allowed
to inspect full Argentum state; policy-facing code receives only safe projections.

## Build and test

Install Git with submodules, JDK 21, and `just`, then run:

```bash
git submodule update --init --recursive
just check
```

The exact Argentum gitlink is supplied by the
[maintained MTGallium compatibility fork](https://github.com/huxinq/argentum-engine)
identified in `.gitmodules`. It is not upstream `main`; do not replace the pin
with another revision. `just architecture-check` and
`just search-teacher-check` are focused validation entry points.

Canonical replays and private experiment evidence are intentionally not part of
this source tree. Public artifacts must be separately derived, privacy-reviewed,
and reproducible from permitted inputs.

## License, rights, and contributions

Original MTGallium first-party code is available under the [MIT License](LICENSE).
Third-party dependencies and material, including Argentum and any Wizards of the
Coast / Magic: The Gathering material, remain subject to their own rights and
licenses and are not granted under the MTGallium MIT license. MTGallium is
unofficial and is not affiliated with or endorsed by Wizards of the Coast.

Contributions are under MIT inbound=outbound terms and require DCO sign-off; no
CLA is currently required. See [CONTRIBUTING.md](CONTRIBUTING.md).
