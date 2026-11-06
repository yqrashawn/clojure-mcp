# Runtime tools (PR 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port cchp's shadow-cljs tools, eval deadline and no-nREPL help into this fork. This is delivery step 1 of the spec, and it is useful over plain stdio.

**Architecture:** New tools use the fork's `tool-system` multimethods (`tool-name`, `tool-schema`, `validate-inputs`, `execute-tool`, `format-results`) and evaluate generated forms through `clojure-mcp.tools.eval.core/evaluate-code` on a resolved port. The deadline and the no-nREPL help live in the eval tool, so every transport gets them. Two new project config keys feed build and hint defaults.

**Tech Stack:** Clojure 1.12, nREPL client (`clojure-mcp.nrepl`), malli config schema, cognitect test-runner (`clojure -M:test`), clj-kondo.

**Spec:** `docs/superpowers/specs/2026-11-06-runtime-daemon-design.md` (§1 and §5's config keys only). Reference implementation: `~/workspace/home/claude-code-http-proxy/src/clj/cchp/module/mcp_server/clojure_mcp.clj` and its test `test/clj/cchp/module/mcp_server/clojure_mcp_test.clj`. Port the forms, regexes and descriptions from there; don't reinvent them.

## Global Constraints

- Tool names exactly: `cljs_eval`, `cljs_build_status`, `cljs_project_state`, `cljs_list_ns`, with behavior identical to cchp except where this plan says otherwise.
- Deadline = `timeout_ms` (or the tool's default timeout) + **60000 ms** grace. On expiry the worker is cancelled and the agent gets cchp's message ("… did not return within Ns. The nREPL it talks to is not answering … Do not retry against the same REPL …").
- Build id whitelist regex (from cchp `build-name-re`): `#"[A-Za-z0-9*+!\-_'?<>=.]+"` after stripping leading colons. The check runs before any interpolation into a form.
- Shadow port file: `<project-dir>/.shadow-cljs/nrepl.port`.
- Config keys: `:cljs-build` (keyword) and `:start-repl-hint` (non-blank string or vector of them).
- The shadow `npx shadow-cljs watch <b>` hint is emitted **only** when `:start-repl-hint` is not configured.
- `clojure -M:test` and `clj-kondo --lint src test` must be clean before the PR.

## Review Focus

1. **Build arg injection:** `build` = `"a) (do :b"`, `":"`, `""` or `"app/x"`. Expect a tool error naming the valid shape, with nothing evaluated. Pinned in Task 4.
2. **Two active builds, no `build`, no `:cljs-build`:** expect an error listing the active builds, not a silent pick. Pinned in Task 4.
3. **A stale `.shadow-cljs/nrepl.port`** (file present, nothing listening): expect a connect error naming the port and `list_nrepl_ports`, never a hang. Pinned in Task 4.
4. **A wedged nREPL** (socket accepts, never replies): `clojure_eval` must return within deadline + slack, not block. Pinned in Task 2.
5. **No port anywhere** while another project's REPL runs: the help must carry the cross-worktree warning and the configured hint, and must not suggest `npx` when a hint is configured. Pinned in Task 3.

---

### Task 1: Config keys and the shadow port file

**Files:**
- Modify: `src/clojure_mcp/config/schema.clj` (Config map: add `:cljs-build`, `:start-repl-hint`)
- Modify: `src/clojure_mcp/config.clj` (getters)
- Modify: `src/clojure_mcp/nrepl.clj` (add `read-shadow-port-file`)
- Modify: `src/clojure_mcp/tools/nrepl_ports/core.clj` (include the shadow port file as a candidate)
- Test: `test/clojure_mcp/config/runtime_keys_test.clj`, `test/clojure_mcp/tools/nrepl_ports/core_test.clj` (new)

**Interfaces:**
- Produces: `config/get-cljs-build [nrepl-client-map] -> keyword|nil`; `config/get-start-repl-hints [nrepl-client-map] -> vector of strings` (empty when unset; a single string becomes a 1-vector); `nrepl/read-shadow-port-file [dir] -> int|nil`; `discover-nrepl-ports` results may carry `:source :shadow-port-file`.

- [ ] **Step 1: Write failing tests**
  - `schema-accepts-runtime-keys`: `(schema/valid? {:cljs-build :dev :start-repl-hint "bb start:detach"})` is true; `{:start-repl-hint ["a" "b"]}` is true; `{:start-repl-hint ""}` and `{:cljs-build "dev"}` are false. Use whatever validation fn the schema ns exposes.
  - `start-repl-hints-normalized`: `(get-start-repl-hints {::config/config {:start-repl-hint "x"}})` = `["x"]`; vector stays a vector; unset → `[]`.
  - `read-shadow-port-file-test`: temp dir with `.shadow-cljs/nrepl.port` containing `"51234\n"` → `51234`; missing → nil; garbage → nil.
  - `discover-includes-shadow-port-file`: `with-redefs` of `get-listening-jvm-ports` → `[]` and of `gather-port-info` → echoes `{:port p :source s}`. A temp dir holding both `.nrepl-port` (1111) and `.shadow-cljs/nrepl.port` (2222) yields sources `#{:nrepl-port-file :shadow-port-file}`.
- [ ] **Step 2: Run** `clojure -M:test -n clojure-mcp.config.runtime-keys-test -n clojure-mcp.tools.nrepl-ports.core-test`. Expected: FAIL (unresolved symbols / schema rejects).
- [ ] **Step 3: Implement** the schema entries, both getters, `read-shadow-port-file` (mirror `read-nrepl-port-file`) and the extra candidate in `discover-nrepl-ports`. Dedupe by port; when a port comes from both files, `.nrepl-port` wins.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "Add :cljs-build, :start-repl-hint and .shadow-cljs/nrepl.port discovery"`

### Task 2: Eval deadline

**Files:**
- Create: `src/clojure_mcp/tools/eval/deadline.clj`
- Modify: `src/clojure_mcp/tools/eval/tool.clj` (`execute-tool ::clojure-eval` runs inside the deadline; `create-eval-tool` accepts `:deadline-grace-ms`, default 60000)
- Modify: `src/clojure_mcp/tools/project/tool.clj` (`clojure_inspect_project` also waits on an nREPL with no timeout. Wrap its `execute-tool` with a deadline of 120000 + grace, as cchp's `nrepl-wait-tools` does.)
- Test: `test/clojure_mcp/tools/eval/deadline_test.clj`

**Interfaces:**
- Produces: `deadline/call-with-deadline [tool-name deadline-ms thunk] -> thunk's value | {:outputs [[:err msg]] :error true :context nil}`. It runs `thunk` in a `future`, derefs with `deadline-ms`, and on expiry calls `future-cancel` and returns the error map. `deadline/deadline-ms [timeout-ms grace-ms] -> long`. Task 4 reuses both.

- [ ] **Step 1: Write failing tests**
  - `deadline-passes-value-through`: thunk returns `{:ok 1}` → `{:ok 1}`.
  - `deadline-expires-with-guidance`: thunk `(Thread/sleep 5000)`, deadline 200 ms → returns within 1000 ms; `:error` true; the message contains `"did not return within"` and `"Do not retry against the same REPL"`.
  - `wedged-nrepl-does-not-block` (Review Focus 4): open a `java.net.ServerSocket` on port 0 that accepts and never writes. Build the eval tool with `:deadline-grace-ms 300` and call its registration `:tool-fn` with `{"code" "1" "port" <that port> "timeout_ms" 200}`. Expect the callback within 3 s, `error?` true, and the text containing `"did not return within"`.
- [ ] **Step 2: Run** `clojure -M:test -n clojure-mcp.tools.eval.deadline-test`. Expected: FAIL.
- [ ] **Step 3: Implement** `deadline.clj`. Wrap the body of `execute-tool ::clojure-eval` (port init + eval) in `call-with-deadline`, using `(deadline-ms (or timeout_ms timeout) deadline-grace-ms)`.
- [ ] **Step 4: Run** the deadline test plus `-n clojure-mcp.tools.eval.tool-test`. Expected: PASS (existing eval tests unchanged).
- [ ] **Step 5: Commit** `git commit -m "Bound clojure_eval by timeout + 60s so a wedged nREPL cannot hang the agent"`

### Task 3: No-nREPL help

**Files:**
- Create: `src/clojure_mcp/tools/eval/discovery.clj`
- Modify: `src/clojure_mcp/tools/eval/tool.clj`. `validate-inputs` no longer throws when no port resolves; it returns `(assoc inputs :port nil)`. `execute-tool` returns `(discovery/no-nrepl-help nrepl-client-map project-dir)` when `:port` is nil.
- Test: `test/clojure_mcp/tools/eval/discovery_test.clj`, and update the no-port expectation in `test/clojure_mcp/tools/eval/tool_test.clj` if one exists.

**Interfaces:**
- Consumes: `config/get-start-repl-hints` (Task 1); `nrepl-ports.core/discover-nrepl-ports`; `nrepl-ports.tool` formatting for the scan text.
- Produces: `discovery/start-repl-hints [dir configured-hints] -> vector of strings`; `discovery/no-nrepl-help [nrepl-client-map project-dir] -> {:outputs [[:err text]...] :error true :context nil}`.

- [ ] **Step 1: Write failing tests** (port cchp's `start-repl-hints` tests and keep their cases):
  - The executable `repl.sh` → `"./repl.sh"`; a non-executable one is absent.
  - bb.edn tasks named `nrepl`/`dev-nrepl` → `"bb nrepl"`…; a task named `start` alone → none.
  - deps.edn alias `:nrepl {:main-opts …}` → `"clj -M:nrepl"`; `{:exec-fn …}` → `"clj -X:…"`; dependency-only → none.
  - shadow-cljs.edn `:builds {:app …}` → `"npx shadow-cljs watch app"` **only when** configured hints are empty. With `["bb start:detach"]` configured, the result is exactly `["bb start:detach" …non-shadow hints]` and contains no `"npx"` (Review Focus 5).
  - `project.clj` → `"lein repl"`.
  - Unreadable EDN → no throw, those sources are skipped.
  - `no-nrepl-help-text`: with `discover-nrepl-ports` redefined to return one foreign port, the text contains that port, `"user.dir"`, `"another worktree"` and the configured hint, and `:error` is true.
- [ ] **Step 2: Run** `clojure -M:test -n clojure-mcp.tools.eval.discovery-test`. Expected: FAIL.
- [ ] **Step 3: Implement** by porting cchp's `read-edn-file`, `key->name`, `nrepl-named?`, `nrepl-named-keys`, `nrepl-alias-commands`, `start-repl-hints` and `nrepl-discovery-help`. Put configured hints first, then the auto-detected ones. Keep the guidance copy, minus cchp's "frontend subdirectory" sentence, which is cchp-specific, and minus the `clojure-mcp_` prefix: name tools `list_nrepl_ports`/`clojure_eval`.
- [ ] **Step 4: Run** the discovery tests plus the eval tool tests. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "Teach the agent how to find or start a REPL when clojure_eval has no port"`

### Task 4: shadow-cljs tools

**Files:**
- Create: `src/clojure_mcp/tools/shadow/core.clj` (build-arg validation, port resolution, the 4 form builders)
- Create: `src/clojure_mcp/tools/shadow/tool.clj` (4 tool-types plus `shadow-tools [nrepl-client-atom] -> vector of registration maps`)
- Modify: `src/clojure_mcp/tools.clj` (add `'clojure-mcp.tools.shadow.tool/shadow-tools` to `eval-tool-syms`)
- Modify: `src/clojure_mcp/tools/eval/tool.clj` (description: append cchp's "multi-REPL & ClojureScript" paragraph, pointing at `cljs_eval`, `list_nrepl_ports` and `.shadow-cljs/nrepl.port`)
- Test: `test/clojure_mcp/tools/shadow/core_test.clj`, `test/clojure_mcp/tools/shadow/tool_test.clj`
- Docs: `README.md` tool list, `doc/full-toolkit.md` (one entry per tool)

**Interfaces:**
- Consumes: `config/get-cljs-build`, `nrepl/read-shadow-port-file` (Task 1); `deadline/call-with-deadline`, `deadline/deadline-ms` (Task 2); `eval.core/evaluate-code`, `nrepl/with-port-initialized`.
- Produces:
  - `core/invalid-build-arg [build] -> error-string|nil`
  - `core/normalize-build-kw [build] -> keyword|nil`
  - `core/resolve-port [nrepl-client-map port-arg] -> int` (throws `ex-info` naming `list_nrepl_ports` when nothing resolves)
  - `core/cljs-eval-form [build-kw code runtime-id] -> string`
  - `core/build-status-form [build-kw] -> string`
  - `core/project-state-form [] -> string`
  - `core/list-ns-form [build-kw match] -> string`
  - The build default (`build-kw` nil) is resolved **inside** the form for `cljs_eval` and `cljs_list_ns`: `(or <kw> (single active build))`, else `{:error … :active-builds […]}`. Callers pass `(or (normalize-build-kw arg) (config/get-cljs-build m))`.

- [ ] **Step 1: Write failing core tests** (port cchp's form/arg tests):
  - `invalid-build-arg` rejects `"a) (do :b"`, `":"`-only → nil (meaning absent), `"app/x"`; accepts `"app"`, `":dev"`, `:dev` (Review Focus 1).
  - Each `*-form` returns a string that `read-string` parses into one form. With `build-kw` nil, the `cljs-eval-form` source contains `active-builds`.
  - `resolve-port`: arg wins; else the `.shadow-cljs/nrepl.port` of the `:nrepl-user-dir` temp dir; else it throws with a message containing `"list_nrepl_ports"`.
- [ ] **Step 2: Write failing tool tests.** In the fixture, start an in-process nREPL server (as `eval/tool_test.clj` does). In that JVM, define stub namespaces `shadow.cljs.devtools.api` (`active-builds`, `get-build-ids`, `get-worker`, `cljs-eval` that records its args in an atom and returns `{:results ["ok"]}`) and `shadow.cljs.devtools.server.runtime` (`instance-ref` holding `{:build-history {:state-ref (atom {...})}}`). Mark both in `*loaded-libs*` so `require` is a no-op. Then:
  - `cljs-eval-single-active-build`: active `#{:dev}`, no build arg → the stub receives `:dev` and the result contains `ok`.
  - `cljs-eval-two-active-builds` (Review Focus 2): active `#{:app :dev}`, no arg, no config → `error?` true and the text mentions both builds.
  - `cljs-eval-config-build`: config `:cljs-build :dev`, active `#{:app :dev}` → the stub receives `:dev`.
  - `cljs-eval-port-from-shadow-file`: no `port` arg, user dir holds `.shadow-cljs/nrepl.port` = test server port → succeeds.
  - `stale-shadow-port-file` (Review Focus 3): the file points at a closed port → an error naming that port and `list_nrepl_ports` within 3 s.
  - `build-status-reports-warnings`, `project-state-lists-runtimes`, `list-ns-filters-by-match`: assert on the stub data passing through.
  - `invalid-build-not-evaluated`: build `"a) (do :b"` → error, and the stub's call atom stays empty.
  - `eval-description-points-at-cljs-eval`: the `clojure_eval` description contains `"cljs_eval"`.
- [ ] **Step 3: Run** `clojure -M:test -n clojure-mcp.tools.shadow.core-test -n clojure-mcp.tools.shadow.tool-test`. Expected: FAIL.
- [ ] **Step 4: Implement** `core.clj` (port the cchp forms; make the build default resolve in-form as above) and `tool.clj`. Each `execute-tool` = `call-with-deadline` around `with-port-initialized` + `evaluate-code` with the generated form. Schemas and descriptions are ported from cchp, with `port` no longer required and the build default described as "`:cljs-build` config, else the only active build". Register in `tools.clj`. Update the eval description and the docs.
- [ ] **Step 5: Run** `clojure -M:test` (full suite) and `clj-kondo --lint src test`. Expected: all pass, 0 errors, no new warnings.
- [ ] **Step 6: Commit** `git commit -m "Add cljs_eval and shadow-cljs state tools"`

### Task 5: End-to-end check against zgh, then PR

**Files:** none (verification + PR)

- [ ] **Step 1:** `git clone --local ~/workspace/home/zgh <scratchpad>/zgh` and add `.clojure-mcp/config.edn` = `{:cljs-build :dev :start-repl-hint "bb start:detach"}` there. Don't touch the operator's checkout.
- [ ] **Step 2:** Before the stack is up, run this fork over stdio (`clojure -X:mcp :project-dir '"<clone>"'`, a JSON-RPC script) and call `clojure_eval` `(+ 1 2)`. Expected: error text containing `bb start:detach` and no `npx`.
- [ ] **Step 3:** In the clone: `nix develop -c bb start:detach`, then wait (one blocking loop) for `.nrepl-port` and `.shadow-cljs/nrepl.port`.
- [ ] **Step 4:** Over stdio, call each of these and record the output:
  - `clojure_eval` `(System/getProperty "user.dir")` → the clone path;
  - `cljs_eval` `js/process.version` with no port and no build → a Node version string;
  - `cljs_build_status` → `:dev` with a status;
  - `cljs_project_state` → a runtime for `:dev`;
  - `cljs_list_ns` match `"^zgh\\.kernel"` → a non-empty list.
- [ ] **Step 5:** `nix develop -c bb stop` in the clone.
- [ ] **Step 6:** Push the branch and open a PR to `yqrashawn/clojure-mcp` `main`. The body covers what is ported from cchp, what differs (port optional, build default, hint precedence), the e2e transcript summary, and that it is spec delivery step 1. Auto review and cchp auto-merge handle the rest.
