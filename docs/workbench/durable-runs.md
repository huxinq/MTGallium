# Retain a long-running command

The durable runner executes a **foreground command** in a Linux user service and
retains its command, logs and process outcome.

Requirements are an available user systemd manager and Node.js 18+. Completion
delivery to a Codex task additionally needs `codex queue --thread`. Shutdown or
reboot can stop the run.

## Launch once

A shell shortcut is convenient for interactive use:

```bash
research() { python3 tools/mtgallium-research "$@"; }
research durable --help
```

For example, retain an ordinary direct fit without external notifications or a
Codex wake:

```bash
research durable launch --name kernel-fit \
  --output /absolute/private/model.json --no-wake --no-notify --json -- \
  python3 "$PWD/tools/mtgallium-research" fit \
  /absolute/private/roots.json /absolute/private/model.json 0.001
```

The child must remain in the foreground. Arguments after `--` are forwarded
without shell reconstruction; a shell function is not executable in the service,
so the example uses Python directly. `--no-build` uses current compiled files.

For task completion delivery, select `--thread UUID` or provide `CODEX_THREAD_ID`
and use `--require-wake`. Status records computation and delivery separately.

`--workdir PATH` chooses the child directory. `--env NAME` captures an additional
required environment variable, such as `JAVA_OPTS`. `--output PATH` is repeatable
and records expected result locations. `--state-root PATH` and `--log PATH`
choose operational storage.

## Read retained state

```bash
research durable list --json
research durable status RUN_ID --json
research durable logs RUN_ID --lines 60
research show /absolute/private/model.json
```

Use the same `--state-root` for a nondefault operational root. A client timeout
after submission can leave an accepted service running; inspect that run before
submitting another.
