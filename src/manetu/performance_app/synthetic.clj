;; Copyright © Manetu, Inc.  All rights reserved
(ns manetu.performance-app.synthetic
  (:require [clojure.core.async :as async]))
(defn create-synthetic-record [n namespace]
  (let [label (if namespace
                (format "%s-vault%d" namespace n)
                (format "vault%d" n))]
    {:label label
     :data {:Email label}}))

(defn load-synthetic-records [count namespace]
  {:n count
   :ch (async/to-chan!
        (map #(create-synthetic-record % namespace)
             (range 1 (inc count))))})
