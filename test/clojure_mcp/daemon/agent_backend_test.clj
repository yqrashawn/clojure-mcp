(ns clojure-mcp.daemon.agent-backend-test
  "Sub-agent tools are listed only when they have a model to call. A local
   cchp is the fallback, after a model the project configured and after an
   API key the caller has: it runs Claude Code in the project dir, which costs
   a turn on cchp, so it must never displace a choice someone made."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp.agent.cchp-model :as cchp]
            [clojure-mcp.daemon.registry :as registry]
            [clojure-mcp.daemon.server :as server]
            [clojure-mcp.tools.agent-tool-builder.core :as agent-core])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (dev.langchain4j.model.anthropic AnthropicChatModel)
           (dev.langchain4j.model.chat ChatModel)
           (java.net InetSocketAddress ServerSocket URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir [prefix]
  (.getCanonicalPath (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0)))))

(defn- fake-cchp []
  (let [seen (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        reply (json/write-str {:choices [{:index 0 :message {:role "assistant" :content "agent says hi"}
                                          :finish_reason "stop"}]})]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (swap! seen conj {:path (.getPath (.getRequestURI ex)) :body (slurp (.getRequestBody ex))})
                        (let [bytes (.getBytes ^String (if (= "/api/health" (.getPath (.getRequestURI ex))) "{}" reply) "UTF-8")]
                          (.sendResponseHeaders ex 200 (count bytes))
                          (with-open [out (.getResponseBody ex)] (.write out bytes))))))
    (.start server)
    {:url (str "http://127.0.0.1:" (.getPort (.getAddress server))) :seen seen :stop #(.stop server 0)}))

(defn- dead-url []
  (str "http://127.0.0.1:" (with-open [s (ServerSocket. 0)] (.getLocalPort s))))

(defn- env-id [daemon env]
  (-> (.send (HttpClient/newHttpClient)
             (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (:port daemon) "/env")))
                 (.header "Authorization" (str "Bearer " (:token daemon)))
                 (.POST (HttpRequest$BodyPublishers/ofString (json/write-str env)))
                 (.build))
             (HttpResponse$BodyHandlers/ofString))
      .body json/read-str (get "id")))

(defn- rpc-session [daemon dir & [env]]
  (let [http (HttpClient/newHttpClient)
        eid (some->> env (env-id daemon))
        path (str "/p" dir "/mcp")
        ids (atom 0)
        sid (atom nil)
        send (fn [body]
               (let [b (cond-> (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (:port daemon) path)))
                                   (.header "Authorization" (str "Bearer " (:token daemon)))
                                   (.header "Accept" "application/json, text/event-stream")
                                   (.header "Content-Type" "application/json"))
                         @sid (.header "Mcp-Session-Id" @sid)
                         eid (.header "X-Clojure-Mcp-Env" eid))
                     r (.send http (.build (.POST b (HttpRequest$BodyPublishers/ofString (json/write-str body))))
                              (HttpResponse$BodyHandlers/ofString))]
                 (when-let [s (.orElse (.firstValue (.headers r) "mcp-session-id") nil)] (reset! sid s))
                 (->> (string/split-lines (.body r))
                      (keep #(or (second (re-matches #"data:\s?(.*)" %)) (when (string/starts-with? % "{") %)))
                      (map #(json/read-str % :key-fn keyword))
                      first)))
        rpc (fn [method params] (send {:jsonrpc "2.0" :id (swap! ids inc) :method method :params params}))]
    (rpc "initialize" {:protocolVersion "2025-06-18" :capabilities {} :clientInfo {:name "t" :version "1"}})
    (send {:jsonrpc "2.0" :method "notifications/initialized"})
    rpc))

(defn- tool-names [env]
  (let [d (server/start! {:version "v" :state-dir (temp-dir "state") :env env :idle-exit-ms 3600000
                          :agent-model-fallback cchp/agent-model-fallback})]
    (try (set (map :name (get-in ((rpc-session d (temp-dir "p")) "tools/list" {}) [:result :tools])))
         (finally ((:stop! d))))))

(deftest agent-tools-listed-with-cchp
  (let [{:keys [url stop]} (fake-cchp)]
    (try (is (contains? (tool-names {"CCHP_URL" url}) "dispatch_agent"))
         (finally (stop)))))

