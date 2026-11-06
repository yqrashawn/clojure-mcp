(ns clojure-mcp.agent.cchp-model
  "A LangChain4j ChatModel backed by a local cchp (claude-code-http-proxy).
   cchp runs Claude Code in `workspace_root` on its own machine's disk, so the
   default URL is localhost: a remote cchp would work in its own copy of the
   path, not the caller's checkout. Text only: cchp ignores client tools, so
   the agent is Claude Code acting with its own tools in the project dir."
  (:require [clojure.data.json :as json]
            [clojure.string :as string])
  (:import (dev.langchain4j.data.message AiMessage SystemMessage UserMessage)
           (dev.langchain4j.model.chat ChatModel)
           (dev.langchain4j.model.chat.request ChatRequest)
           (dev.langchain4j.model.chat.response ChatResponse)
           (dev.langchain4j.model.output FinishReason)
           (java.net ConnectException URI)
           (java.net.http HttpClient HttpConnectTimeoutException HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(defn cchp-url
  "Base URL of the cchp to use, from CCHP_URL in `env`."
  [env]
  (string/replace (get env "CCHP_URL" "http://localhost:8033") #"/+$" ""))

(def ^:private ^HttpClient client
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 2)) (.build))))

(defn healthy?
  "True only when GET <base-url>/api/health answers 200. Never throws."
  [base-url]
  (try
    (= 200 (.statusCode (.send ^HttpClient @client
                               (-> (HttpRequest/newBuilder (URI. (str base-url "/api/health")))
                                   (.timeout (Duration/ofSeconds 2))
                                   (.GET)
                                   (.build))
                               (HttpResponse$BodyHandlers/discarding))))
    (catch Exception _ false)))

(defn- ->message [m]
  (cond
    (instance? SystemMessage m) {"role" "system" "content" (.text ^SystemMessage m)}
    (instance? UserMessage m) {"role" "user" "content" (.singleText ^UserMessage m)}
    (instance? AiMessage m) {"role" "assistant" "content" (or (.text ^AiMessage m) "")}
    :else {"role" "user" "content" (str "Context: " m)}))

(defn- complete [{:keys [base-url model workspace-root timeout-ms]} messages]
  (let [url (str base-url "/v1/chat/completions")
        body (json/write-str {"model" model
                              "stream" false
                              "messages" messages
                              "metadata" {"workspace_root" workspace-root}})
        resp (try
               (.send ^HttpClient @client
                      (-> (HttpRequest/newBuilder (URI. url))
                          (.timeout (Duration/ofMillis timeout-ms))
                          (.header "Content-Type" "application/json")
                          (.POST (HttpRequest$BodyPublishers/ofString body))
                          (.build))
                      (HttpResponse$BodyHandlers/ofString))
               (catch ConnectException e
                 (throw (ex-info (str "cchp at " base-url " is not answering") {:url url} e)))
               (catch HttpConnectTimeoutException e
                 (throw (ex-info (str "cchp at " base-url " is not answering") {:url url} e))))
        status (.statusCode resp)]
    (when (not= 200 status)
      (let [text (.body resp)]
        (throw (ex-info (str "cchp at " base-url " answered " status ": " (subs text 0 (min 300 (count text))))
                        {:url url :status status}))))
    (get-in (json/read-str (.body resp)) ["choices" 0 "message" "content"])))

(defn create-chat-model
  "A ChatModel that sends each request to cchp's /v1/chat/completions with
   metadata.workspace_root = `workspace-root`."
  ^ChatModel [{:keys [base-url model workspace-root timeout-ms]
               :or {model "sonnet" timeout-ms 1800000}}]
  (let [opts {:base-url base-url :model model :workspace-root workspace-root :timeout-ms timeout-ms}]
    (reify ChatModel
      (doChat [_ request]
        (let [text (complete opts (mapv ->message (.messages ^ChatRequest request)))]
          (-> (ChatResponse/builder)
              (.aiMessage (AiMessage/from (str text)))
              (.finishReason FinishReason/STOP)
              (.build)))))))
