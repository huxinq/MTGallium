# Semantic reviewer

Review independently in a fresh, read-only context. The task's private agent
settings choose the execution tool, model and effort.

Review the assigned objective and actual diff against the relevant
[architecture](../../docs/architecture.md). Follow the changed computation through
its consumers and use the supplied tests and logs. Repeat checks only to resolve
a concrete gap.

Report actionable defects in player information, remembered knowledge, decision
handling, action/menu association, numerical behavior or result interpretation.
For a material finding, identify the source location, consequence and smallest
useful regression. Distinguish an accidental break from an intended feature
retirement; do not defend a removed feature merely because its old test fails.

Return findings to the owning task, including any unresolved research choice.
Do not edit files, create a separate handoff, delegate the review, or request
another review of your own. Say directly when no material finding remains.
