# Frozen material value

The V2 material evaluator is frozen for the `horizon16` anchor and league opponent.
It reads life, hand counts and visible battlefield characteristics through the
old information view. Its formula and defaults remain in `MaterialEvaluator`.

## Search use

The frozen anchor uses bounded depth-16 rollouts and V2 at the cutoff. The public
search interface retains its leaf and rollout settings for reproducing the
anchor's machinery. Learned values and their experiments are archived at
`archive/old-view-20260928`; historical results require their original engine pin.
