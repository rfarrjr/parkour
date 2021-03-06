(ns parkour.io.dseq-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.core.reducers :as r]
            [parkour (fs :as fs) (mapreduce :as mr) (wrapper :as w)]
            [parkour.io (dseq :as dseq) (text :as text)])
  (:import [org.apache.hadoop.io Text]
           [org.apache.hadoop.mapred JobConf]))

(defn mr1-add-path
  [c p]
  (org.apache.hadoop.mapred.FileInputFormat/addInputPath c p))

(defn mr2-add-path
  [c p]
  (org.apache.hadoop.mapreduce.lib.input.FileInputFormat/addInputPath c p))

(def TextInputFormat1
  org.apache.hadoop.mapred.TextInputFormat)

(def TextInputFormat2
  org.apache.hadoop.mapreduce.lib.input.TextInputFormat)

(def input-path
  (-> "word-count-input.txt" io/resource fs/path))

(defn run-test-reducible
  [conf]
  (is (= [[0 "apple"]] (->> (dseq/dseq conf)
                            (r/map w/unwrap-all)
                            (r/take 1)
                            (into []))))
  (is (= ["apple"] (->> (dseq/dseq conf)
                        (r/map (comp str second))
                        (r/take 1)
                        (into [])))))

(deftest test-mapred
  (run-test-reducible
   (doto (JobConf.)
     (.setInputFormat TextInputFormat1)
     (mr1-add-path input-path))))

(deftest test-mapreduce
  (run-test-reducible
   (doto (mr/job)
     (.setInputFormatClass TextInputFormat2)
     (mr2-add-path input-path))))

(defn multi-split-dseq
  []
  (let [root (doto (fs/path "tmp/input") fs/path-delete)]
    (spit (fs/path root "part-0.txt") "apple\n")
    (spit (fs/path root "part-1.txt") "banana\n")
    (spit (fs/path root "part-2.txt") "carrot\n")
    (text/dseq root)))

(deftest test-multi-split-reduce-auto
  (is (= ["apple" "banana" "carrot"]
         (->> (multi-split-dseq)
              w/unwrap (r/map second)
              (into []) sort vec))))


(deftest test-multi-split-reduce-unwrap
  (with-open [source (dseq/source-for (multi-split-dseq))]
    (is (= ["apple" "banana" "carrot"]
           (->> source (r/map second) (into []) sort vec)))))

(deftest test-multi-split-reduce-raw
  (with-open [source (dseq/source-for (multi-split-dseq) :raw? true)]
    (is (= [(Text. "apple") (Text. "banana") (Text. "carrot")]
           (->> source (r/map (comp w/clone second)) (into []) sort vec)))))

(deftest test-multi-split-seq
  (with-open [source (dseq/source-for (multi-split-dseq))]
    (is (= ["apple" "banana" "carrot"]
           (->> source (map second) sort vec)))))
