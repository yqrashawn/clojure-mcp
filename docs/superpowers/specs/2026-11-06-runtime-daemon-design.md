# clojure-mcp runtime daemon: shared JVM, shadow-cljs tools, zgh setup

Status: draft for approval · 2026-11-06 · repo: yqrashawn/clojure-mcp (fork, v0.6.0)

## Intent

cchp (`~/workspace/home/claude-code-http-proxy`) embeds clojure-mcp and adds
features on top of it. Agents served by cchp can therefore drive a project's
JVM and shadow-cljs runtimes. Agents not served by cchp cannot: other people's
Claude Code or Codex sessions working on **zgh** (`~/workspace/home/zgh`) have
no good way to reach zgh's JVM nREPL or its Node runtime.

Goal: move cchp's Clojure-runtime features into this fork. Then a project
(zgh first) commits a few config files, and any MCP-speaking agent on that
machine gets:

- eval in the project's **JVM** (`.nrepl-port`), and
- eval in its **shadow-cljs runtimes** (zgh: build `:dev`, a `:node-script`
  Node process, nREPL in `.shadow-cljs/nrepl.port`),
- plus the existing editing toolkit.

All of this runs from **one shared JVM per machine**, not one JVM per agent
session.

Why one JVM: measured on this fork against zgh, `clojure -X:mcp` over stdio
takes **15–18 s** to its first response and holds ~135 MB+. With
per-session stdio, five agents mean five cold starts and five JVMs.

## Goals

1. Port from cchp (`cchp/module/mcp_server/clojure_mcp.clj`):
   - `cljs_eval`: one-shot eval in a shadow build's live runtime via
     `shadow.cljs.devtools.api/cljs-eval` (args `code`, `build`, `runtime_id`,
     `port`, `timeout_ms`).
   - `cljs_build_status`, `cljs_project_state`, `cljs_list_ns`: read shadow's
     own worker and build-history state.
   - **Eval deadline**: `clojure_eval` (and anything else that waits on an
     nREPL with no timeout) is abandoned after `timeout_ms` + 60 s grace, with
     a message telling the agent the REPL is wedged. Origin: mydeck:18b6a67e,
     where an agent hung for hours.
   - **No-nREPL help**: when `clojure_eval` resolves no port, return the
     `list_nrepl_ports` scan, the "other worktree's port" warning, and how to
     start this project's REPL.
   - `clojure_eval` description text covering multi-REPL use and the cljs
     recipe.
2. A **multi-project HTTP daemon**: one process serves many projects, each
   with its own nREPL state, config, and scratch pad.
3. A **babashka stdio shim** that every MCP client launches. It starts or
   finds the daemon and relays stdio↔HTTP, tagging every request with its
   project.
4. **LLM sub-agent tools** (`dispatch_agent`, `architect`, `code_critique`,
   `clojure_edit_agent`) turn on automatically when a local cchp answers, or
   when a model/API key is configured. Otherwise they are off.
5. zgh commits the client config, and an agent working on zgh needs no other
   setup.

## Non-goals

- Switching cchp to this fork, or deleting cchp's copy (operator: "not yet").
- Contributing upstream (allowed later, not a goal).
- cchp-only machinery: `clojure-mcp_` name prefixes, cchp's in-process
  model-pool adapter, OTel logging.
- Remote or multi-user access. The daemon serves localhost only.

## Anti-goals (a failure even if it "works")

- An agent silently evaluates in **another worktree's JVM**. Ports must
  resolve per project, and the help text must keep the cross-worktree warning.
- The shim hangs a client's startup indefinitely (no daemon, wrong Java, a
  build error). It must fail with a readable error inside the client's MCP
  connect timeout.
- Two projects pinning different fork versions corrupt each other through one
  daemon.
- Hints that lie: suggesting `npx shadow-cljs watch` in zgh (zgh forbids npm),
  or a `clj -M:nrepl` alias that doesn't exist.

## Constraints

- Fork: Clojure, MCP Java SDK **1.1.3** (has `McpTransportContextExtractor`
  and `HttpServletStreamableServerTransportProvider`, which is what cchp uses
  for its `/cwd/<path>/mcp` routing). Jetty 11 is in the `:mcp-http` alias.
