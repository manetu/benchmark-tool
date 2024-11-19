;; Copyright © Manetu, Inc.  All rights reserved
(ns manetu.performance-app.config
  (:require [yaml.core :as yaml]))

(defn load-config [path]
  (yaml/from-file path))

(defn validate-config [{:keys [tests concurrency] :as config}]
  (when (or (empty? tests) (empty? concurrency))
    (throw (ex-info "Config must specify tests and concurrency levels" {})))
  config)