;; Copyright © Manetu, Inc.  All rights reserved

(ns manetu.benchmark-tool.driver.core
  (:require
   [manetu.benchmark-tool.driver.drivers.graphql.core :as graphql]))

(def driver-map
  {:graphql graphql/create})

(defn create [{:keys [driver] :as options}]
  (if-let [create-fn (get driver-map driver)]
    (create-fn options)
    (throw (ex-info "unknown driver" {:type driver}))))
