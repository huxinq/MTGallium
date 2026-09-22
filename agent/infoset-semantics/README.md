# Information-set semantic language

This module owns `PlayerObservationSnapshot`,
`InformationStateRepresentation`, represented knowledge, persistent history,
semantic choices, admitted decision sites, and safe policy/evaluator/belief-query
contracts. `InformationStateRepresentationDigest` computes representation
digests.

It has no project dependencies. Search worlds, particle algorithms, and
continuation implementations live in `agent/infoset-planning`; engine projection
lives in `agent/infoset-argentum`.
