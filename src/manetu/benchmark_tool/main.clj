;; Copyright © Manetu, Inc.  All rights reserved

(ns manetu.benchmark-tool.main
  (:require [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [manetu.benchmark-tool.core :as core]
            [manetu.benchmark-tool.driver.core :as driver.core]
            [manetu.benchmark-tool.config :as config]
            [taoensso.timbre :as log])
  (:gen-class))

(defn set-logging
  [level]
  (log/set-config!
   {:level (keyword level)
    :ns-whitelist  ["manetu.*"]
    :appenders
    {:custom
     {:enabled? true
      :async false
      :fn (fn [{:keys [timestamp_ msg_ level] :as data}]
            (binding [*out* *err*]
              (println (force timestamp_) (string/upper-case (name level)) (force msg_))))}}}))

(def drivers (into #{} (keys driver.core/driver-map)))
(defn print-drivers []
  (str "[" (string/join ", " (map name drivers)) "]"))
(def driver-description
  (str "Select the driver from: " (print-drivers)))

(def options
  [["-h" "--help"]
   ["-v" "--version" "Print the version and exit"]
   ["-u" "--url URL" "The connection URL"]
   ["-i" "--insecure" "Disable TLS checks (dev only)"
    :default false]
   [nil "--[no-]progress" "Enable/disable progress output (default: enabled)"
    :default true]
   ["-t" "--token TOKEN" "A personal access token"]
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
   ["-d" "--driver DRIVER" driver-description
    :default :graphql
    :parse-fn keyword
    :validate [drivers (str "Must be one of " (print-drivers))]]])

(defn exit [status msg & args]
  (do
    (apply println msg args)
    status))

(defn version [] (str "manetu-benchmark-tool version: v" (System/getProperty "performance-app.version")))

(defn prep-usage [msg] (->> msg flatten (string/join \newline)))

(defn usage [options-summary]
  (prep-usage [(version)
               ""
               "Usage: manetu-benchmark-tool [options]"
               ""
               "Options:"
               options-summary]))

(defn prepare-options [options config]
  (assoc options
         :json-file (get-in config [:reports :json])
         :csv-file (get-in config [:reports :csv])))

(defn -app
  [& args]
  (let [{{:keys [help url token config] :as options} :options
         :keys [errors summary]} (parse-opts args options)]
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

      (string/blank? config)
      (exit -1 "--config required")

      :else
      (do
        (try
          (let [config-data (-> config
                                config/load-config
                                config/validate-config)
                _ (set-logging (:log_level config-data))
                results (core/exec-tests
                         (prepare-options options config-data)
                         config-data)]
            (if (:error results)
              -1
              0))
          (catch Exception e
            (log/error "Failed to execute tests:" (.getMessage e))
            -1))))))

(defn -main
  [& args]
  (println "Executing performance-app with args:" args)
  (System/exit (apply -app args)))
