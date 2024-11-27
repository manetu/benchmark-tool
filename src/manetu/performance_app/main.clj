;; Copyright © Manetu, Inc.  All rights reserved

(ns manetu.performance-app.main
  (:require [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [manetu.performance-app.commands :as commands]
            [manetu.performance-app.core :as core]
            [manetu.performance-app.driver.core :as driver.core]
            [manetu.performance-app.config :as config]
            [manetu.performance-app.reports :as reports]
            [taoensso.timbre :as log])
  (:gen-class))

(defn set-logging
  [level]
  (log/set-config!
   {:level level
    :ns-whitelist  ["manetu.*"]
    :appenders
    {:custom
     {:enabled? true
      :async false
      :fn (fn [{:keys [timestamp_ msg_ level] :as data}]
            (binding [*out* *err*]
              (println (force timestamp_) (string/upper-case (name level)) (force msg_))))}}}))

(def log-levels #{:trace :debug :info :error})
(defn print-loglevels []
  (str "[" (string/join ", " (map name log-levels)) "]"))
(def loglevel-description
  (str "Select the logging verbosity level from: " (print-loglevels)))

(def drivers (into #{} (keys driver.core/driver-map)))
(defn print-drivers []
  (str "[" (string/join ", " (map name drivers)) "]"))
(def driver-description
  (str "Select the driver from: " (print-drivers)))

(def modes (conj (into #{} (keys commands/command-map)) :test-suite))
(defn print-modes []
  (str "[" (string/join ", " (map name modes)) "]"))
(def mode-description
  (str "Select the mode from: " (print-modes)))

(def options
  [["-h" "--help"]
   ["-v" "--version" "Print the version and exit"]
   ["-u" "--url URL" "The connection URL"]
   ["-i" "--insecure" "Disable TLS checks (dev only)"
    :default false]
   [nil "--[no-]progress" "Enable/disable progress output (default: enabled)"
    :default true]
   ["-t" "--token TOKEN" "A personal access token"]
   ["-l" "--log-level LEVEL" loglevel-description
    :default :info
    :parse-fn keyword
    :validate [log-levels (str "Must be one of " (print-loglevels))]]
   [nil "--fatal-errors" "Any sub-operation failure is considered to be an application level failure"
    :default false]
   [nil "--verbose-errors" "Any sub-operation failure is logged as ERROR instead of TRACE"
    :default false]
   [nil "--type TYPE" "The type of data source this CLI represents"
    :default "performance-app"]
   [nil "--id ID" "The id of the data-source this CLI represents"
    :default "535CC6FC-EAF7-4CF3-BA97-24B2406674A7"]
   [nil "--class CLASS" "The schemaClass of the data-source this CLI represents"
    :default "global"]
   [nil "--config CONFIG" "Path to test configuration YAML file"]
   ["-c" "--concurrency NUM" "The number of parallel requests to issue"
    :default 16
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a positive integer"]]
   ["-m" "--mode MODE" mode-description
    :default :load-attributes
    :parse-fn keyword
    :validate [modes (str "Must be one of " (print-modes))]]
   ["-d" "--driver DRIVER" driver-description
    :default :graphql
    :parse-fn keyword
    :validate [drivers (str "Must be one of " (print-drivers))]]
   [nil "--csv-file FILE" "Write the results to a CSV file"
    :default "results.csv"]
   [nil "--json-file FILE" "Write the results to a JSON file"
    :default "results.json"]
   [nil "--count NUM" "Number of test iterations"
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a positive integer"]]
   [nil "--namespace NS" "Namespace prefix for synthetic vault labels"]
   [nil "--vault-count NUM" "Number of vaults for standalone testing"
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a positive integer"]]])

(defn exit [status msg & args]
  (do
    (apply println msg args)
    status))

(defn version [] (str "manetu-performance-app version: v" (System/getProperty "performance-app.version")))

(defn prep-usage [msg] (->> msg flatten (string/join \newline)))

(defn usage [options-summary]
  (prep-usage [(version)
               ""
               "Usage: manetu-performance-app [options] <file.json>"
               ""
               "Options:"
               options-summary]))

(defn -app
  [& args]
  (let [{{:keys [help log-level url token mode output-file config] :as options} :options
         :keys [arguments errors summary]} (parse-opts args options)]
    (cond
      help
      (exit 0 (usage summary))

      (not= errors nil)
      (exit -1 "Error: " (string/join errors))

      (:version options)
      (exit 0 (version))

      (string/blank? url)
      (exit -1 "--url required")

      (string/blank? token)
      (exit -1 "--token required")

      (and (zero? (count arguments)) (not count))
      (exit -1 "Either input file <file.json> or --count must be provided")

      config
      (do
        (set-logging log-level)
        (try
          (let [config (-> config
                           config/load-config
                           config/validate-config)
                results (core/exec-configured-tests config options (first arguments))]
            (-> results
                (reports/write-json-report (:json-file options))
                (reports/write-csv-report (:csv-file options)))
            0)
          (catch Exception e
            (log/error "Failed to execute configured tests:" (.getMessage e))
            -1)))

      :else
      (do
        (set-logging log-level)
        (let [stats @(core/exec options (first arguments))]
          (-> stats
              (reports/write-json-report (:json-file options)))
          (if (:error stats)
            -1
            0))))))

(defn -main
  [& args]
  (println "Executing performance-app with args:" args)
  (System/exit (apply -app args)))
