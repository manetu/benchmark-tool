;; Copyright © Manetu, Inc.  All rights reserved
(ns manetu.benchmark-tool.core
  (:require [medley.core :as m]
            [promesa.core :as p]
            [taoensso.timbre :as log]
            [clojure.core.async :refer [>!! <! <!! go go-loop] :as async]
            [progrock.core :as pr]
            [kixi.stats.core :as kixi]
            [manetu.benchmark-tool.commands :as commands]
            [manetu.benchmark-tool.time :as t]
            [manetu.benchmark-tool.synthetic :as synthetic]
            [manetu.benchmark-tool.driver.core :as driver.core]
            [manetu.benchmark-tool.stats :as stats]
            [manetu.benchmark-tool.reports :as reports]))

(defn promise-put!
  [port val]
  (p/create
   (fn [resolve reject]
     (async/put! port val resolve))))

(defn execute-command
  [{:keys [verbose-errors]} f {{:keys [Email]} :data :as record}]
  (log/trace "record:" record)
  (let [start (t/now)]
    (-> (f record)
        (p/then
         (fn [result]
           (log/trace "success for" Email)
           {:success true :result result}))
        (p/catch
         (fn [e]
           (if verbose-errors
             (log/error (str Email ": " (ex-message e) " " (ex-data e)))
             (log/trace "ERROR" (str Email ": " (ex-message e) " " (ex-data e))))
           {:success false :exception e}))
        (p/then
         (fn [result]
           (let [end (t/now)
                 d (t/duration end start)]
             (log/trace Email "processed in" d "msecs")
             (assoc result
                    :email Email
                    :duration d)))))))

(defn execute-commands
  [{:keys [concurrency] :as options} f output-ch input-ch]
  (-> (p/all
       (map
        (fn [_]
          (p/vthread
           (loop []
             (when-let [m (<!! input-ch)]
               (>!! output-ch @(execute-command options f m))
               (recur)))))
        (range concurrency)))
      (p/then (fn [_]
                (async/close! output-ch)
                true))))

(defn show-progress
  [{:keys [progress concurrency] :as options} n mux]
  (when progress
    (let [ch (async/chan (* 4 concurrency))]
      (async/tap mux ch)
      (p/create
       (fn [resolve reject]
         (go-loop [bar (pr/progress-bar n)]
           (if (= (:progress bar) (:total bar))
             (do (pr/print (pr/done bar))
                 (resolve true))
             (do (<! ch)
                 (pr/print bar)
                 (recur (pr/tick bar))))))))))

(defn transduce-promise
  [{:keys [concurrency] :as options} n mux xform f]
  (p/create
   (fn [resolve reject]
     (go
       (let [ch (async/chan (* 4 concurrency))]
         (async/tap mux ch)
         (let [result (<! (async/transduce xform f (f) ch))]
           (resolve result)))))))

(defn round2
  "Round a double to the given precision (number of significant digits)"
  [precision ^double d]
  (let [factor (Math/pow 10 precision)]
    (/ (Math/round (* d factor)) factor)))

