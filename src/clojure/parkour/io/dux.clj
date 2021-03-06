(ns parkour.io.dux
  (:require [clojure.edn :as edn]
            [clojure.core.reducers :as r]
            [parkour (conf :as conf) (wrapper :as w) (cstep :as cstep)
                     (mapreduce :as mr)]
            [parkour.mapreduce (sink :as snk)]
            [parkour.io (dseq :as dseq) (dsink :as dsink) (mux :as mux)]
            [parkour.util :refer [returning]])
  (:import [clojure.lang IFn]
           [org.apache.hadoop.conf Configurable]
           [org.apache.hadoop.mapreduce Job TaskInputOutputContext]
           [org.apache.hadoop.mapreduce OutputFormat RecordWriter Counter]
           [parkour.hadoop Dux$OutputFormat]))

(def ^:private ^:const confs-key
  "parkour.dux.confs")

(defn ^:private dux-output?
  "True iff `job` is configured for demultiplex output."
  [job] (->> (conf/get-class job "mapreduce.outputformat.class" nil)
             (identical? Dux$OutputFormat)))

(defn ^:private dux-empty
  "Clone of `job` with empty demultiplex sub-configurations map."
  [job] (-> job mr/job (conf/assoc! confs-key "{}")))

(defn get-subconfs
  "Get map of `job` demultiplex sub-configuration diffs."
  [job]
  (or (if (dux-output? job)
        (some->> (conf/get job confs-key) (edn/read-string)))
      {}))

(defn add-subconf
  "Add demultiplex output `subconf` to `job` as `name`."
  [^Job job name subconf]
  (let [diff (-> job (conf/diff subconf) (dissoc confs-key))
        diffs (-> (get-subconfs job) (assoc name diff))]
    (doto job
      (.setOutputKeyClass Object)
      (.setOutputValueClass Object)
      (.setOutputFormatClass Dux$OutputFormat)
      (conf/assoc! confs-key (pr-str diffs)))))

(defn add-substep
  "Add configuration changes produced by `step` as a demultiplex
sub-configuration of `job`."
  [^Job job name step]
  (add-subconf job name (-> job dux-empty (cstep/apply! step))))

(defn dsink
  "Demultiplexing distributed sink, for other distributed sinks `dsinks`,
a map of names to dsinks.  The distributed sequence of the resulting sink is the
multiplex distributed sequence of all component sinks' sequences."
  [dsinks]
  (dsink/dsink
   (apply mux/dseq (map dsink/dsink-dseq (vals dsinks)))
   (fn [^Job job]
     (reduce (partial apply add-substep) job dsinks))))

(defmethod dsink/output-paths* Dux$OutputFormat
  [^Job job]
  (->> job get-subconfs vals
       (r/mapcat #(dsink/output-paths (conf/merge! (mr/job job) %)))
       (into [])))

(defn ^:private dux-state
  "Extract demultiplexing output state from `context`."
  [^TaskInputOutputContext context]
  @(.getOutputCommitter context))

(defn ^:private set-output-name
  "Re-implementation of `FileOutputFormat/setOutputName`."
  [job base] (conf/assoc! job "mapreduce.output.basename" base))

(defn ^:private get-counter
  "Get dux counter for output `oname`."
  {:tag `Counter}
  [^TaskInputOutputContext context oname]
  (.getCounter context "Demultiplexing Output" (name oname)))

(defn ^:private new-rw
  "Return new demultiplexing output sink for output `oname` and file output
basename `base`."
  [context oname base]
  (let [[jobs ofs rws] (dux-state context)
        of (get ofs oname), ^Job job (get jobs oname)
        conf (-> job conf/clone (cond-> base (set-output-name base)))
        tac (mr/tac conf context), c (get-counter context oname)
        ckey (.getOutputKeyClass job), cval (.getOutputValueClass job)
        rw (.getRecordWriter ^OutputFormat of tac)]
    (snk/wrap-sink
     (reify
       Configurable (getConf [_] conf)
       w/Wrapper (unwrap [_] rw)
       snk/TupleSink
       (-key-class [_] ckey)
       (-val-class [_] cval)
       (-close [_] (.close rw context))
       (-emit-keyval [_ key val]
         (.write rw key val)
         (.increment c 1))))))

(defn get-sink
  "Get sink for named output `oname` and optional (file output format only) file
basename `base`."
  ([context oname] (get-sink context oname nil))
  ([context oname base]
     (let [[jobs ofs rws] (dux-state context), rwkey [oname base]]
       @(or (get-in @rws rwkey)
            (let [new-rw (partial new-rw context oname base)
                  add-rw (fn [rws]
                           (if rws
                             (if-let [rw (get-in rws rwkey)]
                               rw
                               (assoc-in rws rwkey (delay (new-rw))))))]
              (-> rws (swap! add-rw) (get-in rwkey)))))))

(defn write
  "Write `key` and `val` to named output `oname` and optional (file output
format only) file basename `base`."
  ([context oname key val] (write context oname nil key val))
  ([context oname base key val]
     (-> context (get-sink oname base) (snk/emit-keyval key val))))

(defn named-keyvals
  "Sink as key-val pairs to named output, with name provided as `oname` or as
first element of three-element tuples."
  ([oname]
     (fn [context coll]
       (reduce (fn [_ [key val]]
                 (write context oname key val))
               nil coll)))
  ([context coll]
     (reduce (fn [_ [oname key val]]
               (write context oname key val))
             nil coll)))

(defn named-keys
  "Sink as keys to named output, with name provided as `oname` or as first
element of two-element tuples."
  ([oname]
     (fn [context coll]
       (reduce (fn [_ key]
                 (write context oname key nil))
               nil coll)))
  ([context coll]
     (reduce (fn [_ [oname key]]
               (write context oname key nil))
             nil coll)))

(defn named-vals
  "Sink as values to named output, with name provided as `oname` or as first
element of two-element tuples."
  ([oname]
     (fn [context coll]
       (reduce (fn [_ val]
                 (write context oname nil val))
               nil coll)))
  ([context coll]
     (reduce (fn [_ [oname val]]
               (write context oname nil val))
             nil coll)))

(defn prefix-keyvals
  "Sink as key-val pairs to named output `oname`, with file prefix as first
element of three-element tuples."
  [oname]
  (fn [context coll]
    (reduce (fn [_ [base key val]]
              (write context oname base key val))
            nil coll)))

(defn prefix-keys
  "Sink as keys to named output `oname`, with file prefix as first element of
two-element tuples."
  [oname]
  (fn [context coll]
    (reduce (fn [_ [base key]]
              (write context oname base key nil))
            nil coll)))

(defn prefix-vals
  "Sink as values to named output `oname`, with file prefix as first element of
two-element tuples."
  [oname]
  (fn [context coll]
    (reduce (fn [_ [base val]]
              (write context oname base nil val))
            nil coll)))
