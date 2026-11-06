(ns clojure-mcp.daemon
  "Entry point of the shared daemon: one JVM serving every project's MCP
   sessions on 127.0.0.1. Started by the bb shim (shim/) or by hand with
   `clojure -X:daemon :version '\"dev\"'`. State lives in
   ~/.cache/clojure-mcp/daemons/<version>/."
  (:require [clojure-mcp.daemon.server :as server]
            [clojure-mcp.daemon.state :as state]
            [clojure-mcp.logging :as logging]))

(defn start
  "Runs the daemon until it has been idle for `idle-exit-ms` (default 8 h),
   then exits the JVM. `version` names the state dir: the shim passes the
   pinned fork sha, so projects pinning different shas never share a daemon."
  [{:keys [version state-root idle-exit-ms env]}]
  (assert (some? version) ":version is required")
  (let [version (str version)
        dir (state/state-dir (or state-root (state/default-root)) version)]
    (state/ensure-dir! dir)
    (logging/configure-logging! {:log-file (str dir "/daemon.log") :enable-logging? true :log-level :info})
    (let [{:keys [stopped]} (server/start! {:version version
                                            :state-dir dir
                                            :env (or env (into {} (System/getenv)))
                                            :idle-exit-ms (or idle-exit-ms 28800000)})]
      @stopped
      (shutdown-agents)
      (System/exit 0))))
