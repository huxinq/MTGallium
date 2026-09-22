# Information-set planning algorithms

This module depends only on `infoset-semantics`. It owns information-set search,
hypothesis and particle algorithms, rollouts, terminal continuations, and root
dispatch.

Each search starts with fresh statistics. The exact within-search transition cache
reuses computation for one search only. `RootActionSelector` chooses from a
captured `DecisionSiteRequest`; rules-forced passes, optional singleton actions,
direct-policy actions, and searched actions remain distinct.

The host constructs `InformationSetSearch` and supplies model defaults,
`LeafEvaluationConfig`, and `LeafValueSource`.
