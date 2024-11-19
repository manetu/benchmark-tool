;; Copyright © Manetu, Inc.  All rights reserved

(ns manetu.performance-app.time)

(defn now [] (System/nanoTime))

(defn duration
  "Computes a duration in milliseconds"
  [end start]
  (-> (- end start)
      (float)
      (/ (* 1000 1000))))
