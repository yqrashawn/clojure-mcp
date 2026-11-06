# Shared runtime daemon (fork PR 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One clojure-mcp JVM per machine serves every project's agents. A babashka stdio shim lets any MCP client reach it, and the sub-agent tools turn on by themselves when a local cchp answers.

**Architecture:**
- **Daemon.** A new `clojure-mcp.daemon.*` serves MCP Streamable HTTP on `127.0.0.1:0`. The path `/p/<abs project dir>/mcp` selects a per-project entry: its own nrepl-client-atom, config and tool-fns, built lazily the way cchp's per-cwd registry does it. One server-wide tool list dispatches each call to the caller's entry, under that session's forwarded environment.
- **Shim.** A bb script under `shim/` finds or spawns the daemon for its pinned version, answers `initialize` itself, and relays stdio ↔ HTTP.
- **cchp model.** A small LangChain4j `ChatModel` backs the agent tools by calling cchp's `/v1/chat/completions`.

**Tech Stack:** Clojure 1.12, MCP Java SDK 1.1.3 (`HttpServletStreamableServerTransportProvider`, `McpTransportContextExtractor`), Jetty 11.0.20, LangChain4j 1.16.2 `ChatModel`, `java.net.http.HttpClient`, babashka (`babashka.http-client`, `babashka.process`, `org.httpkit.server` for shim tests).

**Spec:** `docs/superpowers/specs/2026-11-06-runtime-daemon-design.md` (§2 daemon, §3 shim, §4 agent tools; "Delivery" step 2).

## Global Constraints

