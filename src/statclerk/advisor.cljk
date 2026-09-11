(ns statclerk.advisor
  "StatFinanceInsuranceClerksAdvisor — proposes a batch operation
  (reconcile a batch, post an adjustment) for a registered
  organization. Swappable mock/llm; the advisor ONLY proposes —
  `statclerk.governor` checks the aggregation identity independently.
  Modeled on cloud-itonami-isco-4311's advisor.

  A proposal: {:op :reconcile-batch|:post-adjustment
               :effect :propose :batch-id str
               :stake kw :confidence n :rationale str}"
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake batch-id] :as request}]
  {:op op
   :effect :propose
   :batch-id batch-id
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a statistical/finance/insurance clerk advisor. Given a
   request, propose an :op and the :batch-id, an honest :confidence
   and a :stake. Never claim a batch reconciles — the governor
   recomputes the line-item sum and checks it against the header
   total.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
