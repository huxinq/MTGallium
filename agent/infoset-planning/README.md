# Information-set planning algorithms

Planning types live in `org.mtgallium.agent.infoset.planning`; the underlying
information and action contracts stay in `org.mtgallium.agent.infoset.core`.
Existing serializer names remain pinned to preserve wire compatibility.

This module depends only on `infoset-semantics`. It owns information-set search,
hypothesis and particle algorithms, rollouts, and root
dispatch.

Each search starts with fresh statistics. The exact within-search transition cache
reuses computation for one search only. `RootActionSelector` chooses from a
captured `DecisionContext`; rules-forced passes, optional singleton actions,
direct-policy actions, and searched actions remain distinct.

The host constructs `InformationSetSearch` and supplies model defaults,
`LeafEvaluationConfig`, and `LeafValueSource`.
