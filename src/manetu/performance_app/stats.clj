;; Copyright © Manetu, Inc.  All rights reserved

(ns manetu.performance-app.stats
  (:require [kixi.stats.core :refer [count mean standard-deviation histogram post-complete]]
            [kixi.stats.distribution :refer [minimum maximum quantile]]
            [redux.core :refer [fuse]]))

(def summary (fuse {:count count
                    :mean mean
                    :stddev standard-deviation
                    :dist (post-complete histogram
                                         (fn [hist]
                                           {:min (minimum hist)
                                            :p50 (quantile hist 0.50)
                                            :p90 (quantile hist 0.90)
                                            :p95 (quantile hist 0.95)
                                            :p99 (quantile hist 0.99)
                                            :max (maximum hist)}))}))