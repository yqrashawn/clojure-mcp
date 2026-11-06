(ns clojure-mcp.tools.eval.discovery-test
  "When clojure_eval finds no nREPL, listing zero ports is a dead end: the
   agent's next move is to start a REPL, not to guess a port. These assert the
   hints track what the project actually has on disk (or says in its config)
   rather than a hardcoded guess."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp.config :as config]
            [clojure-mcp.tools.eval.discovery :as discovery]
            [clojure-mcp.tools.nrepl-ports.core :as ports])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-project
  "A fresh temp dir seeded with `files` ({name -> content}). Returns its path."
  [files]
  (let [dir (.toFile (Files/createTempDirectory "hints" (make-array FileAttribute 0)))]
    (doseq [[n content] files]
      (spit (File. dir n) content))
    (str dir)))

(defn- hints [dir] (discovery/start-repl-hints dir []))

(deftest start-repl-hints-names-declared-tasks-and-aliases
  (let [dir (tmp-project {"bb.edn" "{:tasks {my-nrepl {:task (println 1)}}}"
                          "deps.edn" "{:aliases {:cljs-nrepl {:main-opts [\"-m\" \"nrepl.cmdline\"]}}}"})]
    (is (some #{"bb my-nrepl"} (hints dir)))
    (is (some #{"clj -M:cljs-nrepl"} (hints dir)))))

(deftest start-repl-hints-omits-undeclared-commands
  (testing "emitting \"bb nrepl\" for any bb.edn sends agents into undeclared-alias errors"
    (is (empty? (hints (tmp-project {"bb.edn" "{:tasks {start {:task (println 1)}}}"
                                     "deps.edn" "{:aliases {:test {}}}"}))))))

(deftest start-repl-hints-names-actual-shadow-builds
  (let [dir (tmp-project {"shadow-cljs.edn" "{:builds {:app {} :test {}}}"})]
    (is (some #{"npx shadow-cljs watch app"} (hints dir)))
    (is (some #{"npx shadow-cljs watch test"} (hints dir)))))

(deftest start-repl-hints-skips-a-non-executable-repl-script
  (let [dir (tmp-project {"repl.sh" "#!/bin/sh\necho hi\n"})]
    (is (empty? (hints dir)))
    (.setExecutable (File. dir "repl.sh") true)
    (is (some #{"./repl.sh"} (hints dir)))))

(deftest start-repl-hints-tolerates-bad-files
  (testing "the discovery path must never throw"
    (is (empty? (hints (tmp-project {"deps.edn" "{:aliases {:nrepl }}}} ("}))))
    (is (empty? (hints (tmp-project {}))))
    (is (empty? (hints "/nonexistent/clojure-mcp/dir")))
    (is (= ["npx shadow-cljs watch 1"] (hints (tmp-project {"shadow-cljs.edn" "{:builds {1 {}}}"}))))
    (is (empty? (hints (tmp-project {"shadow-cljs.edn" "{:builds [:app :test]}"}))))))

(deftest start-repl-hints-picks-the-flag-per-alias
  (testing "-M reads :main-opts and -X runs :exec-fn; a dependency-only alias is neither"
    (let [dir (tmp-project {"deps.edn" (str "{:aliases {:cider-nrepl {:extra-deps {a/b {:mvn/version \"1\"}}}"
                                            "           :nrepl {:exec-fn my/start}"
                                            "           :x-nrepl {:main-opts [\"-m\" \"nrepl.cmdline\"]}}}")})]
      (is (= ["clj -X:nrepl" "clj -M:x-nrepl"] (hints dir))))))

(deftest start-repl-hints-lein
  (is (= ["lein repl"] (hints (tmp-project {"project.clj" "(defproject x \"1\")"})))))

(deftest configured-hint-comes-first-and-suppresses-npx
  (testing "a project that starts shadow its own way (zgh: no npm) must never be told npx"
    (let [dir (tmp-project {"shadow-cljs.edn" "{:builds {:dev {}}}"
                            "bb.edn" "{:tasks {nrepl {:task (println 1)}}}"})
          result (discovery/start-repl-hints dir ["bb start:detach"])]
      (is (= ["bb start:detach" "bb nrepl"] result))
      (is (not-any? #(str/includes? % "npx") result)))))

(deftest no-nrepl-help-text
  (testing "the agent gets the running ports, the cross-worktree warning and how to start its REPL"
    (let [dir (tmp-project {})
          client {::config/config {:nrepl-user-dir dir :start-repl-hint "bb start:detach"}}]
      (with-redefs [ports/discover-nrepl-ports
                    (fn [_] [{:host "localhost" :port 7777 :valid true :env-type :clj
                              :project-dir "/elsewhere/other-worktree" :matches-cwd false}])]
        (let [{:keys [outputs error]} (discovery/no-nrepl-help client dir)
              text (str/join "\n" (map second outputs))]
          (is (true? error))
          (is (str/includes? text "7777"))
          (is (str/includes? text "user.dir"))
          (is (str/includes? text "another worktree"))
          (is (str/includes? text "bb start:detach"))
          (is (not (str/includes? text "npx"))))))))

(deftest eval-tool-without-port-returns-help
  (testing "clojure_eval with no port anywhere answers with the help, not a bare error"
    (let [dir (tmp-project {"bb.edn" "{:tasks {nrepl {:task (println 1)}}}"})
          client-atom (atom {::config/config {:nrepl-user-dir dir}})
          tool-fn (:tool-fn ((requiring-resolve 'clojure-mcp.tools.eval.tool/eval-code) client-atom))
          p (promise)]
      (with-redefs [ports/discover-nrepl-ports (constantly [])]
        (tool-fn nil {"code" "1"} (fn [result error?] (deliver p {:result result :error? error?}))))
      (let [{:keys [result error?]} (deref p 5000 nil)]
        (is (true? error?))
        (is (str/includes? (str/join "\n" result) "bb nrepl"))))))
