;; Copyright © Manetu, Inc.  All rights reserved

(ns manetu.performance-app.core
  (:require [medley.core :as m]
            [promesa.core :as p]
            [taoensso.timbre :as log]
            [clojure.core.async :refer [>!! <! go go-loop] :as async]
            [progrock.core :as pr]
            [kixi.stats.core :as kixi]
            [manetu.performance-app.commands :as commands]
            [manetu.performance-app.loader :as loader]
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
(defn get-records
  [{:keys [mode count vault-count namespace] :as options} path]
  (if count
    (if (= mode :standalone-attributes)
      (synthetic/load-synthetic-records vault-count namespace)
      (synthetic/load-synthetic-records count namespace))
    (loader/load-records path)))

(defn exec-test
  [{:keys [mode concurrency] :as options} path]
  (try
    (let [{:keys [n] :as records} (get-records options path)
          output-ch (async/chan (* 4 concurrency))]
      (log/debug "processing" n "records with options:" options)
      @(-> (driver.core/create options)
           (p/then
            (fn [driver]
              (let [mux (async/mult output-ch)
                    f (commands/get-handler mode driver)]
                (p/all [(t/now)
                        (execute-commands options f output-ch (:ch records))
                        (show-progress options n mux)
                        (compute-stats options n mux)]))))
           (p/then
            (fn [[start _ _ {:keys [successes] :as stats}]]
              (let [end (t/now)
                    d (t/duration end start)]
                (assoc stats :total-duration (round2 3 d) :rate (round2 2 (* (/ successes d) 1000))))))
           (p/then (fn [stats]
                     (render options stats)
                     stats))
           (p/catch
            (fn [e]
              (log/error "Exception detected during" mode ":" (ex-message e))
              {:error true :mode mode :message (ex-message e)}))))
    (catch Exception e
      (log/error "Exception in exec:" (.getMessage e))
      {:error true :mode mode :message (.getMessage e)})))

(defn validate-standalone-options [{:keys [count vault-count] :as options}]
  (when-not vault-count
    (throw (ex-info "vault-count is required for standalone operations" {})))
  (when (< vault-count 1)
    (throw (ex-info "vault-count must be greater than 0" {})))
  (when (< count vault-count)
    (throw (ex-info "operation count must be greater than or equal to vault-count" {})))
  options)

(defn create-vault-records
  "Create a sequence of vault records for initialization"
  [vault-count namespace]
  (synthetic/load-synthetic-records vault-count namespace))

(defn create-attribute-operation-records
  "Creates a channel of attribute operations distributed across vaults"
  [vault-count total-ops namespace]
  (let [base-records (->> (range 1 (inc vault-count))
                          (mapv #(synthetic/create-synthetic-record % namespace)))
        ch (async/chan)]
    (go
      (loop [iteration 1]
        (when (<= iteration total-ops)
          (let [vault-idx (mod (dec iteration) vault-count)
                record (nth base-records vault-idx)]
            (>!! ch {:data {:Email (get-in record [:data :Email])}
                     :label (:label record)
                     :iteration iteration})
            (recur (inc iteration)))))
      (async/close! ch))
    {:n total-ops :ch ch}))

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

(defn exec-standalone-attributes
  [{:keys [mode concurrency vault-count count namespace] :as options} path]
  (try
    (log/info "Starting standalone attribute test with" vault-count "vaults and" count "operations")

    (let [init-opts (assoc options :mode :create-vaults)
          vault-records (create-vault-records vault-count namespace)
          init-stats (exec-phase init-opts vault-records)]

      (if (pos? (:failures init-stats))
        (do
          (log/error "Vault initialization failed")
          {:error true :message "Vault initialization failed"})

        (do
          (log/info "Vault initialization complete. Starting attribute operations.")

          (let [attr-opts (assoc options :mode :standalone-attributes)
                attr-records (create-attribute-operation-records vault-count count namespace)
                attr-stats (exec-phase attr-opts attr-records)]

            (log/info "Attribute operations complete. Starting cleanup.")
            (let [cleanup-opts (assoc options :mode :delete-vaults)
                  cleanup-records (create-vault-records vault-count namespace)]
              (exec-phase cleanup-opts cleanup-records))
            attr-stats))))

    (catch Exception e
      (log/error "Exception in standalone attributes:" (ex-message e))
      {:error true :message (ex-message e)})))

(defn exec-configured-tests
  [{:keys [tests concurrency] :as config} options path]
  (let [results (for [c concurrency]
                  {:concurrency c
                   :tests (reduce
                           (fn [acc test-mode]
                             (let [test-opts (assoc options
                                                    :mode (keyword test-mode)
                                                    :concurrency c)
                                   test-opts (if (= test-mode "standalone-attributes")
                                               (validate-standalone-options test-opts)
                                               test-opts)
                                   result (if (= test-mode "standalone-attributes")
                                            (exec-standalone-attributes test-opts path)
                                            (exec-test test-opts path))]
                               (assoc acc test-mode result)))
                           {}
                           tests)})]
    (reports/aggregate-results results)))

(defn exec
  [{:keys [mode concurrency] :as options} path]
  (let [{:keys [n] :as records} (loader/load-records path)
        output-ch (async/chan (* 4 concurrency))]
    (log/debug "processing" n "records with options:" options)
    (-> (driver.core/create options)
        (p/then
         (fn [driver]
           (let [mux (async/mult output-ch)
                 f (commands/get-handler mode driver)]
             (p/all [(t/now)
                     (execute-commands options f output-ch (:ch records))
                     (show-progress options n mux)
                     (compute-stats options n mux)]))))
        (p/then
         (fn [[start _ _ {:keys [successes] :as stats}]]
           (let [end (t/now)
                 d (t/duration end start)]
             (-> stats
                 (assoc :total-duration (round2 3 d)
                        :rate (round2 2 (* (/ successes d) 1000)))
                 ((fn [stats]
                    (render options stats)
                    stats))))))
        (p/catch
         (fn [e]
           (log/error "Exception detected:" (ex-message e))
           {:error true})))))