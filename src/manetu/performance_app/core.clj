;; current core.clj
(ns manetu.performance-app.core
  (:require [medley.core :as m]
            [promesa.core :as p]
            [taoensso.timbre :as log]
            [clojure.core.async :refer [>!! <! go go-loop] :as async]
            [progrock.core :as pr]
            [kixi.stats.core :as kixi]
            [manetu.performance-app.commands :as commands]
            [manetu.performance-app.time :as t]
            [manetu.performance-app.synthetic :as synthetic]
            [manetu.performance-app.driver.core :as driver.core]
            [manetu.performance-app.stats :as stats]
            [manetu.performance-app.reports :as reports]))

(defn promise-put!
  [port val]
  (p/create
   (fn [resolve reject]
     (async/put! port val resolve))))

(defn execute-command
  [{:keys [verbose-errors]} f {{:keys [Email]} :data :as record} ch]
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
             (promise-put! ch (assoc result
                                     :email Email
                                     :duration d)))))
        (p/then
         (fn [_]
           (async/close! ch))))))

(defn execute-commands
  [{:keys [concurrency] :as options} f output-ch input-ch]
  (p/create
   (fn [resolve reject]
     (go
       (log/trace "launching" concurrency "requests")
       (<! (async/pipeline-async concurrency
                                 output-ch
                                 (partial execute-command options f)
                                 input-ch))
       (resolve true)))))

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
  [options n mux]
  (-> (transduce-promise options n mux (map :duration) stats/summary)
      (p/then (fn [{:keys [dist] :as summary}]
                (-> summary
                    (dissoc :dist)
                    (merge dist)
                    (as-> $ (m/map-vals #(round2 3 (or % 0)) $)))))))

(defn successful?
  [{:keys [success]}]
  (true? success))

(defn failed?
  [{:keys [success]}]
  (false? success))

(defn count-msgs
  [options n mux pred]
  (transduce-promise options n mux (filter pred) kixi/count))

(defn compute-stats
  [options n mux]
  (-> (p/all [(compute-summary-stats options n mux)
              (count-msgs options n mux successful?)
              (count-msgs options n mux failed?)])
      (p/then (fn [[summary s f]] (assoc summary :successes s :failures f)))))

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

(defn exec-vault-suite
  "Executes the vaults test suite - creates and then deletes vaults"
  [{:keys [count prefix] :as test-config} driver-options]
  (log/info "Starting vaults test suite with" count "vaults")
  (let [create-records (synthetic/load-synthetic-records count prefix)
        delete-records (synthetic/load-synthetic-records count prefix)

        create-stats (-> (assoc driver-options :mode :create-vaults)
                         (exec-phase create-records))

        _ (log/info "Vault creation complete. Starting deletion.")

        delete-stats (-> (assoc driver-options :mode :delete-vaults)
                         (exec-phase delete-records))]

    {"create-vaults" create-stats
     "delete-vaults" delete-stats}))

(defn exec-e2e-suite
  "Executes the e2e test suite - full lifecycle test"
  [{:keys [count prefix] :as test-config} driver-options]
  (log/info "Starting e2e test suite with" count "operations")
  (let [records (synthetic/load-synthetic-records count prefix)
        stats (-> (assoc driver-options :mode :e2e)
                  (exec-phase records))]

    {"full-lifecycle" stats}))

(defn exec-attributes-suite
  "Executes the attributes test suite - standalone attribute operations"
  [{:keys [count vault_count prefix] :as test-config} driver-options]
  (log/info "Starting attributes test suite with" vault_count "vaults and" count "operations")
  (let [init-opts (assoc driver-options :mode :create-vaults)
        vault-records (synthetic/load-synthetic-records vault_count prefix)
        init-stats (exec-phase init-opts vault-records)]

    (if (pos? (:failures init-stats))
      (do
        (log/error "Vault initialization failed")
        {"standalone-attributes" {:error true :message "Vault initialization failed"}})

      (do
        (log/info "Vault initialization complete. Starting attribute operations.")
        (let [attr-opts (assoc driver-options :mode :standalone-attributes)
              attr-records (synthetic/create-attribute-operation-records vault_count count prefix)
              attr-stats (exec-phase attr-opts attr-records)]

          (log/info "Attribute operations complete. Starting cleanup.")
          (let [cleanup-opts (assoc driver-options :mode :delete-vaults)
                cleanup-records (synthetic/load-synthetic-records vault_count prefix)]
            (exec-phase cleanup-opts cleanup-records)
            {"standalone-attributes" attr-stats}))))))

(defn exec-test-suite
  "Executes a single test suite with the given configuration"
  [suite-name {:keys [enabled] :as suite-config} driver-options]
  (when enabled
    (log/info "Executing test suite:" suite-name)
    (case suite-name
      :vaults (exec-vault-suite suite-config driver-options)
      :e2e (exec-e2e-suite suite-config driver-options)
      :attributes (exec-attributes-suite suite-config driver-options))))

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
                               (assoc acc suite-name suite-results)
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