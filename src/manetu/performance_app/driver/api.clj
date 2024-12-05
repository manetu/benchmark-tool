;; Copyright © Manetu, Inc.  All rights reserved

(ns manetu.performance-app.driver.api)

(defprotocol Driver
  (create-vault [this record])
  (delete-vault [this record])
  (load-attributes [this record])
  (delete-attributes [this record])
  (query-attributes [this record])
  (standalone-attribute-update [this record])
  (tokenize-values [this record values])
  (translate-tokens [this record tokens]))
