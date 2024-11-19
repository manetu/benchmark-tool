;; Copyright © Manetu, Inc.  All rights reserved

(ns manetu.performance-app.loader
  (:require [buddy.core.hash :as hash]
            [buddy.core.codecs.base64 :as b64]
            [medley.core :as m]
            [taoensso.timbre :as log]
            [cheshire.core :as json]
            [clojure.data.csv :as csv]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [clojure.core.async :refer [go >!!] :as async]
            [slingshot.slingshot :refer [throw+ try+]]))

(defn- compute-label
  [email]
  (-> (hash/sha256 email)                                   ;; This should normally be an HMAC
      (b64/encode true)
      (String. "UTF-8")))

(defn validate-headers [headers]
  (doseq [header headers]
    (cond
      (some? (re-find #"\s" header))
      (throw+ {:type ::bad-header :reason (str "Header \"" header "\" contains whitespace")})

      :default :ok)))

(defn load-json
  [rdr]
  (json/parse-stream rdr))

(defn csv->maps [data]
  (map zipmap
       (repeat (first data)) ;; First row is the header
       (rest data)))

(defn load-csv
  [rdr]
  (csv->maps (csv/read-csv rdr)))

(defn parse
  [path rdr]
  (let [ext (fs/extension path)]
    (case ext
      ".json" (load-json rdr)
      ".csv" (load-csv rdr))))

(defn record-seq
  [path f]
  (with-open [rdr (io/reader path)]
    (f (parse path rdr))))

(defn load-records
  [path]
  (let [n (record-seq path count)
        ch (async/chan 1024)]
    (async/thread
      (record-seq path
                  (fn [records]
                    (try+
                     (doseq [{:strs [Email] :as data} records]
                       (validate-headers (keys data))
                       (>!! ch {:label (compute-label Email) :data (m/map-keys keyword data)}))
                     (catch [:type ::bad-header] {:keys [reason]}
                       (log/error reason))
                     (finally
                       (async/close! ch))))))
    {:n n :ch ch}))