- Bind `127.0.0.1` only. Port `0` (OS-assigned).
- One daemon per fork version. State lives in `~/.cache/clojure-mcp/daemons/<version>/` (honor `XDG_CACHE_HOME`): dir mode `0700`; `token` file mode `0600`.
- Idle exit after **8 h** (28 800 000 ms) without an HTTP request.
- Every request needs `Authorization: Bearer <token>`. `Host` must be `127.0.0.1:<port>` or `localhost:<port>`. Any `Origin` header is refused.
- Agents never see the token: only the shim reads it.
- `CCHP_URL` defaults to `http://localhost:8033`. Health is `GET <cchp>/api/health` → 200. Completions are `POST <cchp>/v1/chat/completions` with `metadata.workspace_root`. Default model is `"sonnet"`.
- Agent-model precedence: explicit `:tools-config` model, then a provider API key, then a healthy cchp, then no agent tools.
- The project root is the nearest ancestor of cwd containing `.git`, `deps.edn`, `bb.edn` or `shadow-cljs.edn`.
- `bb` is required for the shim. The shim never loads JVM deps.
- The shim answers `initialize` immediately and holds later requests until the daemon is up.
- Registry numbers (cchp's): TTL 30 min, cap 50, eviction sweep every 100th call. Rebuild an entry when its `.nrepl-port` changes.
- Tests run with `clojure -M:test`; shim tests with `cd shim && bb test`. No Co-Authored-By lines in commits.

## Daemon ↔ shim contract (pinned; both sides test against it)

| Request | Response |
|---|---|
| `GET /health` | 200 `{"version": "<v>", "pid": <n>}` |
| `POST /env`, body a JSON object of string→string | 200 `{"id": "<sha256 hex of the body's canonical JSON, keys sorted>"}` |
| `POST/GET/DELETE /p/<dir>/mcp` | MCP Streamable HTTP (SDK) |
| no or wrong bearer token | 401 |
| bad `Host`, or any `Origin` | 403 |
| any other path | 404 |

- `<dir>` is the canonical absolute project path. Each `/`-separated segment is percent-encoded with `%20` for spaces, and slashes stay literal: Jetty 11 rejects `%2F`.
- Every `/p/...` request may carry `X-Clojure-Mcp-Env: <id>`. With no header, or an unknown id, the daemon's own environment is used.
- State files in `<state>/<v>/`:
  - `token`: 64 hex chars, written first.
  - `pid`.
  - `daemon.log`.
  - `spawn.lock`.
  - `port`: decimal, written **last**, via tmp file + atomic rename. Its presence means "ready".
- A daemon that exits deletes `port` only if the file still holds its own port.
- Version string:
  - a git pin → the 40-char sha;
  - `--local-root <dir>` → `local-` + the first 12 hex chars of sha256(canonical dir).

## Review Focus

1. **The daemon dies mid-session** (idle exit, crash, kill). The shim notices the refused connection, respawns, replays `initialize` + `notifications/initialized` + `/env`, and retries the request once. The agent does not see a dead MCP server. (Task 6 test `daemon-restart-is-transparent`.)
2. **Two shims cold-start at once.** Exactly one daemon is spawned (spawn lock), and both connect to it. (Task 6 test `concurrent-cold-start-spawns-once`.)
3. **Another worktree's REPL.** Project A's `clojure_eval` never resolves project B's `.nrepl-port`. Entries are keyed by canonical dir, and a symlinked path maps to the same entry. (Task 2 test `entries-are-per-canonical-dir`.)
4. **Project paths with spaces or unicode** round-trip through the URL to the right entry. (Task 3 test `path-with-space-routes`; Task 6 `encode-project-path`.)
5. **The daemon fails to start** (no `clojure` on PATH, a deps error). Held requests get a JSON-RPC error carrying the daemon log tail within the start timeout, and the shim exits non-zero. It must not hang. (Task 6 test `spawn-failure-reports-log-tail`.)

---

### Task 1: Session environment reaches bash and API-key lookup

**Files:**
- Modify: `src/clojure_mcp/config.clj` (add `^:dynamic *session-env*`)
- Modify: `src/clojure_mcp/tools/bash/core.clj` (`execute-bash-command`)
- Modify: `src/clojure_mcp/agent/langchain.clj` (`agent-model`, `reasoning-agent-model` and the `create-*-model` key reads)
- Test: `test/clojure_mcp/daemon/session_env_test.clj`

**Interfaces:**
- Produces: `clojure-mcp.config/*session-env*`, which is nil or a `{String String}` map. When it is bound:
  - a local bash process gets exactly that environment (`.clear` then `.putAll` on `ProcessBuilder.environment`);
  - every API-key read in `langchain.clj` goes through `clojure-mcp.agent.langchain.model/get-env`. The daemon binds `model/*env-overrides*` to the same map.

- [ ] **Step 1: Failing tests.**
  - `bash-sees-session-env`: `(binding [config/*session-env* {"PATH" (System/getenv "PATH") "ZZ_SESSION" "s1"}] (execute-bash-command … "echo $ZZ_SESSION"))` → stdout `"s1\n"`.
  - `bash-without-session-env-inherits`: unbound → `$HOME` is non-blank.
  - `agent-model-reads-overrides`: `(binding [model/*env-overrides* {"ANTHROPIC_API_KEY" "x"}] (some? (chain/agent-model)))` is true, and `(binding [model/*env-overrides* {}] …)` gives nil when the real env has no keys. Guard that branch with `(when-not (System/getenv "ANTHROPIC_API_KEY") …)`.
- [ ] **Step 2: Run them.** `clojure -M:test -n clojure-mcp.daemon.session-env-test` → FAIL.
- [ ] **Step 3: Implement.** If `langchain.clj` → `model` would be a require cycle, move `*env-overrides*` and `get-env` into `clojure-mcp.config` and re-point `model/get-env` at it. Ledger that ruling.
- [ ] **Step 4: Run tests + the bash and agent suites.** `clojure -M:test` → only the 2 known failures that also fail on `main` (`resources_test`, `valid_paths`).
- [ ] **Step 5: Commit.** "Let a bound session env drive bash and API-key lookup"

### Task 2: Per-project registry

**Files:**
- Create: `src/clojure_mcp/daemon/registry.clj`
- Test: `test/clojure_mcp/daemon/registry_test.clj`

**Interfaces:**
- Produces:
  - `(new-registry) -> atom`.
  - `(get-or-create! registry project-dir) -> entry`, where entry is `{:dir String :nrepl-client-atom atom :tool-fns {String fn} :nrepl-port int-or-nil :last-used Instant}`.
  - `(evict-stale! registry {:ttl-ms :max-entries})`.
  - `(read-nrepl-port dir) -> int-or-nil`.
  - `(tool-enabled? entry tool-name) -> bool`, via `config/tool-id-enabled?` on the entry's client map.
- Entry build:
  - Call `core/create-and-start-nrepl-connection {:project-dir dir :port <.nrepl-port or absent>}`, or the equivalent lower-level calls. It must **not** touch `core/nrepl-client-atom`. Ledger which you used.
  - Then call `tools/build-all-tools` on a fresh atom holding it.
  - Use a per-dir lock (`ConcurrentHashMap` of Objects) with a double check, as in cchp's `get-or-create!`.
  - When rebuilding or evicting, remove the scratch-pad persistence watch (`:clojure-mcp.tools.scratch-pad.tool/scratch-pad-persistence`).

- [ ] **Step 1: Failing tests.**
  - `entries-are-per-canonical-dir`: two temp dirs get distinct entries, and a symlink to dir A gets A's entry (`identical?`).
  - `rebuilds-when-nrepl-port-changes`: write `.nrepl-port` 1111, then 2222 → a new entry with `:nrepl-port 2222`. Deleting the file keeps the entry.
  - `evicts-by-ttl-and-cap`.
  - `global-client-atom-untouched`: `@core/nrepl-client-atom` is unchanged after `get-or-create!`.
  - `project-config-disables-tool`: a project `.clojure-mcp/config.edn` `{:disable-tools [:bash]}` → `(tool-enabled? e "bash")` is false.
- [ ] **Step 2: Run.** `clojure -M:test -n clojure-mcp.daemon.registry-test` → FAIL.
- [ ] **Step 3: Implement** as above.
- [ ] **Step 4: Run.** → PASS.
- [ ] **Step 5: Commit.** "Add the daemon's per-project registry"

### Task 3: Daemon HTTP server, auth, env store, dispatch

**Files:**
- Create: `src/clojure_mcp/daemon/server.clj`, `src/clojure_mcp/daemon/state.clj`, `src/clojure_mcp/daemon.clj`
- Modify: `deps.edn`. Add a `:daemon` alias with the `:mcp-http` Jetty and servlet deps and `:exec-fn clojure-mcp.daemon/start`.
- Test: `test/clojure_mcp/daemon/server_test.clj`. The `:test` alias must get the Jetty deps; add them to `:test :extra-deps`.

**Interfaces:**
- Consumes: Task 1 `config/*session-env*` and `model/*env-overrides*`; Task 2 registry fns.
- Produces:
  - `(clojure-mcp.daemon/start {:version String :state-root String? :idle-exit-ms long? :env {String String}?})`. This is the `-X` exec-fn. It blocks, writes the state files per the contract, and `System/exit 0` on idle.
  - `(server/start! {:version :state-dir :env :idle-exit-ms}) -> {:port int :token String :stop! fn}`. This one does not exit; tests use it.
  - `state/` fns: `(state-dir root version)`, `(write-ready! dir {:port :token :pid})`, `(clear-port! dir port)`.
- Server:
  - A Jetty `ServletContextHandler` with a security `Filter` on `/*` (token, Host, Origin; it also updates last-activity), small servlets for `/health` and `/env`, and the SDK provider on `/p/*`.
  - Provider: `.mcpEndpoint "/mcp"`, with a `.contextExtractor` that matches `^/p(/.*)/mcp$`, URL-decodes each segment, canonicalizes, and puts `"project-dir"` plus `"env"` (looked up from `X-Clojure-Mcp-Env`) in the context.
  - `McpServer/async` with `serverInfo "clojure-mcp" <version>` and tools capability `listChanged true`.
- Tools:
  - Metadata comes from one dummy entry (a temp dir), as cchp does.
  - Each tool is registered through `core/create-async-tool` with a dispatching `:tool-fn`, which:
    - reads `project-dir` and `env` from `(.transportContext exchange)`;
    - calls `get-or-create!`;
    - returns an error result `"<tool> is disabled by <dir>/.clojure-mcp/config.edn"` when `tool-enabled?` is false;
    - otherwise calls the entry's tool-fn inside `(binding [config/*session-env* env model/*env-overrides* env] …)`.
  - A missing `project-dir` gives the error result "connect through /p/<project dir>/mcp".
  - Run an eviction sweep on every 100th call.
- Agent tools: this task registers them only when `(agent-backend-available? env)` is true; Task 5 supplies that fn. Until Task 5, stub it to "an API key in `env`" and ledger that.

- [ ] **Step 1: Failing tests.** All go through `java.net.http.HttpClient` against `start!` with a temp state root, and use an in-process nREPL (`nrepl.server/start-server :port 0`) written to project A's `.nrepl-port`.
  - `auth-and-host-checks`: 401 with no token; 403 with `Origin: http://evil`; 403 with `Host: evil:1`; 200 `/health` with `version`.
  - `mcp-roundtrip-per-project`: `initialize` → `tools/list` contains `clojure_eval` and `cljs_eval` → `tools/call clojure_eval (+ 1 2)` on A gives `3`. The same call on project B (no `.nrepl-port`) gives the no-nREPL help (an error result containing "No nREPL").
  - `path-with-space-routes`: project dir `".../my proj"` → eval works.
  - `session-env-reaches-bash`: `POST /env {"PATH":…, "ZZ":"e1"}`, then `bash "echo $ZZ"` with the header gives `e1`; without the header it gives an empty line.
  - `state-files-and-modes`: `token` is `0600`, the dir is `0700`, and `port` exists only after the server is accepting.
  - `idle-exit`: `start!` with `:idle-exit-ms 500` → `:stop!` runs and the `port` file is gone within 3 s.
- [ ] **Step 2: Run.** `clojure -M:test -n clojure-mcp.daemon.server-test` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** → PASS, then the full suite → only the 2 known failures that also fail on `main`.
- [ ] **Step 5: Commit.** "Serve many projects from one HTTP daemon"

### Task 4: cchp ChatModel

**Files:**
- Create: `src/clojure_mcp/agent/cchp_model.clj`
- Test: `test/clojure_mcp/agent/cchp_model_test.clj`

**Interfaces:**
- Produces:
  - `(cchp-url env) -> String`: `(get env "CCHP_URL" "http://localhost:8033")`, trailing `/` stripped.
  - `(healthy? base-url) -> bool`: `GET /api/health`, 2 s timeout, true only on 200, never throws.
  - `(create-chat-model {:base-url :model "sonnet" :workspace-root :timeout-ms 1800000}) -> dev.langchain4j.model.chat.ChatModel`, implemented with `reify` overriding `doChat`.
- Request:
  - `POST <base>/v1/chat/completions` with JSON `{"model", "stream": false, "messages": [{"role", "content"}…], "metadata": {"workspace_root"}}`.
  - Roles: `SystemMessage` → system, `UserMessage` → user (`singleText`), `AiMessage` → assistant (`.text`), anything else → user, with `"Context: "` + str.
- Response: `choices[0].message.content` → `AiMessage`, `FinishReason/STOP`.
- Failures: a non-200 or connection failure throws `ex-info` "cchp at <url> answered <status>: <first 300 chars>" or "cchp at <url> is not answering".

- [ ] **Step 1: Failing tests** against a JDK `com.sun.net.httpserver.HttpServer` on port 0.
  - `posts-openai-shape-with-workspace-root`: the captured body has `model "sonnet"`, `stream false`, `metadata.workspace_root "/w"`, and roles `["system" "user"]`. `.chat` returns the stubbed content.
  - `healthy-only-on-200`: 200 → true; 500 → false; a dead port → false.
  - `errors-are-loud`: a 500 body ends up in the ex message; a dead port says "not answering".
- [ ] **Step 2: Run.** `clojure -M:test -n clojure-mcp.agent.cchp-model-test` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** → PASS.
- [ ] **Step 5: Commit.** "Add a ChatModel backed by a local cchp"

### Task 5: Sub-agent tools turn on with a local cchp

**Files:**
- Modify: `src/clojure_mcp/daemon/registry.clj` (entry build), `src/clojure_mcp/daemon/server.clj` (registration)
- Test: `test/clojure_mcp/daemon/agent_backend_test.clj`

**Interfaces:**
- Consumes: Task 4 `cchp-url`, `healthy?`, `create-chat-model`.
- Produces:
  - `(agent-backend-available? env) -> bool`: an API key (`ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `OPENAI_API_KEY`) in `env`, or `(healthy? (cchp-url env))`. It is evaluated once at server start, with the daemon's env.
  - Entry build injects `{:model <cchp ChatModel with :workspace-root dir>}` under `:tools-config` for `:dispatch_agent :architect :code_critique :clojure_edit_agent`, but **only** for ids whose project config has no `:model`, and only when the building session's env has no provider key and cchp is healthy.
- Per call, the ChatModel errors loudly if cchp has gone away (Task 4).

- [ ] **Step 1: Failing tests.**
  - `agent-tools-listed-with-cchp`: a fake cchp answers 200 and env is `{"CCHP_URL" fake}` → `tools/list` includes `dispatch_agent`.
  - `agent-tools-absent-without-backend`: env is `{"CCHP_URL" "http://127.0.0.1:1"}` → `dispatch_agent` is absent.
  - `api-key-beats-cchp`: env has `ANTHROPIC_API_KEY` → the entry's `:tools-config` has no injected model.
  - `project-model-beats-cchp`: a project config `{:tools-config {:architect {:model :openai/o4-mini}}}` keeps that model for `:architect` and injects for the others.
  - `dispatch-agent-reaches-cchp`: `tools/call dispatch_agent` → the fake receives `workspace_root` = the project dir, and the tool result contains the stubbed reply.
- [ ] **Step 2: Run.** `clojure -M:test -n clojure-mcp.daemon.agent-backend-test` → FAIL.
- [ ] **Step 3: Implement.** Replace Task 3's stub.
- [ ] **Step 4: Run.** → PASS, then the full suite.
- [ ] **Step 5: Commit.** "Turn the sub-agent tools on when a local cchp answers"

### Task 6: bb stdio shim

**Files:**
- Create: `shim/deps.edn` (`{:paths ["src"]}`), `shim/bb.edn` (`:paths ["src" "test"]`, task `test`), `shim/src/clojure_mcp/shim.clj`, `shim/test/clojure_mcp/shim_test.clj`
- Modify: `README.md` (section "Shared daemon": client config, `--warm`, state dir, troubleshooting via `daemon.log`)

**Interfaces:**
- Consumes: the daemon ↔ shim contract above.
- Produces the CLI `bb -Sdeps '{:deps {io.github.yqrashawn/clojure-mcp {:git/url "https://github.com/yqrashawn/clojure-mcp" :git/sha "<sha>" :deps/root "shim"}}}' -m clojure-mcp.shim --sha <sha> [--local-root <dir>] [--warm] [--start-timeout-ms 180000]`:
  - `--warm`: ensure the daemon is up, print its port, exit 0.
  - Otherwise relay until stdin EOF, then DELETE the session and exit 0.
- Spawn:
  - Take an exclusive lock on `spawn.lock`, re-check `port`, then start detached with cwd = state dir and stdout/stderr appended to `daemon.log`: `clojure -Sdeps '{:deps {io.github.yqrashawn/clojure-mcp {…sha…} <jetty + servlet coords from :mcp-http>}}' -X clojure-mcp.daemon/start :version '"<v>"'`. With `--local-root`, use `{:local/root dir}`.
  - The lock is `java.nio.channels.FileChannel/tryLock` if bb allows it (check with `bb -e '(import (java.nio.channels FileChannel))'`). Otherwise use an atomic `mkdir spawn.lock.d` treated as stale after 120 s. Ledger which one.
  - The daemon must survive the client killing the shim (check in Task 7).
- Relay:
  - Read one JSON-RPC message per stdin line.
  - `initialize` is answered locally at once: the client's `protocolVersion`, `serverInfo {name "clojure-mcp" version <v>}`, `capabilities {"tools" {"listChanged" true}}`. The shim then replays it to the daemon, together with `notifications/initialized`, once the daemon is ready.
  - The local answer has no `instructions`. This keeps cold and warm starts identical, and the tool descriptions carry the guidance.
  - Every other message is POSTed to `/p/<dir>/mcp` on its own thread, with:
    - `Authorization`;
    - `Accept: application/json, text/event-stream`;
    - `Mcp-Session-Id`;
    - `MCP-Protocol-Version`;
    - `X-Clojure-Mcp-Env`: the id from POSTing the shim's `System/getenv` to `/env` once per daemon.
  - Responses:
    - 202: nothing;
    - JSON: write the body as one line;
    - SSE: write each event's `data` as a line.
  - All stdout writes are serialized.
  - After the session is up, a background GET stream relays server→client messages; a 405 stops it.
  - A refused connection → respawn, replay, retry once (Review Focus 1).
  - If the daemon is not ready by `--start-timeout-ms`: every held request gets a JSON-RPC error with the last 40 lines of `daemon.log`, the same lines go to stderr, and the shim exits 1.

- [ ] **Step 1: Failing bb tests**, using `org.httpkit.server` fake daemons that write the state files themselves:
  - `project-root-walks-up`.
  - `encode-project-path`: `"/a/my proj/ü"` → `"/p/a/my%20proj/%C3%BC/mcp"`.
  - `version-for-local-root`.
  - `initialize-answered-before-daemon`: no daemon yet → the initialize response arrives within 1 s.
  - `relays-json-and-sse`.
  - `env-id-header-sent`.
  - `daemon-restart-is-transparent`.
  - `concurrent-cold-start-spawns-once`: two shims, a spawn stub counting invocations → 1.
  - `spawn-failure-reports-log-tail`: the spawn command is stubbed as `false`, with the timeout shortened.
  - `stdin-eof-deletes-session`.
  - The spawn command must be injectable for these tests (an env var `CLOJURE_MCP_SHIM_SPAWN_CMD`, test-only and documented as such).
- [ ] **Step 2: Run.** `cd shim && bb test` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** → PASS. Also `clj-kondo --lint shim/src shim/test` → no new warnings.
- [ ] **Step 5: Commit.** "Add the bb stdio shim that finds or starts the daemon"

### Task 7: End-to-end on this machine

No new code unless the checks fail. Each fix gets its own test in the owning task's test file.

- [ ] **Step 1: Start the zgh clone's stack.** `bb start:detach`.
- [ ] **Step 2: Drive the shim over stdio from two concurrent processes.** Use a stdio JSON-RPC driver script, with cwd = the zgh clone and `--local-root <this worktree>`. Expected:
  - Cold start answers `initialize` immediately.
  - `clojure_eval (System/getProperty "user.dir")` gives the zgh clone.
  - `cljs_eval js/process.version` gives `"v…"` with no port and no build.
  - `cljs_build_status` gives `:dev`.
  - Both drivers see the same daemon pid (`GET /health`).
- [ ] **Step 3: Agent tools.**
  - With cchp up (`localhost:8033`) and no API keys, `dispatch_agent` is listed, and a short prompt returns text.
  - Restart the daemon with `CCHP_URL=http://127.0.0.1:1` and no keys → it is not listed.
- [ ] **Step 4: Daemon survives the client.** Kill a driver's whole process group (`kill -TERM -<pgid>`); the daemon pid is still alive and the next driver reuses it.
- [ ] **Step 5: Real client.** Register the shim in a scratch `.mcp.json` and run `claude -p` in the zgh clone asking it to call `clojure_eval` → it answers with the zgh path.
- [ ] **Step 6: Stop the stack and the daemon. Record results in the ledger. Commit any fixes.**
