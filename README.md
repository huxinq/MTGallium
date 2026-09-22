# MTGallium

MTGallium is an independently developed research codebase for information-safe
planning and learning in Magic: The Gathering. Current experiments use a narrow
Mono-Red scope.

The native policy uses information-set search over a pinned Argentum engine.

For research, use the [direct library and CLI](docs/research-workbench.md):
normal `build`, `games`, `fit`, `predict`, `encode`, and `show` commands, or an
ordinary Kotlin main or Python program. Dirty-source builds and caller-chosen
resource settings are supported.

The [neural learner](docs/neural-policy.md) runs directly with PyTorch and ordinary
JSON, with optional production-format ONNX export.

Start with the [documentation task guide](docs/README.md) to understand the system,
run or read an experiment, or implement a change. The [architecture](docs/architecture.md)
introduces the modules and boundaries. Use the [white paper](docs/whitepapers/README.md)
for the mathematical model and the [glossary](docs/glossary.md) for shared definitions.

## Build and test

Install Git with submodules, JDK 21, and `just`, then run:

```bash
git submodule update --init --recursive
just check
```

The Argentum gitlink pins the engine revision. See its
[integration record](docs/history/argentum-performance-integration.md).
`just architecture-check` checks dependency boundaries; `just policy-check`
runs policy and research tests.

Keep private replays and research results outside the checkout.

## License, rights, and contributions

Original MTGallium first-party code is available under the [MIT License](LICENSE).
Third-party dependencies and material, including Argentum and any Wizards of the
Coast / Magic: The Gathering material, remain subject to their own rights and
licenses and are not granted under the MTGallium MIT license. MTGallium is
unofficial and is not affiliated with or endorsed by Wizards of the Coast.

Contributions are under MIT inbound=outbound terms and require DCO sign-off; no
CLA is currently required. See [CONTRIBUTING.md](CONTRIBUTING.md).
