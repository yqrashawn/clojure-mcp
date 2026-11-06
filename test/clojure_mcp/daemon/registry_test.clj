(ns clojure-mcp.daemon.registry-test
  "The daemon keys everything a tool touches (nREPL port, config, scratch pad)
   by project. If two projects shared an entry, an agent in one worktree would
   silently evaluate in another worktree's JVM."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp.core :as core]
            [clojure-mcp.daemon.registry :as registry])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(defn- temp-dir []
  (.getCanonicalPath (.toFile (Files/createTempDirectory "registry-test" (make-array FileAttribute 0)))))

(deftest entries-are-per-canonical-dir
  (let [reg (registry/new-registry)
        a (temp-dir)
        b (temp-dir)
        link (str (temp-dir) "/link-to-a")]
    (Files/createSymbolicLink (.toPath (io/file link)) (.toPath (io/file a)) (make-array FileAttribute 0))
    (let [ea (registry/get-or-create! reg a)
          eb (registry/get-or-create! reg b)]
      (is (not (identical? (:nrepl-client-atom ea) (:nrepl-client-atom eb))))
      (is (= a (:dir ea)))
      (testing "a symlinked path is the same project, not a second entry"
        (is (identical? (:nrepl-client-atom ea)
                        (:nrepl-client-atom (registry/get-or-create! reg link)))))
      (is (= 2 (count @reg))))))

(deftest rebuilds-when-nrepl-port-changes
  (let [reg (registry/new-registry)
        dir (temp-dir)
        port-file (io/file dir ".nrepl-port")]
    (spit port-file "1111")
    (let [e1 (registry/get-or-create! reg dir)]
      (is (= 1111 (:nrepl-port e1)))
      (is (= 1111 (:port @(:nrepl-client-atom e1))))
      (testing "same port: same entry"
        (is (identical? (:nrepl-client-atom e1) (:nrepl-client-atom (registry/get-or-create! reg dir)))))
      (spit port-file "2222")
      (let [e2 (registry/get-or-create! reg dir)]
        (is (= 2222 (:nrepl-port e2)))
        (is (= 2222 (:port @(:nrepl-client-atom e2))))
        (testing "the port file disappearing (REPL stopping) keeps the entry"
          (.delete port-file)
          (is (identical? (:nrepl-client-atom e2) (:nrepl-client-atom (registry/get-or-create! reg dir)))))))))

(deftest evicts-by-ttl-and-cap
  (let [reg (registry/new-registry)
        [a b c] (repeatedly 3 temp-dir)]
    (doseq [d [a b c]] (registry/get-or-create! reg d))
    (swap! reg assoc-in [a :last-used] (.minusSeconds (Instant/now) 3600))
    (swap! reg assoc-in [b :last-used] (.minusSeconds (Instant/now) 10))
    (registry/evict-stale! reg {:ttl-ms 60000 :max-entries 50})
    (is (= #{b c} (set (keys @reg))) "only the entry idle past the TTL goes")
    (registry/evict-stale! reg {:ttl-ms 60000 :max-entries 1})
    (is (= #{c} (set (keys @reg))) "over the cap, the most recently used stays")))

(deftest global-client-atom-untouched
  (let [before @core/nrepl-client-atom]
    (registry/get-or-create! (registry/new-registry) (temp-dir))
    (is (identical? before @core/nrepl-client-atom))))

(deftest project-config-disables-tool
  (let [dir (temp-dir)]
    (io/make-parents (io/file dir ".clojure-mcp" "config.edn"))
    (spit (io/file dir ".clojure-mcp" "config.edn") "{:disable-tools [:bash]}")
    (let [e (registry/get-or-create! (registry/new-registry) dir)]
      (is (false? (registry/tool-enabled? e "bash")))
      (is (true? (registry/tool-enabled? e "clojure_eval")))
      (is (fn? (get (:tool-fns e) "clojure_eval"))))))
