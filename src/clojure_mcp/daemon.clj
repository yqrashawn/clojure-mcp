(ns clojure-mcp.daemon
  "Entry point of the shared daemon: one JVM serving every project's MCP
   sessions on 127.0.0.1. Started by the bb shim (shim/) or by hand with
   `clojure -X:daemon :version '\"dev\"'`. State lives in
   ~/.cache/clojure-mcp/daemons/<version>/."
  (:require [clojure-mcp.daemon.server :as server]
            [clojure-mcp.daemon.state :as state]
            [clojure-mcp.logging :as logging]))

(def default-agent-model-fallback
  "The hook a daemon gets when its start options name none: sub-agents run
   through the local cchp."
  'clojure-mcp.agent.cchp-model/agent-model-fallback)

(defn resolve-agent-model-fallback
  "The fn a qualified symbol names, nil for nil. Throws, naming the symbol,
   when it does not resolve: a bad symbol must stop the daemon with a message
   in daemon.log, not an NPE."
  [sym]
  (when sym
    (let [v (try (requiring-resolve sym)
                 (catch Exception e
                   (throw (ex-info (str ":agent-model-fallback " sym " could not be loaded: " (ex-message e))
                                   {:symbol sym} e))))]
      (if v
        @v
        (throw (ex-info (str ":agent-model-fallback " sym " does not name a var") {:symbol sym}))))))

(defn start
  "Runs the daemon until it has been idle for `idle-exit-ms` (default 8 h),
   then exits the JVM. `version` names the state dir: the shim passes the
   pinned sha, so projects pinning different shas never share a daemon.
   `agent-model-fallback` is a qualified symbol naming the hook described in
   `clojure-mcp.daemon.server/start!`. Left out, it is the local cchp
   (`default-agent-model-fallback`); an explicit nil turns the fallback off."
  [{:keys [version state-root idle-exit-ms env] :as opts}]
  (assert (some? version) ":version is required")
  (let [version (str version)
        dir (state/state-dir (or state-root (state/default-root)) version)]
    (state/ensure-dir! dir)
    ;; Spawned from one client's shell, it shares that shell's process group:
    ;; a Ctrl-C or a closed terminal there must not take it from every other
    ;; session. It still stops on TERM, and by itself when idle.
    (doseq [sig ["INT" "HUP"]]
      (sun.misc.Signal/handle (sun.misc.Signal. sig) sun.misc.SignalHandler/SIG_IGN))
    (logging/configure-logging! {:log-file (str dir "/daemon.log") :enable-logging? true :log-level :info})
    (let [{:keys [stopped]} (server/start! {:version version
                                            :state-dir dir
                                            :env (or env (into {} (System/getenv)))
                                            :idle-exit-ms (or idle-exit-ms 28800000)
                                            :agent-model-fallback (resolve-agent-model-fallback
                                                                   (get opts :agent-model-fallback default-agent-model-fallback))})]
      @stopped
      (shutdown-agents)
      (System/exit 0))))
