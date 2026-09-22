# Conditioned belief maintenance

`agent/argentum-policy` owns `ArgentumParticleBeliefBackend`. It maintains a player's particle population
from an `ArgentumSearchWorld`, known decks, configured belief mode, opponent
action distribution, and private-choice selector.

For `POLICY_CONDITIONED_V1`, each hypothetical world advances through the
observed action, then keeps descendants that agree with the viewer's newly
observed `InformationStateRepresentation` and represented exact knowledge. The
surviving weights are normalized before copying-only resampling. A private
opponent response is selected from that opponent's information; only its
legitimately observed consequences condition the viewer's population.

The tracker exposes a `BeliefSnapshot` with weighted hypothesis materialization
and query views. Joint hand requirements are evaluated within each hypothesis,
which retains correlations that separate marginal estimates would lose. Snapshot
queries consume no randomness and contain no mutable worlds.

`POLICY_CONDITIONED_V1` requires `SEQUENTIAL_B_V1`. A depleted population,
support mismatch, or reconstruction requirement raises a typed maintenance
failure. A factual continuation forks both its `ArgentumSearchWorld` and the
tracker, and requires the same current information-state digest.
