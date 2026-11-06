(ns clojure-mcp.agent.cchp-model-test
  "The sub-agent tools fall back to a local cchp. cchp runs Claude Code in
   metadata.workspace_root, so that field, not the prompt, decides which
   checkout the agent works in; and a cchp that is down must fail the call
   loudly rather than hang or answer with nothing."
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp.agent.cchp-model :as cchp])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (dev.langchain4j.data.message SystemMessage UserMessage)
           (dev.langchain4j.model.chat ChatModel)
           (java.net InetSocketAddress ServerSocket)))

(defn- fake-cchp
  "Starts an HTTP server answering every request with `status` and `body`.
   Returns [base-url captured-requests-atom stop-fn]."
  [status body]
  (let [seen (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (swap! seen conj {:method (.getRequestMethod ex)
                                          :path (.getPath (.getRequestURI ex))
                                          :body (slurp (.getRequestBody ex))})
                        (let [bytes (.getBytes ^String body "UTF-8")]
                          (.sendResponseHeaders ex status (count bytes))
                          (with-open [out (.getResponseBody ex)] (.write out bytes))))))
    (.start server)
    [(str "http://127.0.0.1:" (.getPort (.getAddress server))) seen #(.stop server 0)]))

(defn- dead-url []
  (str "http://127.0.0.1:" (with-open [s (ServerSocket. 0)] (.getLocalPort s))))

(def ^:private completion
  (json/write-str {:choices [{:index 0 :message {:role "assistant" :content "done in /w"} :finish_reason "stop"}]}))

(deftest posts-openai-shape-with-workspace-root
  (let [[url seen stop] (fake-cchp 200 completion)]
    (try
      (let [^ChatModel model (cchp/create-chat-model {:base-url url :workspace-root "/w"})
            reply (.chat model [(SystemMessage/from "be brief") (UserMessage/from "hi")])
            {:keys [method path body]} (first @seen)
            sent (json/read-str body)]
        (is (= "done in /w" (.text (.aiMessage reply))))
        (is (= ["POST" "/v1/chat/completions"] [method path]))
        (is (= "sonnet" (get sent "model")))
        (is (false? (get sent "stream")))
        (is (= "/w" (get-in sent ["metadata" "workspace_root"])))
        (is (= [["system" "be brief"] ["user" "hi"]]
               (map (juxt #(get % "role") #(get % "content")) (get sent "messages")))))
      (finally (stop)))))

(deftest healthy-only-on-200
  (let [[ok _ stop-ok] (fake-cchp 200 "{}")
        [bad _ stop-bad] (fake-cchp 500 "{}")]
    (try
      (is (true? (cchp/healthy? ok)))
      (is (false? (cchp/healthy? bad)))
      (is (false? (cchp/healthy? (dead-url))))
      (finally (stop-ok) (stop-bad)))))

(deftest errors-are-loud
  (let [[url _ stop] (fake-cchp 500 "{\"error\":{\"message\":\"turn limit\"}}")]
    (try
      (let [e (try (.chat ^ChatModel (cchp/create-chat-model {:base-url url :workspace-root "/w"}) "hi")
                   nil
                   (catch Exception e e))]
        (is (some? e))
        (is (string/includes? (str (ex-message e) (some-> e ex-cause ex-message)) "turn limit")))
      (finally (stop))))
  (testing "a cchp that went away says so"
    (let [e (try (.chat ^ChatModel (cchp/create-chat-model {:base-url (dead-url) :workspace-root "/w"}) "hi")
                 nil
                 (catch Exception e e))]
      (is (string/includes? (str (ex-message e) (some-> e ex-cause ex-message)) "not answering")))))

(deftest cchp-url-defaults-to-local
  (is (= "http://localhost:8033" (cchp/cchp-url {})))
  (is (= "http://studio:9000" (cchp/cchp-url {"CCHP_URL" "http://studio:9000/"}))))
