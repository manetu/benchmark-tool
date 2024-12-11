;; Copyright © Manetu, Inc.  All rights reserved
(ns manetu.performance-app.reports
  (:require [cheshire.core :as json]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [doric.core :refer [table]]
            [taoensso.timbre :as log]))

(defn order-stats [stats]
  (into (sorted-map)
        {:failures (:failures stats)
         :successes (:successes stats)
         :min (:min stats)
         :mean (:mean stats)
         :stddev (:stddev stats)
         :p50 (:p50 stats)
         :p90 (:p90 stats)
         :p95 (:p95 stats)
         :p99 (:p99 stats)
         :max (:max stats)
         :total-duration (:total-duration stats)
         :rate (:rate stats)
         :count (:count stats)}))

(defn format-csv-row [concurrency suite-name config section stats]
  (let [token-type (when (#{:tokenizer :tokenizer_translate_e2e} (:test-name config))
                     (:token_type config))
        tokens-per-job (when (#{:tokenizer :tokenizer_translate_e2e} (:test-name config))
                         (:tokens_per_job config))]
    [(str concurrency)
     suite-name
     section
     (:successes stats)
     (:failures stats)
     (:min stats)
     (:mean stats)
     (:stddev stats)
     (:p50 stats)
     (:p90 stats)
     (:p95 stats)
     (:p99 stats)
     (:max stats)
     (:total-duration stats)
     (:rate stats)
     (:count stats)
     (or token-type "N/A")
     (or tokens-per-job "N/A")]))

(defn results->csv-data [{:keys [timestamp results]}]
  (let [headers ["Concurrency" "Test Suite" "Section" "Successes" "Failures" "Min (ms)" "Mean (ms)" "Stddev"
                 "P50 (ms)" "P90 (ms)" "P95 (ms)" "P99 (ms)" "Max (ms)" "Total Duration (ms)"
                 "Rate (ops/sec)" "Count" "Token Type" "Tokens Per Job"]
        rows (for [result results
                   [suite-name suite-results] (:tests result)
                   test-result suite-results
                   [section stats] (:results test-result)]
               (format-csv-row
                (:concurrency result)
                (name suite-name)
                (:config test-result)
                section
                stats))]
    (cons headers rows)))

(defn ensure-parent-dirs [file-path]
  (let [parent (.getParentFile (io/file file-path))]
    (when-not (.exists parent)
      (.mkdirs parent))))

(defn write-csv-report [stats csv-file]
  (try
    (ensure-parent-dirs csv-file)
    (with-open [writer (io/writer csv-file)]
      (csv/write-csv writer (results->csv-data stats)))
    (log/info "CSV results written to" csv-file)
    stats
    (catch Exception e
      (log/error "Failed to write CSV results to file:" (.getMessage e))
      {:error true :message (.getMessage e)})))

(defn write-json-report [stats json-file]
  (try
    (ensure-parent-dirs json-file)
    (spit json-file (json/generate-string stats {:pretty true}))
    (log/info "Results written to" json-file)
    stats
    (catch Exception e
      (log/error "Failed to write results to file:" (.getMessage e))
      {:error true :message (.getMessage e)})))

(defn render-stats [{:keys [fatal-errors] :as options} {:keys [failures] :as stats}]
  (println (table [:successes :failures :min :mean :stddev :p50 :p90 :p95 :p99 :max :total-duration :rate] [stats]))
  (if (and fatal-errors (pos? failures))
    -1
    0))

(defn aggregate-results [results]
  {:timestamp (.toString (java.time.Instant/now))
   :results (mapv (fn [result]
                    (update result :tests
                            (fn [tests]
                              (reduce-kv (fn [m k v]
                                           (assoc m k
                                                  (mapv (fn [test-result]
                                                          (update test-result :results
                                                                  (fn [r]
                                                                    (reduce-kv (fn [m2 k2 v2]
                                                                                 (assoc m2 k2 (order-stats v2)))
                                                                               (sorted-map)
                                                                               r))))
                                                        v)))
                                         (sorted-map)
                                         tests))))
                  results)})