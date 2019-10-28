(ns jepsen.etcd
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [independent :as independent]
                    [nemesis :as nemesis]
                    [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [jepsen.etcd [set :as set]
                         [support :as s]]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+]]
            [verschlimmbesserung.core :as v]))

(def dir "/opt/etcd")
(def binary "etcd")
(def logfile (str dir "/etcd.log"))
(def pidfile (str dir "/etcd.pid"))

(defn db
  "Etcd DB. Pulls version from test map's :version"
  []
  (reify db/DB
    (setup! [_ test node]
      (let [version (:version test)]
        (info node "installing etcd" version)
        (c/su
          (let [url (str "https://storage.googleapis.com/etcd/v" version
                         "/etcd-v" version "-linux-amd64.tar.gz")]
            (cu/install-archive! url dir))))

      (cu/start-daemon!
        {:logfile logfile
         :pidfile pidfile
         :chdir   dir}
        binary
        :--enable-v2
        :--log-outputs                  :stderr
        :--logger                       :zap
        :--name                         node
        :--listen-peer-urls             (s/peer-url   node)
        :--listen-client-urls           (s/client-url node)
        :--advertise-client-urls        (s/client-url node)
        :--initial-cluster-state        :new
        :--initial-advertise-peer-urls  (s/peer-url node)
        :--initial-cluster              (s/initial-cluster test))

      (Thread/sleep 5000))

    (teardown! [_ test node]
      (info node "tearing down etcd")
      (cu/stop-daemon! binary pidfile)
      (c/su (c/exec :rm :-rf dir)))

    db/LogFiles
    (log-files [_ test node]
      [logfile])))

(defn parse-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (Long/parseLong s)))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (v/connect (s/client-url node)
                                 {:timeout 5000})))

  (setup! [this test])

  (invoke! [_ test op]
    (let [[k v] (:value op)]
      (try+
        (case (:f op)
          :read (let [value (-> conn
                                (v/get k {:quorum? (:quorum test)})
                                parse-long)]
                  (assoc op :type :ok, :value (independent/tuple k value)))

          :write (do (v/reset! conn k v)
                     (assoc op :type :ok))

          :cas (let [[old new] v]
                 (assoc op :type (if (v/cas! conn k old new)
                                   :ok
                                   :fail))))

        (catch java.net.SocketTimeoutException e
          (assoc op
                 :type  (if (= :read (:f op)) :fail :info)
                 :error :timeout))

        (catch [:errorCode 100] e
          (assoc op :type :fail, :error :not-found)))))

  (teardown! [this test])

  (close! [_ test]
    ; If our connection were stateful, we'd close it here. Verschlimmmbesserung
    ; doesn't actually hold connections, so there's nothing to close.
    ))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn register-workload
  "Tests linearizable reads, writes, and compare-and-set operations on
  independent keys."
  [opts]
  {:client    (Client. nil)
   :checker   (independent/checker
                (checker/compose
                  {:linear   (checker/linearizable
                               {:model (model/cas-register)})
                   :timeline (timeline/html)}))
   :generator (independent/concurrent-generator
                10
                (range)
                (fn [k]
                  (->> (gen/mix [r w cas])
                       (gen/limit (:ops-per-key opts)))))})

(def workloads
  "A map of workload names to functions that construct workloads, given opts."
  {"set"      set/workload
   "register" register-workload})

(defn etcd-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency ...), constructs a test map. Special options:

      :quorum       Whether to use quorum reads
      :rate         Approximate number of requests per second, per thread
      :ops-per-key  Maximum number of operations allowed on any given key.
      :workload     Name of the workload to run."
  [opts]
  (let [quorum        (boolean (:quorum opts))
        workload-name (:workload opts)
        workload      ((workloads workload-name) opts)]
    (merge tests/noop-test
           opts
           {:name       (str "etcd " workload-name " q=" quorum)
            :quorum     quorum
            :os         debian/os
            :db         (db)
            :nemesis    (nemesis/partition-random-halves)
            :checker    (checker/compose
                          {:perf     (checker/perf)
                           :workload (:checker workload)})
            :client    (:client workload)
            :generator (gen/phases
                         (->> (:generator workload)
                              (gen/stagger (/ (:rate opts)))
                              (gen/nemesis
                                (gen/seq (cycle [(gen/sleep 5)
                                                 {:type :info, :f :start}
                                                 (gen/sleep 5)
                                                 {:type :info, :f :stop}])))
                              (gen/time-limit (:time-limit opts)))
                         (gen/log "Healing cluster")
                         (gen/nemesis (gen/once {:type :info, :f :stop}))
                         (gen/log "Waiting for recovery")
                         (gen/sleep 10)
                         (gen/clients (:final-generator workload)))})))

(def cli-opts
  "Additional command line options."
  [["-v" "--version STRING" "What version of etcd should we install?"
    :default "3.4.3"]
   ["-w" "--workload NAME" "What workload should we run?"
    :missing  (str "--workload " (cli/one-of workloads))
    :validate [workloads (cli/one-of workloads)]]
   ["-q" "--quorum" "Use quorum reads, instead of reading from any primary."]
   ["-r" "--rate HZ" "Approximate number of requests per second, per thread."
    :default  10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]
   [nil "--ops-per-key NUM" "Maximum number of operations on any given key."
    :default  100
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]])

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  etcd-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))