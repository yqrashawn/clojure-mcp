(ns clojure-mcp.daemon-test
  "The daemon is normally started by the shim, which only sees a start
   timeout and the tail of daemon.log: a bad :agent-model-fallback symbol has
   to fail with a message that names it."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp.agent.cchp-model :as cchp]
            [clojure-mcp.daemon :as daemon]))

(defn hook [_env] nil)

(deftest resolve-agent-model-fallback-test
  (testing "nil means no hook"
    (is (nil? (daemon/resolve-agent-model-fallback nil))))
  (testing "a qualified symbol resolves to the fn it names"
    (is (identical? hook (daemon/resolve-agent-model-fallback `hook))))
  (testing "an unresolvable symbol throws, naming it"
    (doseq [sym ['clojure-mcp.daemon-test/no-such-hook 'no.such.ns/hook]]
      (let [e (try (daemon/resolve-agent-model-fallback sym) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) (str sym))
        (is (string/includes? (str (ex-message e)) (str sym)))
        (is (= sym (:symbol (ex-data e))))))))

(deftest default-hook-is-cchp
  (testing "a shim-started daemon names no hook, so the default must be cchp's"
    (is (identical? cchp/agent-model-fallback
                    (daemon/resolve-agent-model-fallback daemon/default-agent-model-fallback)))))
