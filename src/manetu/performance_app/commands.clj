;; Copyright © Manetu, Inc.  All rights reserved

(ns manetu.performance-app.commands
  (:require [manetu.performance-app.driver.api :as driver.api]
            [crypto.random]
            [promesa.core :as p]))

(defn init-create-vault [driver]
  (partial driver.api/create-vault driver))

(defn init-delete-vault [driver]
  (partial driver.api/delete-vault driver))

(defn init-load-attributes [driver]
  (partial driver.api/load-attributes driver))

(defn init-onboard [driver]
  (fn [record]
    (-> (driver.api/create-vault driver record)
        (p/then (fn [_]
                  (driver.api/load-attributes driver record))))))

(defn init-delete-attributes [driver]
  (partial driver.api/delete-attributes driver))

(defn init-query-attributes [driver]
  (partial driver.api/query-attributes driver))

(defn init-standalone-attributes [driver]
  (fn [record]
    (driver.api/standalone-attribute-update driver record)))
(defn init-e2e [driver]
  (fn [record]
    (-> (driver.api/create-vault driver record)
        (p/then (fn [_] (driver.api/load-attributes driver record)))
        (p/then (fn [_] (driver.api/delete-attributes driver record)))
        (p/then (fn [_] (driver.api/delete-vault driver record))))))

(defn init-tokenize-values [driver]
  (fn [record]
    (let [size (+ (:value_min record)
                  (rand-int (inc (- (:value_max record) (:value_min record)))))
          values (repeatedly (:tokens_per_job record)
                             #(crypto.random/bytes size))]
      (driver.api/tokenize-values driver record values))))

(defn init-tokenize-translate-e2e [driver]
  (fn [record]
    (let [size (+ (:value_min record)
                  (rand-int (inc (- (:value_max record) (:value_min record)))))
          values (repeatedly (:tokens_per_job record)
                             #(crypto.random/bytes size))]
      (-> (driver.api/tokenize-values driver record values)
          (p/then (fn [response]
                    (let [token-values (map #(:value %) (:tokenize response))]
                      (driver.api/translate-tokens driver
                                                   {:mrn (:mrn record)
                                                    :context-embedded? (not= (keyword (:token_type record)) :EPHEMERAL)}
                                                   token-values))))))))
(def command-map
  {:create-vaults     init-create-vault
   :delete-vaults     init-delete-vault
   :load-attributes   init-load-attributes
   :onboard           init-onboard
   :delete-attributes init-delete-attributes
   :query-attributes  init-query-attributes
   :e2e               init-e2e
   :standalone-attributes   init-standalone-attributes
   :tokenize-values   init-tokenize-values
   :tokenize-translate-e2e init-tokenize-translate-e2e})

(defn get-handler
  [mode driver]
  (if-let [init-fn (get command-map mode)]
    (init-fn driver)
    (throw (ex-info "bad mode" mode))))
