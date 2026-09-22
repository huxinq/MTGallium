# Evidence and research boundaries

The [research workbench](../research-workbench.md) operates on caller-chosen
inputs and output paths. The [architecture](../architecture.md#module-responsibilities)
maps implementation owners.

## Recording

Ordinary game records use the adapter's accepted-decision counter. Decision logs
contain player-view information and accepted choices. Replay snapshots contain
privileged referee state.

## Results and partial work

At a decision or time limit, `GameResult.payoffs` is null (`None` in Python).
Exceptions and recording failures propagate to the caller, leaving completed files available.

## Source context

Keep the source revision and any uncommitted diff, Argentum revision, settings,
and inputs with a result. `--no-build` uses the last compiled classes; record their
source when it differs from the current checkout. Read historical records with
their [producing source](../history.md).
