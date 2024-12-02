(ns manetu.performance-app.config
  (:require [yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def valid-test-suites
  #{:vaults :e2e :attributes})
(def valid-log-levels
  #{:trace :debug :info :warn :error})

(defn validate-test-config [test-name {:keys [enabled count prefix vault_count] :as config}]
  (when-not (map? config)
    (throw (ex-info (format "Test suite '%s' configuration must be a map" test-name)
                    {:test-suite test-name :config config})))

  ;; Check required fields exist
  (when-not (every? #(contains? config %) [:enabled :count :prefix])
    (throw (ex-info (format "Test suite '%s' missing required fields. Must have enabled, count, and prefix" test-name)
                    {:test-suite test-name
                     :config config
                     :required-fields [:enabled :count :prefix]})))

  ;; Validate value types and ranges
  (when-not (boolean? enabled)
    (throw (ex-info (format "Test suite '%s': enabled must be a boolean" test-name)
                    {:test-suite test-name :enabled enabled})))

  (when-not (and (integer? count) (pos? count))
    (throw (ex-info (format "Test suite '%s': count must be a positive integer" test-name)
                    {:test-suite test-name :count count})))

  (when-not (string? prefix)
    (throw (ex-info (format "Test suite '%s': prefix must be a string" test-name)
                    {:test-suite test-name :prefix prefix})))

  ;; Validate attributes specific configuration
  (when (= test-name :attributes)
    (when-not (contains? config :vault_count)
      (throw (ex-info "Attributes test suite requires vault_count parameter"
                      {:test-suite test-name})))

    (when-not (and (integer? vault_count) (pos? vault_count))
      (throw (ex-info "Attributes test suite: vault_count must be a positive integer"
                      {:test-suite test-name :vault_count vault_count})))

    (when (< count vault_count)
      (throw (ex-info "Attributes test suite: count must be greater than or equal to vault_count"
                      {:test-suite test-name :count count :vault_count vault_count})))))

(defn validate-concurrency [concurrency]
  (when-not (and (sequential? concurrency)
                 (seq concurrency)
                 (every? pos-int? concurrency))
    (throw (ex-info "Config must specify concurrency as a non-empty sequence of positive integers"
                    {:concurrency concurrency}))))

(defn validate-log-level [log-level]
  (when-not (contains? valid-log-levels (keyword log-level))
    (throw (ex-info (format "Invalid log level: %s. Valid levels are: %s"
                            log-level
                            (str/join ", " (map name valid-log-levels)))
                    {:log-level log-level
                     :valid-levels valid-log-levels}))))
(defn generate-report-filename []
  (let [timestamp (java.time.LocalDateTime/now)
        formatter (java.time.format.DateTimeFormatter/ofPattern "HH-mm-MM-dd-yyyy")]
    (str "manetu-perf-results-" (.format timestamp formatter))))
(defn ensure-reports-dir []
  (let [reports-dir (io/file "reports")]
    (when-not (.exists reports-dir)
      (.mkdirs reports-dir))
    reports-dir))
(defn get-default-report-paths []
  (let [base-filename (generate-report-filename)
        reports-dir (ensure-reports-dir)]
    {:csv (str (io/file reports-dir (str base-filename ".csv")))
     :json (str (io/file reports-dir (str base-filename ".json")))}))
(defn validate-reports [{:keys [csv json] :as reports}]
  (if (nil? reports)
    (get-default-report-paths)
    (let [default-paths (get-default-report-paths)]
      {:csv (if (string? csv) csv (:csv default-paths))
       :json (if (string? json) json (:json default-paths))})))
(defn validate-config [{:keys [tests concurrency log_level reports] :as config}]
  ;; Check top level structure
  (when-not (and (map? config)
                 (map? tests)
                 (sequential? concurrency)
                 log_level)
    (throw (ex-info "Config must contain 'tests' map, 'concurrency' sequence, and 'log_level'"
                    {:config config})))

  ;; Validate test suites
  (doseq [[test-name test-config] tests]
    (let [test-keyword (keyword test-name)]
      (when-not (contains? valid-test-suites test-keyword)
        (throw (ex-info (format "Invalid test suite: %s. Valid suites are: %s"
                                test-name
                                (str/join ", " (map name valid-test-suites)))
                        {:test-suite test-name
                         :valid-suites valid-test-suites})))
      (validate-test-config test-keyword test-config)))

  ;; Validate concurrency
  (validate-concurrency concurrency)

  ;; Validate log level
  (validate-log-level log_level)

  ;; Update config with validated reports
  (assoc config :reports (validate-reports reports)))

(defn load-config
  "Load and validate the test configuration from a YAML file"
  [path]
  (-> path
      yaml/from-file
      validate-config))