(deftest agent-tools-absent-without-backend
  (let [names (tool-names {"CCHP_URL" (dead-url)})]
    (is (contains? names "clojure_eval"))
    (is (not-any? #(contains? names %) ["dispatch_agent" "architect" "code_critique" "clojure_edit_agent"]))))

(defn- completions [seen]
  (filter #(string/ends-with? (:path %) "/chat/completions") @seen))

(deftest api-key-beats-cchp
  (testing "each session's agent gets its own model: a key in one session's env never serves another"
    (let [{:keys [url seen stop]} (fake-cchp)
          dir (temp-dir "p")
          client (:nrepl-client-atom (registry/get-or-create! (registry/new-registry) dir))
          agent-model (fn [env]
                        (with-bindings (server/session-bindings env dir cchp/agent-model-fallback)
                          (:model (agent-core/get-or-create-agent client {:id :t :name "t" :system-message "s"}))))]
      (try
        (is (instance? AnthropicChatModel (agent-model {"ANTHROPIC_API_KEY" "x" "CCHP_URL" url})))
        (testing "without a key, the same project and agent get the cchp model"
          (let [m (agent-model {"CCHP_URL" url})]
            (is (not (instance? AnthropicChatModel m)))
            (.chat ^ChatModel m "hi")
            (is (= 1 (count (completions seen))))))
        (testing "a session with the same env reuses its agent"
          (is (identical? (agent-model {"CCHP_URL" url}) (agent-model {"CCHP_URL" url}))))
        (finally (stop))))))

(deftest keyless-session-gets-cchp-on-a-project-a-keyed-session-opened
  (let [{:keys [url seen stop]} (fake-cchp)
        dir (temp-dir "p")
        d (server/start! {:version "v" :state-dir (temp-dir "state") :env {"CCHP_URL" url} :idle-exit-ms 3600000
                          :agent-model-fallback cchp/agent-model-fallback})]
    (try
      ((rpc-session d dir {"ANTHROPIC_API_KEY" "x" "CCHP_URL" url}) "tools/call" {:name "glob_files" :arguments {:pattern "*"}})
      (let [{:keys [result]} ((rpc-session d dir {"CCHP_URL" url}) "tools/call" {:name "dispatch_agent" :arguments {:prompt "say hi"}})]
        (is (not (:isError result)) (pr-str result))
        (is (= 1 (count (completions seen)))))
      (finally ((:stop! d)) (stop)))))

(deftest project-model-beats-cchp
  (let [{:keys [url seen stop]} (fake-cchp)
        mine (fake-cchp)
        dir (temp-dir "p")
        d (server/start! {:version "v" :state-dir (temp-dir "state") :env {"CCHP_URL" url} :idle-exit-ms 3600000
                          :agent-model-fallback cchp/agent-model-fallback})]
    (io/make-parents (io/file dir ".clojure-mcp" "config.edn"))
    (spit (io/file dir ".clojure-mcp" "config.edn")
          (pr-str {:models {:openai/mine {:provider :openai :model-name "gpt-4o-mini" :api-key "k" :base-url (:url mine)}}
                   :tools-config {:architect {:model :openai/mine}}}))
    (try
      (let [{:keys [result]} ((rpc-session d dir {"CCHP_URL" url}) "tools/call" {:name "architect" :arguments {:prompt "plan"}})]
        (is (not (:isError result)) (pr-str result))
        (is (= 1 (count (completions (:seen mine)))) "the project's model answered")
        (is (empty? (completions seen)) "cchp was not called"))
      (finally ((:stop! d)) (stop) ((:stop mine))))))

(deftest dispatch-agent-reaches-cchp
  (let [{:keys [url seen stop]} (fake-cchp)
        dir (temp-dir "p")
        d (server/start! {:version "v" :state-dir (temp-dir "state") :env {"CCHP_URL" url} :idle-exit-ms 3600000
                          :agent-model-fallback cchp/agent-model-fallback})]
    (try
      (let [{:keys [result]} ((rpc-session d dir) "tools/call" {:name "dispatch_agent" :arguments {:prompt "say hi"}})
            completion (some #(when (= "/v1/chat/completions" (:path %)) (json/read-str (:body %))) @seen)]
        (is (not (:isError result)) (pr-str result))
        (is (string/includes? (string/join (map :text (:content result))) "agent says hi"))
        (is (= dir (get-in completion ["metadata" "workspace_root"]))))
      (finally ((:stop! d)) (stop)))))
