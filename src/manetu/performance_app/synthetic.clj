;; Copyright © Manetu, Inc.  All rights reserved
(ns manetu.performance-app.synthetic
  (:require [clojure.core.async :as async :refer [>!! go]]))
(defn create-synthetic-record [n prefix]
  (let [label (if prefix
                (format "%s-vault%d" prefix n)
                (format "vault%d" n))]
    {:label label
     :data {:Email label}}))

(defn load-synthetic-records [count prefix]
  {:n count
   :ch (async/to-chan!
        (map #(create-synthetic-record % prefix)
             (range 1 (inc count))))})

(defn create-attribute-operation-records
  "Creates a channel of attribute operations distributed across vaults"
  [vault-count total-ops prefix]
  (let [base-records (->> (range 1 (inc vault-count))
                          (mapv #(create-synthetic-record % prefix)))
        ch (async/chan)]
    (go
      (loop [iteration 1]
        (when (<= iteration total-ops)
          (let [vault-idx (mod (dec iteration) vault-count)
                record (nth base-records vault-idx)]
            (>!! ch {:data {:Email (get-in record [:data :Email])}
                     :label (:label record)
                     :iteration iteration})
            (recur (inc iteration)))))
      (async/close! ch))
    {:n total-ops :ch ch}))
