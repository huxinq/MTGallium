# Durable runner

The durable runner executes a **foreground command** in a Linux user service and
retains its command, logs and process outcome.

Requirements are an available user systemd manager and Node.js 18+. Install the
runner dependencies once with `npm ci --prefix tools/durable-run`. Completion
delivery to a Codex task additionally needs `codex app-server proxy`, the
running local Codex app-server, and `codex queue` for the fallback below. Shutdown
or reboot can stop the run.

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
and use `--require-wake`. Completion messages steer an active turn or start a turn
when the task is idle, without changing its goal status. A compaction or review turn
cannot be steered; the message is then queued with `codex queue --thread`, and status
records that fallback. Status records computation and delivery separately.
An uncertain delivery is not automatically retried. Runner updates apply to new
launches; an executor already running retains its loaded delivery code.

To wake a task on another machine, use `--wake-codex /absolute/forwarding-script`
with an explicit `--thread UUID` from that machine. The executable must forward
Codex arguments and standard input/output, including `app-server proxy` and the
queue fallback. A small Python script can use `os.execvp('ssh', ['ssh', '-T',
'-o', 'BatchMode=yes', 'HOST', shlex.join([REMOTE_CODEX, *sys.argv[1:]])])`.
Quote the complete remote command as shown; completion text must remain literal.
The destination must expose a running app-server control socket. `--require-wake`
checks CLI support through the forwarding script, not server connectivity or
task availability. Verify the destination separately before relying on delivery.
The same delivery accounting applies: no automatic retry after an ambiguous failure.
Wake messages identify the execution host so the receiving task reads its logs
and outputs there over SSH.

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
