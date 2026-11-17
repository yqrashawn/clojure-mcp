(ns clojure-mcp.shim-test
  "The shim is what an agent's MCP client actually launches. A shim that hangs
   or dies takes the whole clojure-mcp server away from the agent, so these
   pin the cases where the daemon is missing, slow, failing, or restarted,
   against fake daemons that speak the daemon <-> shim contract."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp.shim :as shim]
            [org.httpkit.server :as server])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- temp-dir [prefix]
  (.getCanonicalPath (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0)))))

(defn- start-fake!
  "A fake daemon that writes the state files like the real one and records
   every request it gets."
  [state-dir version]
  (let [seen (atom [])
        token "tok"
        sid (str "sid-" (rand-int 1000000))
        handler (fn [req]
                  (let [body (some-> (:body req) slurp)
                        msg (when-not (string/blank? body) (json/parse-string body true))]
                    (swap! seen conj {:method (:request-method req) :uri (:uri req)
                                      :headers (:headers req) :msg msg})
                    (cond
                      (not= (str "Bearer " token) (get-in req [:headers "authorization"])) {:status 401}
                      (= "/health" (:uri req)) {:status 200 :body (json/generate-string {:version version :pid 1})}
                      (= "/env" (:uri req)) {:status 200 :body "{\"id\":\"envid\"}"}
                      (= :get (:request-method req)) {:status 405}
                      (= :delete (:request-method req)) {:status 200}
                      (= "initialize" (:method msg)) {:status 200
                                                      :headers {"content-type" "application/json" "mcp-session-id" sid}
                                                      :body (json/generate-string {:jsonrpc "2.0" :id (:id msg)
                                                                                   :result {:protocolVersion "2025-06-18"}})}
                      (nil? (:id msg)) {:status 202}
                      (= "tools/list" (:method msg)) {:status 200
                                                      :headers {"content-type" "text/event-stream"}
                                                      :body (str "event: message\ndata: "
                                                                 (json/generate-string {:jsonrpc "2.0" :id (:id msg)
                                                                                        :result {:tools [{:name "clojure_eval"}]}})
                                                                 "\n\n")}
                      (= "die" (get-in msg [:params :name])) (server/as-channel req {:on-open server/close})
                      :else {:status 200 :headers {"content-type" "application/json"}
                             :body (json/generate-string {:jsonrpc "2.0" :id (:id msg) :result {:echo (:method msg)}})})))
        srv (server/run-server handler {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (.mkdirs (io/file state-dir))
    (spit (io/file state-dir "token") token)
    (spit (io/file state-dir "port") (str (server/server-port srv)))
    {:seen seen :sid sid :stop! #(do (server/server-stop! srv) (.delete (io/file state-dir "port")))}))

(defn- harness
  "Runs the shim in-process with a queue for stdin and one for stdout."
  [{:keys [state-dir spawn! start-timeout-ms instructions] :or {start-timeout-ms 5000}}]
  (let [in (LinkedBlockingQueue.)
        out (LinkedBlockingQueue.)
        exits (promise)
        ctx (shim/context {:project-dir "/w/my proj" :version "tv" :state-dir state-dir
                           :instructions instructions
                           :spawn! spawn! :start-timeout-ms start-timeout-ms
                           :read-line #(let [l (.take in)] (when-not (= ::eof l) l))
                           :emit #(.put out %)
                           :exit! #(deliver exits %)})]
    (future (shim/relay! ctx))
    {:send! #(.put in (json/generate-string %))
     :recv! (fn [ms] (some-> (.poll out ms TimeUnit/MILLISECONDS) (json/parse-string true)))
     :eof! #(.put in ::eof)
     :exits exits}))

(def ^:private init-msg
  {:jsonrpc "2.0" :id 1 :method "initialize"
   :params {:protocolVersion "2025-06-18" :capabilities {} :clientInfo {:name "t" :version "1"}}})

(defn- spawner
  "A spawn! that counts calls and starts a fake daemon after `delay-ms`."
  [state-dir fakes & [delay-ms]]
  (let [n (atom 0)]
    [n (fn [_ctx]
         (swap! n inc)
         (future (Thread/sleep (or delay-ms 0))
                 (swap! fakes conj (start-fake! state-dir "tv"))))]))

(deftest project-root-walks-up
  (let [root (temp-dir "root")
        deep (io/file root "src" "a")]
    (.mkdirs deep)
    (spit (io/file root "deps.edn") "{}")
    (is (= root (shim/project-root (str deep))))
    (testing "with no marker anywhere, the cwd itself"
      (let [bare (temp-dir "bare")]
        (is (= bare (shim/project-root bare)))))))

(deftest encode-project-path
  (is (= "/p/a/my%20proj/%C3%BC/mcp" (shim/mcp-path "/a/my proj/ü"))))

(deftest version-for-local-root
  (is (= "abc123" (shim/version {:sha "abc123"})))
  (let [v (shim/version {:local-root "/tmp"})]
    (is (re-matches #"local-[0-9a-f]{12}" v))
    (is (= v (shim/version {:local-root "/tmp/"})) "same dir, same daemon")))

(defn- write-config! [dir form]
  (.mkdirs (io/file dir ".clojure-mcp"))
  (spit (io/file dir ".clojure-mcp" "config.edn") form))

(deftest mcp-instructions-resolve-like-the-jvm
  ;; The daemon advertises no instructions, so through the shim this is the
  ;; only place a client learns what clojure-mcp offers.
  (let [home (temp-dir "home") proj (temp-dir "proj")]
    (testing "no config anywhere: the default text"
      (is (= shim/default-mcp-instructions (shim/mcp-instructions home proj))))
    (testing "home string alone"
      (write-config! home "{:mcp-instructions \"H\"}")
      (is (= "H" (shim/mcp-instructions home proj))))
    (testing "the project's string beats home's"
      (write-config! proj "{:mcp-instructions \"P\"}")
      (is (= "P" (shim/mcp-instructions home proj))))
    (testing "false in the project disables them over home's string"
      (write-config! proj "{:mcp-instructions false}")
      (is (nil? (shim/mcp-instructions home proj))))
    (testing "a blank string disables them"
      (write-config! proj "{:mcp-instructions \"  \"}")
      (is (nil? (shim/mcp-instructions home proj))))
    (testing "a project config without the key keeps home's"
      (write-config! proj "{:cljfmt true :x #env FOO}")
      (is (= "H" (shim/mcp-instructions home proj)) "unknown tags don't void the file"))
    (testing "an unreadable project config counts as {}"
      (write-config! proj "{:mcp-instructions false")
      (is (= "H" (shim/mcp-instructions home proj))))
    (testing "a non-string value: the default"
      (write-config! home "{:mcp-instructions 42}")
      (write-config! proj "{}")
      (is (= shim/default-mcp-instructions (shim/mcp-instructions home proj))))))

(deftest default-instructions-match-the-jvm
  ;; The shim keeps a copy because it can't load src/; a stale copy would
  ;; tell shim clients something the JVM no longer says.
  (let [src (slurp (io/file ".." "src" "clojure_mcp" "config.clj"))
        start (string/index-of src "(def default-mcp-instructions")
        form (read (java.io.PushbackReader. (java.io.StringReader. (subs src start))))]
    (is (some? start))
    (is (= 'str (first (last form))) "the def's value is still a (str ...) of literals")
    (is (= (eval (last form)) shim/default-mcp-instructions))))

(deftest initialize-reply-carries-instructions
  (let [dir (temp-dir "state") fakes (atom [])
        [_ spawn!] (spawner dir fakes)]
    (testing "present when resolved"
      (let [{:keys [send! recv!]} (harness {:state-dir dir :spawn! spawn! :instructions "X"})]
        (send! init-msg)
        (is (= "X" (get-in (recv! 1000) [:result :instructions])))))
    (testing "absent, not null, when disabled"
      (let [{:keys [send! recv!]} (harness {:state-dir dir :spawn! spawn!})]
        (send! init-msg)
        (is (not (contains? (:result (recv! 1000)) :instructions)))))
    (doseq [f @fakes] ((:stop! f)))))

(deftest initialize-answered-before-daemon
  (let [dir (temp-dir "state") fakes (atom [])
        [_ spawn!] (spawner dir fakes 2000)
        {:keys [send! recv!]} (harness {:state-dir dir :spawn! spawn!})
        t0 (System/currentTimeMillis)]
    (send! init-msg)
    (let [resp (recv! 1000)]
      (is (= 1 (:id resp)))
      (is (= "2025-06-18" (get-in resp [:result :protocolVersion])))
      (is (= {:listChanged true} (get-in resp [:result :capabilities :tools])))
      (is (< (- (System/currentTimeMillis) t0) 1000)))
    (testing "later requests are held until the daemon is up, then answered"
      (send! {:jsonrpc "2.0" :method "notifications/initialized"})
      (send! {:jsonrpc "2.0" :id 2 :method "tools/list"})
      (is (= [{:name "clojure_eval"}] (get-in (recv! 8000) [:result :tools]))))
    (doseq [f @fakes] ((:stop! f)))))

(deftest relays-json-and-sse
  (let [dir (temp-dir "state") fakes (atom [])
        [_ spawn!] (spawner dir fakes)
        {:keys [send! recv!]} (harness {:state-dir dir :spawn! spawn!})]
    (send! init-msg) (recv! 1000)
    (send! {:jsonrpc "2.0" :id 2 :method "tools/list"})
    (is (= 2 (:id (recv! 5000))) "SSE response")
    (send! {:jsonrpc "2.0" :id 3 :method "tools/call" :params {:name "x"}})
    (is (= {:echo "tools/call"} (:result (recv! 5000))) "JSON response")
    (let [posts (filter #(and (= :post (:method %)) (string/ends-with? (:uri %) "/mcp")) @(:seen (first @fakes)))]
      (testing "the client's own initialize is replayed to the daemon, once"
        (is (= 1 (count (filter #(= "initialize" (get-in % [:msg :method])) posts)))))
      (testing "requests go to the project's path with the daemon session"
        (is (every? #(= "/p/w/my%20proj/mcp" (:uri %)) posts))
        (is (= (:sid (first @fakes)) (get-in (last posts) [:headers "mcp-session-id"])))))
    (doseq [f @fakes] ((:stop! f)))))

(deftest env-id-header-sent
  (let [dir (temp-dir "state") fakes (atom [])
        [_ spawn!] (spawner dir fakes)
        {:keys [send! recv!]} (harness {:state-dir dir :spawn! spawn!})]
    (send! init-msg) (recv! 1000)
    (send! {:jsonrpc "2.0" :id 2 :method "tools/list"}) (recv! 5000)
    (let [seen @(:seen (first @fakes))
          env-post (first (filter #(= "/env" (:uri %)) seen))]
      (is (= (System/getenv "PATH") (get-in env-post [:msg :PATH])) "the shim's own env is forwarded")
      (is (every? #(= "envid" (get-in % [:headers "x-clojure-mcp-env"]))
                  (filter #(string/ends-with? (:uri %) "/mcp") seen))))
    (doseq [f @fakes] ((:stop! f)))))

(deftest daemon-restart-is-transparent
  (let [dir (temp-dir "state") fakes (atom [])
        [n spawn!] (spawner dir fakes)
        {:keys [send! recv!]} (harness {:state-dir dir :spawn! spawn!})]
    (send! init-msg) (recv! 1000)
    (send! {:jsonrpc "2.0" :id 2 :method "tools/list"}) (recv! 5000)
    ((:stop! (first @fakes)))
    (send! {:jsonrpc "2.0" :id 3 :method "tools/call" :params {:name "x"}})
    (is (= {:echo "tools/call"} (:result (recv! 8000))) "answered by the respawned daemon")
    (is (= 2 @n))
    (testing "the new daemon got the session replayed before the call"
      (is (= ["initialize" "notifications/initialized" "tools/call"]
             (keep #(get-in % [:msg :method]) (filter #(string/ends-with? (:uri %) "/mcp") @(:seen (second @fakes)))))))
    (doseq [f @fakes] ((:stop! f)))))

(deftest concurrent-cold-start-spawns-once
  (let [dir (temp-dir "state") fakes (atom [])
        [n spawn!] (spawner dir fakes 500)
        a (harness {:state-dir dir :spawn! spawn!})
        b (harness {:state-dir dir :spawn! spawn!})]
    (doseq [{:keys [send!]} [a b]]
      (send! init-msg)
      (send! {:jsonrpc "2.0" :id 2 :method "tools/list"}))
    (doseq [{:keys [recv!]} [a b]]
      (recv! 1000)
      (is (= 2 (:id (recv! 8000)))))
    (is (= 1 @n))
    (doseq [f @fakes] ((:stop! f)))))

(deftest spawn-failure-reports-log-tail
  (let [dir (temp-dir "state")
        spawn! (fn [_] (spit (io/file dir "daemon.log") "Error building classpath. boom line\n" :append true))
        {:keys [send! recv! exits]} (harness {:state-dir dir :spawn! spawn! :start-timeout-ms 1500})]
    (send! init-msg) (recv! 1000)
    (send! {:jsonrpc "2.0" :id 2 :method "tools/list"})
    (let [resp (recv! 6000)]
      (is (= 2 (:id resp)))
      (is (string/includes? (get-in resp [:error :message]) "boom line")))
    (is (= 1 (deref exits 3000 :no-exit)))))

(deftest stdin-eof-deletes-session
  (let [dir (temp-dir "state") fakes (atom [])
        [_ spawn!] (spawner dir fakes)
        {:keys [send! recv! eof! exits]} (harness {:state-dir dir :spawn! spawn!})]
    (send! init-msg) (recv! 1000)
    (send! {:jsonrpc "2.0" :id 2 :method "tools/list"}) (recv! 5000)
    (eof!)
    (is (= 0 (deref exits 5000 :no-exit)))
    (let [del (first (filter #(= :delete (:method %)) @(:seen (first @fakes))))]
      (is (= (:sid (first @fakes)) (get-in del [:headers "mcp-session-id"]))))
    (doseq [f @fakes] ((:stop! f)))))

(deftest start-detached-runs-the-command-into-the-log
  (testing "the real spawn path (the other tests inject spawn!) runs the command as argv, appending to daemon.log"
    (let [dir (temp-dir "state")]
      (spit (io/file dir "daemon.log") "earlier\n")
      @(shim/start-detached! dir ["sh" "-c" "echo out; echo err >&2"])
      (is (= "earlier\nout\nerr\n" (slurp (io/file dir "daemon.log")))))))

(deftest daemon-dying-mid-call-answers-the-call
  (testing "a connection dropped after the request reached the daemon is one failed call, not a hang"
    (let [dir (temp-dir "state") fakes (atom [])
          [n spawn!] (spawner dir fakes)
          {:keys [send! recv! exits]} (harness {:state-dir dir :spawn! spawn!})]
      (send! init-msg) (recv! 1000)
      (send! {:jsonrpc "2.0" :id 2 :method "tools/call" :params {:name "die"}})
      (let [resp (recv! 5000)]
        (is (= 2 (:id resp)))
        (is (= -32603 (get-in resp [:error :code]))))
      (testing "the call is not retried (it may have run) and the shim keeps serving"
        (is (= 1 (count (filter #(= "die" (get-in % [:msg :params :name])) @(:seen (first @fakes))))))
        (send! {:jsonrpc "2.0" :id 3 :method "tools/call" :params {:name "x"}})
        (is (= {:echo "tools/call"} (:result (recv! 5000))))
        (is (= :running (deref exits 500 :running)))
        (is (= 1 @n)))
      (doseq [f @fakes] ((:stop! f))))))
