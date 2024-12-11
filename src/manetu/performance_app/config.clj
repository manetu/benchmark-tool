;; Copyright © Manetu, Inc.  All rights reserved
(ns manetu.performance-app.config
  (:require [yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def valid-test-suites
  #{:vaults :e2e :attributes :tokenizer :tokenizer_translate_e2e})
(def valid-log-levels
  #{:trace :debug :info :warn :error})

(def valid-token-types
  #{:EPHEMERAL :PERSISTENT})

(defn validate-count-array [test-name count-array]
  (when-not (or (integer? count-array) (and (sequential? count-array) (every? integer? count-array)))
    (throw (ex-info (format "Test suite '%s': count must be an integer or array of integers" test-name)
                    {:test-suite test-name :count count-array})))

  (when (sequential? count-array)
    (when-not (every? pos? count-array)
      (throw (ex-info (format "Test suite '%s': all count values must be positive integers" test-name)
                      {:test-suite test-name :count count-array})))))

(defn validate-token-type-array [test-name token-types]
  (when-not (or (string? token-types) (and (sequential? token-types) (every? string? token-types)))
    (throw (ex-info (format "Test suite '%s': token_type must be a string or array of strings" test-name)
                    {:test-suite test-name :token_type token-types})))

  (let [types (if (sequential? token-types) token-types [token-types])]
    (doseq [type types]
      (when-not (contains? valid-token-types (keyword type))
        (throw (ex-info (format "Test suite '%s': token_type must be one of %s"
                                test-name
                                (str/join ", " (map name valid-token-types)))
                        {:test-suite test-name :token_type type}))))))

(defn validate-tokens-per-job-array [test-name tokens-per-job]
  (when-not (or (integer? tokens-per-job) (and (sequential? tokens-per-job) (every? integer? tokens-per-job)))
    (throw (ex-info (format "Test suite '%s': tokens_per_job must be an integer or array of integers" test-name)
                    {:test-suite test-name :tokens_per_job tokens-per-job})))

  (when (sequential? tokens-per-job)
    (when-not (every? pos? tokens-per-job)
      (throw (ex-info (format "Test suite '%s': all tokens_per_job values must be positive integers" test-name)
                      {:test-suite test-name :tokens_per_job tokens-per-job})))))

(defn validate-test-config [test-name {:keys [enabled count prefix vault_count clean_up realm token_type
                                              value_min value_max tokens_per_job]
                                       :or {realm "data-loader"}
                                       :as config}]
  (when-not (map? config)
    (throw (ex-info (format "Test suite '%s' configuration must be a map" test-name)
                    {:test-suite test-name :config config})))

  ;; Check required fields exist
  (let [required-msg (str "Test suite '%s' missing required fields. "
                          "Must have enabled, count, prefix, and clean_up")]
    (when-not (every? #(contains? config %) [:enabled :count :prefix :clean_up])
      (throw (ex-info (format required-msg test-name)
                      {:test-suite test-name
                       :config config
                       :required-fields [:enabled :count :prefix :clean_up]}))))

  ;; Validate value types and ranges
  (when-not (boolean? enabled)
    (throw (ex-info (format "Test suite '%s': enabled must be a boolean" test-name)
                    {:test-suite test-name :enabled enabled})))

  (when-not (boolean? clean_up)
    (throw (ex-info (format "Test suite '%s': clean_up must be a boolean" test-name)
                    {:test-suite test-name :clean_up clean_up})))

  (validate-count-array test-name count)

  (when-not (string? prefix)
    (throw (ex-info (format "Test suite '%s': prefix must be a string" test-name)
                    {:test-suite test-name :prefix prefix})))

  ;; Validate tokenizer specific configuration
  (when (#{:tokenizer :tokenizer_translate_e2e} test-name)
    (when-not (contains? config :vault_count)
      (throw (ex-info "Tokenizer test suite requires vault_count parameter"
                      {:test-suite test-name})))

    (when-not (and (integer? vault_count) (pos? vault_count))
      (throw (ex-info "Tokenizer test suite: vault_count must be a positive integer"
                      {:test-suite test-name :vault_count vault_count})))

    (let [max-count (if (sequential? count) (apply max count) count)]
      (when (< max-count vault_count)
        (throw (ex-info "Tokenizer test suite: count must be greater than or equal to vault_count"
                        {:test-suite test-name :count count :vault_count vault_count}))))

    (when-not (or (nil? realm) (string? realm))
      (throw (ex-info "Tokenizer test suite: realm must be a string if provided"
                      {:test-suite test-name :realm realm})))

    (when-not (and (integer? value_min) (pos? value_min))
      (throw (ex-info "Tokenizer test suite: value_min must be a positive integer"
                      {:test-suite test-name :value_min value_min})))

    (when-not (and (integer? value_max) (>= value_max value_min))
      (throw (ex-info "Tokenizer test suite: value_max must be >= value_min"
                      {:test-suite test-name :value_max value_max :value_min value_min})))

    (validate-tokens-per-job-array test-name tokens_per_job)
    (validate-token-type-array test-name token_type))

  ;; Validate attributes specific configuration
  (when (= test-name :attributes)
    (when-not (contains? config :vault_count)
      (throw (ex-info "Attributes test suite requires vault_count parameter"
                      {:test-suite test-name})))

    (when-not (and (integer? vault_count) (pos? vault_count))
      (throw (ex-info "Attributes test suite: vault_count must be a positive integer"
                      {:test-suite test-name :vault_count vault_count})))

    (let [max-count (if (sequential? count) (apply max count) count)]
      (when (< max-count vault_count)
        (throw (ex-info "Attributes test suite: count must be greater than or equal to vault_count"
                        {:test-suite test-name :count count :vault_count vault_count}))))))

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