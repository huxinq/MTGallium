# Learning from retained calibration decisions

`admitCalibrationReferenceCloning` derives behavioral-cloning inputs from
retained Search Teacher calibration games. It reads the saved safe policy
inputs and accepted searched actions; it does not reconstruct features from
engine state or generate new gameplay. The learned target is the reference
teacher's accepted action, not a terminal outcome or an optimal action.

The adapter authenticates the parent artifact manifest, every consumed file,
the plan and report's research-run binding, and each selected game checkpoint.
It selects the reference player's p0 leg from every reported pair, without
filtering by wins, losses, or action quality. An invalid selected game rejects
admission by default. An explicit generation-limit exclusion mode can remove
whole games whose first admission failure is action-generation exhaustion.
It retains the complete attempted population, validation, and named exclusions;
every other failure still rejects the run. No partial game or truncated candidate
family is admitted, and this selected population is not claimed to represent all
teacher positions. A recorded successful canonical replay verification must remain
attached to that replay's exact hash. Hash verification alone does not set the
replay-verified flag.

Calibration can have Search Teacher on both sides. `CorpusEntry.teacherSeat`
therefore explicitly names the perspective supplying the labels. The original
game summary still records both SEARCH players and no unique search seat.
The public corpus validator requires the declared teacher to be an actual
SEARCH player and checks the trajectory perspective and every decision actor
against that seat. For dual-Search games, the authenticated teacher policy
supplies the shared-tree planner in example provenance; the game-wide planner
summary remains null. Omitting the new field preserves the historical serialized
corpus bytes, dataset identity and unique-searcher requirement. When present,
the field participates in the dataset identity.

The derived admission scope uses the authenticated calibration plan hash,
source revisions, deck, teacher budget and default evaluator. It does not
pretend that the calibration plan is a historical frozen-profile artifact.
The narrow path currently supports the reference policy's default evaluator;
other targets require an explicit extension. Historical opponent-only fields remain
opaque in the complete retained plan and original run identity; the selected
teacher descriptor is strictly decoded. Unsupported teacher fields reject admission.

The existing `BehavioralCloningAdmission` remains the extraction authority.
It verifies bounded information, represented history and knowledge, exact
candidates, planner evidence, and that the selected search winner equals the
accepted semantic response. Forced singleton passes do not become search
labels. Privileged replay and search diagnostics do not become model features.

The derived corpus preserves the original trajectory source identity. Its
separate lineage also records the committed admission-source provenance,
parent run/manifest, teacher identity and whole-pair groups. Keep those groups
together in subsequent training and validation, including across candidate
comparisons that share a source pair. Game-wide cost metadata is not a
measurement of the chosen teacher seat alone. A finalized derived research-run
manifest binds the admission source, parent, dataset, lineage, full retained
plan, attempted population, exclusions and validation; the original corpus
identity remains unchanged. Relocated sidecars preserve their original safe
trajectory locator as bound metadata without dereferencing it; the actual
trajectory remains checked by its registered local path, hash, game and policy.

Corpus admission establishes trustworthy imitation examples. It does not
establish teacher optimality, learner quality, a match between root and rollout
state distributions, or stronger deployed play. Those remain separate
training and gameplay questions.
