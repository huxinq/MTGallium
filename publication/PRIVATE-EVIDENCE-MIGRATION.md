# Private-evidence externalization seam

No existing evidence population is moved by this preparation. In particular,
the 10+ GB historical population remains at its recorded private paths and
retains its original private source SHAs.

`PrivateEvidencePaths` is the path authority used by the Kotlin Search Teacher
`EvidenceStore` and the focused Argentum evaluator. In a normal private
checkout, absence of configuration retains the historical `reports/**` layout
for compatible readers. In public-source mode, set both:

```text
MTGALLIUM_PUBLIC_SOURCE=1
MTGALLIUM_PRIVATE_EVIDENCE_ROOT=/absolute/path/outside/the/source/checkout
```

The second path is required and an in-checkout path is rejected. Historical
relative layout is preserved below that root (`search-teacher/work`,
`search-teacher/latest`, `argentum/latest`), so an owner-selected private
evidence store can be introduced without changing artifact identities or bulk
copying existing data.

Private-only durable-run, replay-inspector, and tournament-monitor operations
remain excluded from the initial candidate because they still embody private
operational/evidence assumptions. They must be migrated to this authority (or
replaced by public-safe tools) before any future public inclusion.

Owner migration step: choose and provision the private evidence-store location,
set the two variables for source work that can write evidence, and only then
perform a separately reviewed copy/migration if historical data needs to move.
