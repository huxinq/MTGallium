# MTGallium

MTGallium is an independently developed research codebase for planning and
learning in Magic: The Gathering where each policy acts only on information its
player could legitimately know. It searches over the possible hidden states
(information-set search) on a pinned
[Argentum](https://github.com/wingedsheep/argentum-engine) engine. Current
experiments use a narrow Mono-Red scope.

## Try it

Install Git, JDK 21, Python 3 and `just`, then:

```bash
git submodule update --init --recursive
work=$(mktemp -d)
python3 tools/mtgallium-research games examples/research-games.json "$work/game"
python3 tools/mtgallium-research show "$work/game/results.json"
```

The [research quick start](docs/research-workbench.md) continues with live games
from Python. The [documentation index](docs/README.md) lists the rest by task.

## Build and test

`just check` runs the public tests. `just architecture-check` checks module
dependency boundaries; `just policy-check` runs the policy and research tests
only. The Argentum gitlink pins the engine revision; see its
[integration record](docs/history/argentum-performance-integration.md).

Keep private replays and research results outside the checkout.

## License, rights, and contributions

Original MTGallium first-party code is available under the [MIT License](LICENSE).
Third-party dependencies and material, including Argentum and any Wizards of the
Coast / Magic: The Gathering material, remain subject to their own rights and
licenses and are not granted under the MTGallium MIT license. MTGallium is
unofficial and is not affiliated with or endorsed by Wizards of the Coast.

Contributions are under MIT inbound=outbound terms with DCO sign-off. See
[CONTRIBUTING.md](CONTRIBUTING.md).