(defn compute-summary-stats
  [options n mux {:keys [description pred]}]
  (-> (transduce-promise options n mux (comp (filter pred) (map :duration)) stats/summary)
      (p/then (fn [{:keys [dist] :as summary}]
                (-> summary
                    (dissoc :dist)
                    (merge dist)
                    (as-> $ (m/map-vals #(round2 3 (or % 0)) $))
                    (assoc :description description))))))

(defn successful?
  [{:keys [success]}]
  (true? success))

(defn failed?
  [{:keys [success]}]
  (false? success))

(defn categorize-error [{:keys [exception]}]
  (when exception
    (let [data (ex-data exception)
          msg (ex-message exception)]
      (cond
        (re-find #"unauthorized|401|bad status response" msg) "Unauthorized"
        (re-find #"timeout|504" msg) "Timeout"
        (re-find #"not found|404" msg) "Not Found"
        :else "Other Error"))))

(def stat-preds
  [{:description "Errors" :pred failed?}
   {:description "Unauthorized Errors" :pred (fn [r] (and (failed? r) (= "Unauthorized" (categorize-error r))))}
   {:description "Timeout Errors" :pred (fn [r] (and (failed? r) (= "Timeout" (categorize-error r))))}
   {:description "Not Found Errors" :pred (fn [r] (and (failed? r) (= "Not Found" (categorize-error r))))}
   {:description "Successes" :pred successful?}
   {:description "Total" :pred identity}])
(defn count-msgs
  [options n mux pred]
  (transduce-promise options n mux (filter pred) kixi/count))

(defn compute-stats
  [options n mux]
  (-> (p/all (map (partial compute-summary-stats options n mux) stat-preds))
      (p/then (fn [summaries]
                (let [failures (or (:count (first (filter #(= (:description %) "Errors") summaries))) 0)
                      successes (or (:count (first (filter #(= (:description %) "Successes") summaries))) 0)
                      unauthorized (or (:count (first (filter #(= (:description %) "Unauthorized Errors")
                                                              summaries))) 0)
                      timeout (or (:count (first (filter #(= (:description %) "Timeout Errors") summaries))) 0)
                      not-found (or (:count (first (filter #(= (:description %) "Not Found Errors") summaries))) 0)]
                  (-> (first (filter #(= (:description %) "Total") summaries))
                      (merge {:failures failures
                              :successes successes
                              :unauthorized unauthorized
                              :timeout timeout
                              :not_found not-found})))))))
(defn render [options stats]
  (reports/render-stats options stats))

(defn exec-phase
  "Executes a test phase with proper stats collection"
  [{:keys [mode concurrency] :as options} records]
  (let [output-ch (async/chan (* 4 concurrency))]
    @(-> (driver.core/create options)
         (p/then
          (fn [driver]
            (let [mux (async/mult output-ch)
                  f (commands/get-handler mode driver)]
              (p/all [(t/now)
                      (execute-commands options f output-ch (:ch records))
                      (show-progress options (:n records) mux)
                      (compute-stats options (:n records) mux)]))))
         (p/then
          (fn [[start _ _ {:keys [successes] :as stats}]]
            (let [end (t/now)
                  d (t/duration end start)]
              (assoc stats
                     :total-duration (round2 3 d)
                     :rate (round2 2 (* (/ successes d) 1000))))))
         (p/then (fn [stats]
                   (render options stats)
                   stats)))))
(defn normalize-parameter [param]
  (if (sequential? param)
    param
    [param]))

(defn generate-parameter-combinations [test-config]
  (let [counts (normalize-parameter (:count test-config))
        tokens-per-job (normalize-parameter (:tokens_per_job test-config))
        token-types (normalize-parameter (:token_type test-config))]
    (if (#{:tokenizer :tokenizer_translate_e2e} (:test-name test-config))
      ;; For tokenizer tests, generate all combinations
      (for [count counts
            tpj tokens-per-job
            tt token-types]
        (assoc test-config
               :count count
               :tokens_per_job tpj
               :token_type tt))
      ;; For non-tokenizer tests, only vary count
      (for [count counts]
        (assoc test-config :count count)))))
(defn perform-cleanup
  "Performs cleanup operation for a specific test suite"
  [{:keys [count prefix] :as test-config} driver-options]
  (log/info "Starting cleanup operation for prefix:" prefix "with count:" count)
  (let [configs (generate-parameter-combinations test-config)
        results (for [{:keys [count prefix]} configs]
                  (let [cleanup-records (synthetic/load-synthetic-records count prefix)
                        cleanup-opts (assoc driver-options :mode :delete-vaults)
                        cleanup-stats (exec-phase cleanup-opts cleanup-records)]
                    (if (pos? (:failures cleanup-stats))
                      (log/warn "Some cleanup operations failed. Check logs for details.")
                      (log/info "Cleanup completed successfully"))

                    {:config {:count count :prefix prefix}
                     :results {"cleanup" cleanup-stats}}))]
    {"cleanup" results}))

(defn exec-vault-suite
  [{:keys [clean_up] :as test-config} driver-options]
  (if clean_up
    (do
      (log/info "Clean up mode enabled for vaults suite - skipping regular test execution")
      (perform-cleanup test-config driver-options))
    (do
      (let [configs (generate-parameter-combinations (assoc test-config :test-name :vaults))
            results (for [config configs]
                      (let [{:keys [count prefix]} config
                            _ (log/info "Starting vaults test suite with" count "vaults")
                            create-records (synthetic/load-synthetic-records count prefix)
                            delete-records (synthetic/load-synthetic-records count prefix)

                            create-stats (-> (assoc driver-options :mode :create-vaults)
                                             (exec-phase create-records))

                            _ (log/info "Vault creation complete. Starting deletion.")

                            delete-stats (-> (assoc driver-options :mode :delete-vaults)
                                             (exec-phase delete-records))]

                        {:config config
                         :results {"create-vaults" create-stats
                                   "delete-vaults" delete-stats}}))]
        {"vaults" results}))))

(defn exec-e2e-suite
  [{:keys [clean_up] :as test-config} driver-options]
  (if clean_up
    (do
      (log/info "Clean up mode enabled for e2e suite - skipping regular test execution")
      (perform-cleanup test-config driver-options))
    (do
      (let [configs (generate-parameter-combinations (assoc test-config :test-name :e2e))
            results (for [{:keys [count prefix] :as config} configs]
                      (do
                        (log/info "Starting e2e test suite with" count "operations")
                        (let [records (synthetic/load-synthetic-records count prefix)
                              stats (-> (assoc driver-options :mode :e2e)
                                        (exec-phase records))]
                          {:config config
                           :results {"full-lifecycle" stats}})))]
        {"e2e" results}))))

(defn exec-attributes-suite
  [{:keys [clean_up] :as test-config} driver-options]
  (if clean_up
    (do
      (log/info "Clean up mode enabled for attributes suite - skipping regular test execution")
      (perform-cleanup test-config driver-options))
    (do
      (let [configs (generate-parameter-combinations (assoc test-config :test-name :attributes))
            results (for [{:keys [count vault_count prefix] :as config} configs]
                      (do
                        (log/info "Starting attributes test suite with" vault_count "vaults and" count "operations")
                        (let [init-opts (assoc driver-options :mode :create-vaults)
                              vault-records (synthetic/load-synthetic-records vault_count prefix)
                              init-stats (exec-phase init-opts vault-records)]

                          (if (pos? (:failures init-stats))
                            {:config config
                             :results {"standalone-attributes" {:error true :message "Vault initialization failed"}}}

                            (let [attr-opts (assoc driver-options :mode :standalone-attributes)
                                  attr-records (synthetic/create-attribute-operation-records vault_count count prefix)
                                  attr-stats (exec-phase attr-opts attr-records)]

                              (log/info "Attribute operations complete. Starting cleanup.")
                              (let [cleanup-opts (assoc driver-options :mode :delete-vaults)
                                    cleanup-records (synthetic/load-synthetic-records vault_count prefix)]
                                (exec-phase cleanup-opts cleanup-records)
                                {:config config
                                 :results {"standalone-attributes" attr-stats}}))))))]
        {"attributes" results}))))
(defn exec-tokenizer-suite
  [{:keys [clean_up realm value_min value_max] :as test-config} driver-options]
  (if clean_up
    (do
      (log/info "Clean up mode enabled for tokenizer suite - skipping regular test execution")
      (perform-cleanup test-config driver-options))
    (do
      (let [configs (generate-parameter-combinations (assoc test-config :test-name :tokenizer))
            results (for [{:keys [count vault_count prefix tokens_per_job token_type] :as config} configs]
                      (do
                        (log/info "Starting tokenizer test with:"
                                  "count:" count
                                  "tokens_per_job:" tokens_per_job
                                  "token_type:" token_type)

                        (let [init-opts (assoc driver-options :mode :create-vaults)
                              vault-records (synthetic/load-synthetic-records vault_count prefix)
                              init-stats (exec-phase init-opts vault-records)]

                          (if (pos? (:failures init-stats))
                            {:config config
                             :results {"standalone-tokenize" {:error true :message "Vault initialization failed"}}}

                            (let [tokenize-opts (assoc driver-options :mode :tokenize-values)
                                  tokenize-records (synthetic/create-tokenize-operation-records
                                                    vault_count
                                                    count
                                                    {:prefix prefix
                                                     :realm realm
                                                     :value_min value_min
                                                     :value_max value_max
                                                     :tokens_per_job tokens_per_job
                                                     :token_type token_type})
                                  tokenize-stats (exec-phase tokenize-opts tokenize-records)]

                              (log/info "Tokenize operations complete. Starting cleanup.")
                              (let [cleanup-opts (assoc driver-options :mode :delete-vaults)
                                    cleanup-records (synthetic/load-synthetic-records vault_count prefix)]
                                (exec-phase cleanup-opts cleanup-records)
                                {:config config
                                 :results {"standalone-tokenize" tokenize-stats}}))))))]
        {"tokenizer" results}))))

(defn exec-tokenizer-translate-e2e-suite
  [{:keys [clean_up realm value_min value_max] :as test-config} driver-options]
  (if clean_up
    (do
      (log/info "Clean up mode enabled for tokenizer+translate e2e suite - skipping regular test execution")
      (perform-cleanup test-config driver-options))
    (do
      (let [configs (generate-parameter-combinations (assoc test-config :test-name :tokenizer_translate_e2e))
            results (for [{:keys [count vault_count prefix tokens_per_job token_type] :as config} configs]
                      (do
                        (log/info "Starting tokenizer+translate e2e test with:"
                                  "count:" count
                                  "tokens_per_job:" tokens_per_job
                                  "token_type:" token_type)

                        (let [init-opts (assoc driver-options :mode :create-vaults)
                              vault-records (synthetic/load-synthetic-records vault_count prefix)
                              init-stats (exec-phase init-opts vault-records)]

                          (if (pos? (:failures init-stats))
                            {:config config
                             :results {"tokenize-translate-e2e" {:error true :message "Vault initialization failed"}}}

                            (let [e2e-opts (assoc driver-options :mode :tokenize-translate-e2e)
                                  e2e-records (synthetic/create-tokenize-operation-records
                                               vault_count
                                               count
                                               {:prefix prefix
                                                :realm realm
                                                :value_min value_min
                                                :value_max value_max
                                                :tokens_per_job tokens_per_job
                                                :token_type token_type})
                                  e2e-stats (exec-phase e2e-opts e2e-records)]

                              (log/info "Tokenize+translate operations complete. Starting cleanup.")
                              (let [cleanup-opts (assoc driver-options :mode :delete-vaults)
                                    cleanup-records (synthetic/load-synthetic-records vault_count prefix)]
                                (exec-phase cleanup-opts cleanup-records)
                                {:config config
                                 :results {"tokenize-translate-e2e" e2e-stats}}))))))]
        {"tokenizer-translate-e2e" results}))))

(defn exec-test-suite
  "Executes a single test suite with the given configuration"
  [suite-name {:keys [enabled] :as suite-config} driver-options]
  (when enabled
    (log/info "Executing test suite:" suite-name)
    (case suite-name
      :vaults (exec-vault-suite suite-config driver-options)
      :e2e (exec-e2e-suite suite-config driver-options)
      :attributes (exec-attributes-suite suite-config driver-options)
      :tokenizer (exec-tokenizer-suite suite-config driver-options)
      :tokenizer_translate_e2e (exec-tokenizer-translate-e2e-suite suite-config driver-options))))

(defn exec-configured-tests
  "Execute all configured test suites"
  [{:keys [tests concurrency] :as config} options]
  (let [results (for [c concurrency]
                  {:concurrency c
                   :tests (reduce-kv
                           (fn [acc suite-name suite-config]
                             (if-let [suite-results (exec-test-suite
                                                     suite-name
                                                     suite-config
                                                     (assoc options :concurrency c))]
                               (merge acc suite-results)
                               acc))
                           {}
                           tests)})]
    (reports/aggregate-results results)))

(defn exec-tests
  "Main entry point for test execution"
  [options config]
  (try
    (log/info "Starting test execution with config:" config)
    (let [results (exec-configured-tests config options)]
      (-> results
          (reports/write-json-report (:json-file options))
          (reports/write-csv-report (:csv-file options)))
      results)
    (catch Exception e
      (log/error "Test execution failed:" (ex-message e))
      {:error true :message (ex-message e)})))