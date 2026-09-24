# Argentum engine evaluation

A standalone check of the pinned Argentum engine for adoption, using only
Argentum libraries and no MTGallium agent code. It runs:

- static and contract probes: card-pool resolution, seeded replay determinism,
  hidden-zone masking, state digests, and the bundled trainer's branching and
  MCTS smoke run;
- reliability corpora of complete games on a diagnostic deck and the Mono-Red
  deck, with and without mulligans;
- fork, step and observation timings.

It then gives an `ADOPT`, `CONDITIONAL` or `REJECT` verdict for the rules core,
the direct Gym interface and the bundled trainer, and writes `report.json` and
`report.md` to `argentum/latest` under `MTGALLIUM_PRIVATE_EVIDENCE_ROOT`. Without
that variable it writes to `reports/argentum/latest` in the checkout, which
`MTGALLIUM_PUBLIC_SOURCE=1` forbids.

The entry point is `ArgentumEvaluationKt` (`--suite full|smoke`, `--seed`,
`--games`, `--mulligan-games`). Its Mono-Red deck is the published list in
[`fixtures/decks/mono-red-standard-2026-07-30.json`](../../fixtures/decks/mono-red-standard-2026-07-30.json),
with its source.
