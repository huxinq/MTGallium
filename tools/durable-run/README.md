# Durable execution

`research durable` runs a foreground command through Node.js and systemd.
Codex completion delivery and the tests need its WebSocket client; install it with
`npm ci --prefix tools/durable-run`.

Start with the [durable runner guide](../../docs/workbench/durable-runs.md).
`node tools/durable-run/durable-run.mjs --help` exposes the same interface without
Python. Run its tests with `node --test tools/durable-run/test/*.test.mjs`, also
included in `just research-tools-check` and public CI.

Keep generated state, logs and outputs outside the checkout.
