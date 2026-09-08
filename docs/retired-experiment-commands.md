# Retired experiment commands

The current workbench drops 33 historical experiment commands and their exclusive
implementations. Existing run artifacts retain their original producer identity.
To reproduce a historical experiment, use its recorded MTGallium revision,
Argentum revision, material configuration and private inputs. Do not rerun it at
a newer revision and attribute the result to the old source.

The last source revision containing all commands below is
`f284b95e5c40b17ac193edfe5bbe7712a6f46e40`. This is a source recovery point,
not a replacement for an artifact's recorded producer revision. Retired names
fail normal CLI validation; they do not route to newer experiments.

| Retired family | Commands |
| --- | --- |
| Historical tournaments and remediation | `tournament-amendment`, `tournament-v3-calibrated`, `baseline-factorial-tournament`, `baseline-factorial-smoke`, `tournament-fallback-diagnostic`, `tree-reuse-validation`, `tournament-performance`, `tournament-remediation`, `tournament-remediation-check`, `tournament-remediation-probe`, `tournament` |
| Early calibration launchers | `calibrate`, `pilot-calibrate` |
| Fresh-world and standalone-mana experiments | `root-search-evidence-repeatability`, `standalone-mana-timing-experiment`, `issue-0013-stage-a`, `issue-0013-stage-b-panel`, `issue-0013-stage-b`, `issue-0013-stage-b-reviewed-secondary`, `issue-0013-blinded-review` |
| Issue-specific neural diagnostics | `neural-capacity-diagnostic`, `neural-memorization-diagnostic`, `neural-saturation-trajectory-diagnostic`, `neural-candidate-update-scale-diagnostic`, `neural-population-scaling-diagnostic`, `neural-stability-boundary-diagnostic`, `neural-final-boundary-diagnostic`, `neural-cohort-continuation-preflight`, `neural-cohort-continuation-diagnostic`, `neural-anchor-crossing-preflight`, `neural-anchor-crossing-diagnostic`, `neural-held-out-generalization-preflight`, `neural-held-out-generalization-diagnostic` |

The CLI options `--root-limit` and `--repetitions` belonged only to the retired
fresh-world and standalone-mana experiments and are also removed. Root limits in
current typed research plans are unchanged. The four dedicated Gradle tasks for
the corrected-neural, cohort-continuation, anchor-crossing and held-out neural
preflights are removed with their experiment-only tests.

Two unregistered VisibleV2 coefficient-calibration producers are also removed.
Historical VisibleV2 plan/report schemas remain because current action-kernel and
TacticalV3 code decodes them. Shared policy specifications, game descriptors,
operational validity checks, pair-index bootstrap arithmetic, compact game
records and artifact digests remain in independent source files. Current latency
and throughput measurements retain their existing profile and summary helpers.

The current arena, Search Teacher calibration, replay verification, generic neural
behavioral cloning, terminal research workflows, and information-state/search
contracts remain supported. Generic regression witnesses formerly housed in
retired tournament tests move with the shared code; experiment-only tests leave
with their implementations. No historical evidence is copied into public source.