- Tool-fn signature `(fn [exchange args clj-result-k])`, built by
  `clojure-mcp.tools/build-all-tools` over an `nrepl-client-atom`, the same
  way cchp builds one tool set per cwd.
- zgh rules (its AGENTS.md): Node is the runtime and Bun only the package
  manager (so "node/bun" means Node here); never npm/npx; ports are the OS's
  (`:nrepl {:port 0}`); AGENTS.md is the only agent-doc file (no CLAUDE.md;
  `.mcp.json` is fine). zgh starts its stack with `bb start:detach`, which
  writes `.nrepl-port` and `.shadow-cljs/nrepl.port`.
- cchp facts (verified 2026-11-06 on this machine): prod HTTP on `:8033`,
  `GET /api/health` → 200. `POST /v1/chat/completions` with
  `metadata.workspace_root` runs Claude Code in that directory: asked to run
  `pwd`, it answered `/Users/yqrashawn/workspace/home/zgh`. Client-side
  `tools` are **ignored**: Claude Code uses its own tools.

## Approaches considered

| | How agents reach clojure-mcp | Trade-off | |
|---|---|---|---|
| A | **bb stdio shim → shared HTTP daemon** | Most code (registry + relay). One JVM per machine; works with every stdio MCP client; config is identical on every machine | **chosen** |
| B | Client connects to the daemon over HTTP (`url: …/cwd/${PWD}/mcp`) | No relay, but depends on each client expanding `${PWD}` (unverified for Codex), breaks when the daemon isn't running, and everyone must install a service | rejected |
| C | Plain stdio, one JVM per session | Least code; 15–18 s and ~150 MB per session, the waste the operator questioned | rejected |

