# History event equivalence

`ArgentumSearchWorld.create` selects both a history event-order mode and an
object-reference mode. The defaults are `LEGACY_ENGINE_ORDER_V1` and
`LEGACY_SNAPSHOT_V1`.

## Qualified simultaneous untaps

`QUALIFIED_TURN_UNTAP_V1` recognizes the automatic END/CLEANUP-to-UPKEEP
transition with one turn marker, at least two distinct untaps of the next active
player's tapped objects, and the final upkeep marker. The adapter projects each
recognized untap into player-visible vocabulary, sorts those projected entries,
then assigns event numbers and updates the history commitment. Raw engine events,
raw entity IDs, hidden identities, unrelated events, and ordered choices retain
their order.

`QUALIFIED_TURN_UNTAP_V2` also covers the cleanup-resume path. It canonicalizes
only the later qualified untap group; completed cleanup events remain ordered.
The prefix is eligible only when none of its events changes a grouped object's
zone, tapped state, or phased presence.

## Remembered battlefield references

`REMEMBERED_BATTLEFIELD_V1` uses a viewer-local knowledge-object key when the
same battlefield incarnation is visible before and after a transition. Eligibility
is fixed before the event batch. Zone changes, shuffle-invalidated continuity,
missing handles, and missing incarnation information use the snapshot reference.

`QUALIFIED_OBSERVED_OBJECTS_V2` adds qualified resolution sources and
canonicalizes unordered combat assignments while retaining distinct remembered
objects and ordered damage choices. Handles never rebind to a later incarnation;
raw entity IDs and incarnations remain inside the trusted adapter.

Forks retain the selected modes and their eligibility state. History commitments
therefore describe the projected event sequence for that mode.
