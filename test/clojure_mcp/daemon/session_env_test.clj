(ns clojure-mcp.daemon.session-env-test
  "One daemon serves sessions started from different shells. Whatever reads the
   environment on a caller's behalf (a local bash command, the API-key lookup
   for agent tools) must see that caller's environment, not the environment of
   whichever shell happened to spawn the daemon."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp.agent.langchain :as chain]
            [clojure-mcp.config :as config]
            [clojure-mcp.tools.bash.core :as bash]))

(defn- run [cmd]
  (bash/execute-bash-command nil {:command cmd :timeout-ms 10000}))

(deftest bash-sees-session-env
  (binding [config/*session-env* {"PATH" (System/getenv "PATH") "ZZ_SESSION" "s1"}]
    (is (= "s1" (:stdout (run "echo $ZZ_SESSION")))))
  (testing "a variable of the daemon's own env that the caller does not have is not leaked"
    (binding [config/*session-env* {"PATH" (System/getenv "PATH")}]
      (is (= "" (:stdout (run "echo $HOME")))))))

(deftest bash-without-session-env-inherits
  (is (= (System/getenv "HOME") (:stdout (run "echo $HOME")))))

(deftest agent-model-follows-the-caller
  (binding [config/*session-env* {"ANTHROPIC_API_KEY" "x"}]
    (is (some? (chain/agent-model))))
  (testing "a key only the daemon's env has does not count for a caller without it"
    (binding [config/*session-env* {}]
      (is (nil? (chain/agent-model))))))
