;; Copyright © Manetu, Inc.  All rights reserved

(ns manetu.performance-app.driver.drivers.graphql.core
  (:require [promesa.core :as p]
            [taoensso.timbre :as log]
            [cheshire.core :as json]
            [org.httpkit.client :as http]
            [graphql-query.core :refer [graphql-query]]
            [manetu.performance-app.sparql :as sparql]
            [manetu.performance-app.driver.api :as api]))

(defn http-post
  [{:keys [url insecure token] :as ctx} query]
  (log/trace "http/put:" query)
  (p/create
   (fn [resolve reject]
     (http/post (str url "/graphql")
                {:insecure? insecure
                 :basic-auth [nil token]
                 :headers {"content-type" "application/json"
                           "accepts" "application/json"}
                 :body (json/generate-string query)
                 :async? true}
                (fn [{:keys [status body] :as r}]
                  (log/trace "r:" r)
                  (cond
                    (not= status 200)
                    (reject (ex-info "bad response" r))

                    :default
                    (try
                      (resolve (json/parse-string body true))
                      (catch Throwable t
                        (reject (ex-info "body is not json" {:response r}))))))))))

(defn gql-query
  ([ctx query]
   (gql-query ctx query {}))
  ([ctx query args]
   (let [query (graphql-query query)]
     (log/trace "gql query:" query)
     (-> (http-post ctx {:query query
                         :variables args})
         (p/then (fn [{:keys [errors data] :as r}]
                   (log/trace "r:" r)
                   (if (some? errors)
                     (throw (ex-info (-> errors first :message) {:errors errors}))
                     data)))))))

(defn -create-vault [ctx {:keys [label] :as record}]
  (gql-query ctx {:operation {:operation/type :mutation
                              :operation/name "create_vault"}
                  :queries [[:create_vault {:label label}
                             [:mrn]]]}))

(defn get-vault [ctx label]
  (-> (gql-query ctx {:operation {:operation/type :query
                                  :operation/name "vaults"}
                      :queries [[:vaults {:labels [label]}
                                 [:version]]]})
      (p/then (fn [{:keys [vaults]}]
                (-> vaults first :version)))))

(defn -delete-vault [ctx {:keys [label] :as record}]
  (-> (get-vault ctx label)
      (p/then (fn [version]
                (gql-query ctx {:operation {:operation/type :mutation
                                            :operation/name "delete_vault"}
                                :queries [[:delete_vault {:label label :version version}]]})))))

(defn -load-attributes [ctx {:keys [label data] :as record}]
  (gql-query ctx {:operation {:operation/type :mutation
                              :operation/name "sparql_update"}
                  :variables [{:variable/name :$expr
                               :variable/type :String}]
                  :queries [[:sparql_update {:label label :sparql_expr :$expr}]]}
             {:expr (sparql/convert ctx data)}))

(defn -delete-attributes [ctx {:keys [label] :as record}]
  (gql-query ctx {:operation {:operation/type :mutation
                              :operation/name "sparql_update"}
                  :variables [{:variable/name :$expr
                               :variable/type :String}]
                  :queries [[:sparql_update {:label label :sparql_expr "DELETE WHERE { ?s ?p ?o . }"}]]}))
(defn -standalone-attribute-update [ctx {:keys [label] :as record}]
  (gql-query ctx {:operation {:operation/type :mutation
                              :operation/name "sparql_update"}
                  :variables [{:variable/name :$expr
                               :variable/type :String}]
                  :queries [[:sparql_update {:label label :sparql_expr :$expr}]]}
             {:expr (sparql/convert-standalone-attribute ctx record)}))

(defn -query-attributes [ctx record]
  (throw (ex-info "not implemented" {})))

(defrecord GraphqlDriver [ctx]
  api/Driver
  (create-vault [this record]
    (-create-vault ctx record))
  (delete-vault [this record]
    (-delete-vault ctx record))
  (load-attributes [this record]
    (-load-attributes ctx record))
  (delete-attributes [this record]
    (-delete-attributes ctx record))
  (query-attributes [this record]
    (-query-attributes ctx record))
  (standalone-attribute-update [this record]
    (-standalone-attribute-update ctx record)))

(defn create
  [options]
  (GraphqlDriver. options))