Tool shape: separate tools named as in cchp (`cljs_eval` + 3 shadow tools),
not a `build` flag folded into `clojure_eval` (operator's pick). This keeps
behavior identical to what cchp agents already know, and leaves the door open
for cchp to consume the fork later.

## Design

### 1. Runtime tools (in the fork, every transport)

- New tools under `src/clojure_mcp/tools/shadow/` (or similar):
  `cljs_eval`, `cljs_build_status`, `cljs_project_state`, `cljs_list_ns`.
  Forms and descriptions are ported from cchp. Each evaluates one form on the
  shadow nREPL through the same eval path as `clojure_eval`, so connection
  handling stays in one place.
- **`port` becomes optional** for these tools (cchp requires it). Resolution
  order: arg → `.shadow-cljs/nrepl.port` in the project dir → error that
  names `list_nrepl_ports`.
- **`build` defaults**: arg → project config `:cljs-build` → the single
  active build, if exactly one → error listing the active builds. (cchp
  defaults to `:app`, which is wrong for zgh's `:dev`.)
- `list_nrepl_ports` and port discovery also read `.shadow-cljs/nrepl.port`
  (the standard shadow file), and label it shadow.
- **Deadline** lives in the eval tool itself, so stdio users get it too.
- **No-nREPL help**: start hints come first from a new project config key
  `:start-repl-hint` (string or vector of strings; zgh sets
  `"bb start:detach"`). After that come cchp's auto-detection rules (`repl.sh`
  if executable, bb tasks / deps aliases whose name contains "nrepl", shadow
  builds from `:builds`, `lein repl`). Adjustment: the shadow hint is emitted
  only when no explicit hint is configured, so zgh never sees `npx`.

### 2. Multi-project daemon

- New entry point (for example `clojure-mcp.daemon/start`) on the Streamable
  HTTP transport, bound to **127.0.0.1**, port **0** (OS-assigned).
- A context extractor pulls the project dir from the request path
  `/p/<url-encoded-abs-path>/mcp`, the same idea as cchp's `/cwd/<path>/mcp`.
- **Registry**: project dir → `{nrepl-client-atom, tool-fns, last-used,
  nrepl-port}`, created lazily per project from that project's
  `.clojure-mcp/config.edn`. It is rebuilt when `.nrepl-port` changes (REPL
  restart) and evicted after idle TTL / LRU cap. Ported from cchp's
  `get-or-create!` / `evict-stale!`, with the same per-project build lock.
- **Tool list is server-wide**: the full toolkit (editing included) plus the
  4 cljs tools. Agent tools are listed only when a model backend is available
  (§4). Each handler dispatches to the calling project's tool-fn. Per-project
  `:enable-tools` therefore cannot shrink the list. This is a known
  limitation, because MCP registers tools per server.
- **One daemon per fork version**: state lives under
  `~/.cache/clojure-mcp/daemons/<version>/` (port file, pid, log, lock).
  Different pinned shas get different daemons, which avoids the "two pins
  corrupt each other" anti-goal.
- **Idle exit** after 8 h with no live MCP session, so old versions don't
  pile up.
- **Per-session environment**: the first shim to spawn the daemon must not
  fix everyone's environment. Each shim sends its own environment when it
  opens a session (a private handshake, or a header on `initialize`). The
  daemon uses that session's env for anything env-sensitive: the `bash` tool
  (when it is not running over nREPL), `CCHP_URL`, and API-key lookup for
  agent tools. The daemon's own process env is used only for itself.
- **Auth, even on localhost**: the daemon generates a random token and
  writes it into its `0600` state dir next to the port. The shim sends it as
  a bearer header, and the daemon rejects requests without it. It also
  checks that `Host` is `127.0.0.1:<port>`/`localhost:<port>` and that no
  browser `Origin` is present. This blocks browser pages and DNS rebinding
  from reaching eval, `bash` and file writes. **Agents never see the token**:
  agent ↔ shim is stdio, and only the shim reads the token file and attaches
  it to the HTTP requests it relays. The tools and their arguments are
  unchanged. It guards a new exposure: nREPL's bencode protocol cannot be
  spoken by a browser, but an HTTP daemon can. Other processes of the same
  OS user can still read the file. That is the same trust level nREPL
  already has.
- "Per fork version" means per pinned git sha. Each project's config pins
  the fork's sha, and a shim must reach a daemon running exactly that code.
  Normally every project pins the same sha, so there is one daemon. After a
  pin bump, the new sha gets a fresh daemon and the old one exits once idle,
  so no live session is restarted underneath.

### 3. bb stdio shim

- Lives in the fork under its own root (for example `shim/` with its own
  `deps.edn`), so bb never pulls the JVM deps.
- Startup (target < 100 ms when the daemon is warm):
  1. Resolve the project dir: walk up from cwd to the nearest dir containing
     `.git`, `deps.edn`, `bb.edn` or `shadow-cljs.edn`.
  2. Read the version's port file and probe it. If it is dead, take the lock
     and spawn the daemon detached
     (`clojure -Sdeps '{:deps {<fork> {:git/sha <same sha>}}}' -X clojure-mcp.daemon/start`,
     with the Jetty deps included), then wait for the port file. The wait is
     capped below the client's MCP connect timeout; on failure, print the
     daemon log tail to stderr and exit non-zero.
  3. Relay newline-delimited JSON-RPC from stdin as POSTs to
     `/p/<dir>/mcp`. Handle JSON and SSE responses and `Mcp-Session-Id`, and
     open the GET stream for server→client notifications. On stdin EOF, send
     DELETE for the session.
- `bb` is required on the machine (zgh's dev shell already has it).
- **Cold first launch for teammates** (git clone of the fork + deps + 15–18 s
  JVM) can exceed a client's MCP startup timeout. The shim therefore answers
  `initialize` **itself, immediately** (static server info and capabilities)
  and holds later requests (`tools/list`, calls) until the daemon is up, then
  replays the session into it. Per-request timeouts are far longer than
  startup timeouts. In addition, zgh's `.codex/config.toml` raises
  `startup_timeout_sec`, and zgh gets a `bb mcp:warm` task that starts the
  daemon ahead of time.

### 4. Sub-agent tools auto-enabled by a local cchp

- Model resolution for an agent tool: an explicit `:tools-config` model or
  provider API key (existing behavior) first. Otherwise, if
  `GET <cchp-url>/api/health` answers 200 (`<cchp-url>` from `CCHP_URL`,
  default `http://localhost:8033`), use a new **cchp ChatModel**. Otherwise
  the agent tools are not registered.
- The cchp ChatModel is a text-only LangChain4j `ChatModel` (~60 lines,
  modeled on cchp's `chat_model_adapter.clj`). It POSTs to
  `<cchp-url>/v1/chat/completions` with `model` (default `sonnet`) and
  `metadata.workspace_root = <project dir>`, and returns the reply text.
- **Why the default is `localhost`, not a fixed host such as studio**: cchp
  runs Claude Code in `workspace_root` **on the cchp machine's own disk**. A
  remote cchp would edit its own copy of that path, not the caller's checkout,
  or fail if the path doesn't exist there. On studio, `localhost:8033` *is*
  studio's cchp. On mbp it is mbp's. Teammates have no cchp, and studio's is
  behind Cloudflare Access for the operator only. For them the agent tools
  stay off unless they configure an API key.
- **Behavioral caveat**, the same as cchp today: cchp ignores client tools,
  so the "agent" is a Claude Code process acting with its own tools in the
  project dir, not a LangChain4j loop over clojure-mcp's tools.
- Health is checked when the daemon starts (to decide registration) and again
  per call. If cchp has gone away, the call returns a clear error instead of
  hanging.

### 5. zgh setup (separate PR in zgh)

- `.mcp.json`: `{"mcpServers": {"clojure-mcp": {"command": "bb", "args": [...shim, pinned fork sha...]}}}`
- `.codex/config.toml`: the same command.
- `.clojure-mcp/config.edn`: `{:start-repl-hint "bb start:detach" :cljs-build :dev}`.
- One line in root `AGENTS.md` under "REPL workflow" pointing at the
  clojure-mcp tools. It must keep the ≤100-line tier limit that
  `bb check:agent-md` enforces.

## Delivery

Done in order, in this run (operator's pick). I open each PR; auto review
and cchp auto-merge take it from there. The zgh PR pins the fork sha only
after fork PR 2 is merged.

1. **Fork PR 1**: §1 runtime tools, deadline, discovery help,
   `.shadow-cljs/nrepl.port` support, `:start-repl-hint`, `:cljs-build`.
   Useful on its own, even over stdio.
2. **Fork PR 2**: §2 daemon + §3 shim + §4 cchp agent backend.
3. **zgh PR**: §5, pinned to the merged fork sha.

Verification, end to end on this machine: start zgh with `bb start:detach`.
From a fresh Claude Code session in zgh (no cchp MCP), confirm that:

- `clojure_eval` reaches the JVM: `(System/getProperty "user.dir")` is zgh;
- `cljs_eval` reaches Node: `js/process.version` returns, with build `:dev`
  and no `port`;
- `cljs_build_status` reports `:dev`;
- a second session reuses the same daemon pid;
- agent tools are listed while cchp is up, and not listed with `CCHP_URL`
  pointing at a dead port and no API key.

## Calls I made (operator: check these)

- Agent tools via cchp are text-only. They are Claude Code acting with its
  own tools, not clojure-mcp's tools.
- The tool list is server-wide; per-project tool filtering is dropped in
  daemon mode.
- One daemon per fork version, OS-assigned port, 8 h idle exit.
- Project root = nearest ancestor with `.git`/`deps.edn`/`bb.edn`/`shadow-cljs.edn`.
- `CCHP_URL` default `http://localhost:8033` (personal prod). Dev cchp
  worktrees use other ports and need `CCHP_URL` set.
- `bb` is a hard requirement for the shim. zgh's config assumes the agent
  was started inside zgh's dev shell (direnv/`nix develop`), as zgh's
  AGENTS.md already requires for its commands.
- zgh's committed config targets **every contributor**, not only this
  machine (that is the point of the work). Hence the shim answering
  `initialize` itself and the warm task.
- Per-session env forwarding, and a token plus Host/Origin checks on the
  localhost daemon.

## Left to the builder

Namespace and file names, the relay's internals, the exact registry TTL/cap
numbers, the wording of error messages, the test layout (follow the fork's
`clojure -M:test` conventions), and whether the shim also supports
`--version`/`--stop` flags.
