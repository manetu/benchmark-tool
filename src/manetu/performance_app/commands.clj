;; Copyright © Manetu, Inc.  All rights reserved

(ns manetu.performance-app.commands
  (:require [manetu.performance-app.driver.api :as driver.api]
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

(def command-map
  {:create-vaults     init-create-vault
   :delete-vaults     init-delete-vault
   :load-attributes   init-load-attributes
   :onboard           init-onboard
   :delete-attributes init-delete-attributes
   :query-attributes  init-query-attributes
   :e2e               init-e2e
   :standalone-attributes   init-standalone-attributes})

(defn get-handler
  [mode driver]
  (if-let [init-fn (get command-map mode)]
    (init-fn driver)
    (throw (ex-info "bad mode" mode))))
