# Search adapter cost profile

`SearchAdapterProfile.java` runs four existing tactical roots with a synthetic
60-card deck and the default production Search Teacher configuration (8
particles, 64 simulations, 32-decision horizon). It measures initial policy
and belief construction separately from selection. Each repetition starts from
a fresh root and session; one repetition per root warms up before JFR starts.

This is a cost-location diagnostic, not a strength test, a full game, or an
isolated throughput comparison. It excludes fixture preparation from stage
timings and does not measure sequential belief updates between real decisions.
The JFR also contains fixture preparation and report writing between stages;
filter samples to the `mtgallium.AdapterProfileStage` intervals on their event
thread. Allocation sample weights estimate attribution, while reported stage
byte counts use the current-thread allocation counter.

Commit source first and use a fresh external evidence destination. Build the
current runtime classpath with:

```sh
MTGALLIUM_PROFILE_CLASSPATH=/private/setup/classpath.txt \
  bash tools/mtgallium-gradle \
  :evaluation:search-teacher:searchAdapterProfileClasspath \
  -I tools/diagnostics/search-adapter-profile.init.gradle
```

Compile the Java source with JDK 21 against that classpath, then run its main
class with the compiled diagnostic directory prepended to the same classpath:

```text
SearchAdapterProfile <repository-root> <relative-evidence-directory> <repetitions>
```

Set `MTGALLIUM_PUBLIC_SOURCE=1` and an external
`MTGALLIUM_PRIVATE_EVIDENCE_ROOT`. Use owner-provided durable execution for a
substantial run. The diagnostic refuses dirty source, an engine/gitlink
mismatch, source changes during execution, and Java instrumentation agents.
It retains source provenance, full policy identities, deck, search results,
belief diagnostics, CPU/allocation/timing measurements, JFR, and harness source
through the existing research-run artifact authority. All repeated outcomes
remain diagnostic search results; no terminal-game population is implied.
