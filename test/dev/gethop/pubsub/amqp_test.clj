;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns dev.gethop.pubsub.amqp-test
  {:clj-kondo/config '{:linters {:missing-docstring {:level :off}}}}
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :refer :all]
            [dev.gethop.pubsub.amqp :as amqp]
            [dev.gethop.pubsub.core :as core]
            [duct.logger :as logger]
            [integrant.core :as ig]
            [langohr.queue :as lq]
            [taoensso.nippy :as nippy])
  (:import [com.rabbitmq.client Recoverable ShutdownSignalException]))

(defn enable-instrumentation
  [f]
  (-> (stest/enumerate-namespace 'dev.gethop.pubsub.amqp) stest/instrument)
  (f))

(use-fixtures :once enable-instrumentation)

(defrecord AtomLogger [logs]
  logger/Logger
  (-log [_ level ns-str file line _ event data]
    (swap! logs conj [level ns-str file line event data])))

(def default-exchange-name
  "The name of the default AMQP exchange (empty string), which is always
  available and is configured as a direct exchange (routing all
  messages to queues named like the routing key"
  "")

(def sensor-id
  "825b4260-f5a6-45ed-9e27-c96358b0126f")

(def queue
  (str "sensor." sensor-id ".data"))

(def queue-attrs
  {:durable true :auto-delete false})

(def payload
  {:unit :volts
   :data [{:timestamp 1549901822.425 :value 12.34}
          {:timestamp 1549901822.725 :value 12.29}
          {:timestamp 1549901823.023 :value 12.32}
          {:timestamp 1549901823.212 :value 12.30}]})

(def base-config
  {:broker-config {:transport :tcp
                   :host (System/getenv "AMQP_TESTS_HOST")
                   :port (System/getenv "AMQP_TESTS_PORT")
                   :vhost (System/getenv "AMQP_TESTS_VHOST")
                   :username (System/getenv "AMQP_TESTS_USERNAME")
                   :password (System/getenv "AMQP_TESTS_PASSWORD")}
   :logger (->AtomLogger (atom []))})

(def ssl-config
  {:port (System/getenv "AMQP_TESTS_SSL_PORT")
   :tls-version (System/getenv "AMQP_TESTS_SSL_TLS_VERSION")
   :ca-crt-file (System/getenv "AMQP_TESTS_SSL_CA_CRT_FILE")
   :crt-file (System/getenv "AMQP_TESTS_SSL_CRT_FILE")
   :key-file (System/getenv "AMQP_TESTS_SSL_KEY_FILE")
   :key-password (System/getenv "AMQP_TESTS_SSL_KEY_PASSWORD")})

(defmulti prepare-listener
  (fn [_config listener]
    (:type listener)))

(defmethod prepare-listener :shutdown-listener
  [config _]
  (fn shutdown-listener [^ShutdownSignalException cause]
    (let [reason (.getReason cause)
          level (if reason :info :error)
          details (if reason
                    {:root-cause :amqp-method-executed
                     :amqp-method-name (-> reason .protocolMethodName)}
                    {:root-cause (-> cause .getCause .getMessage)})]
      (logger/log (:logger config) level ::shutdown-listener details))))

(defmethod prepare-listener :blocked-listener
  [config _]
  (fn blocked-listener [^String reason]
    (logger/log (:logger config) :info ::blocked-listener {:root-cause reason})))

(defmethod prepare-listener :unblocked-listener
  [config _]
  (fn unblocked-listener []
    (logger/log (:logger config) :info ::unblocked-listener {})))

(defmethod prepare-listener :recovery-listener
  [config listener]
  (fn recovery-listener [^Recoverable _]
    (logger/log (:logger config) :info (keyword *ns* (:name listener)) {})))

(defmethod prepare-listener :queue-recovery-listener
  [config _]
  (fn queue-recovery-listener [^String old-name ^String new-name]
    (logger/log (:logger config) :info ::queue-recovery-listener {:old-name old-name
                                                                  :new-name new-name})))
(def event-listeners
  {:shutdown-listener-fn {:name :shutdown-listener
                          :type :shutdown-listener}
   :blocked-listener-fn [{:name :blocked-listener
                          :type :blocked-listener}
                         {:name :unblocked-listener
                          :type :unblocked-listener}]
   :recovery-listener-fn [{:name :recovery-started-listener
                           :type :recovery-listener}
                          {:name :recovery-finished-listener
                           :type :recovery-listener}]
   :queue-recovery-listener-fn {:name :queue-recovery-listener
                                :type :queue-recovery-listener}})

(defn- consuming-callback
  [_ _ ^bytes received-payload]
  (let [value (nippy/thaw received-payload)]
    (= value payload)))

(defn- init-key
  [config]
  ;; Don't create a new AtomLogger each time we initialize the
  ;; Integrant key, but simply empty the one already existing in
  ;; `config`. This way we can use the same logger (actually the same
  ;; full config, including the logger) in the listeners and in the
  ;; AMQP client itself. This is important because we need to
  ;; instantiate the listeners before we can create the AMQP
  ;; client (by calling init-key).
  (reset! (get-in config [:logger :logs]) [])
  (ig/init-key :dev.gethop.pubsub/amqp config))

(deftest conn-test
  (testing "TCP connection is established"
    (let [config base-config
          {:keys [client] :as amqp} (init-key config)]
      (is (and client (instance? dev.gethop.pubsub.amqp.PubSubAMQPClient client)))
      (ig/halt-key! :dev.gethop.pubsub/amqp amqp)))

  (testing "TCP connection, with listener and exception handler options, is established"
    ;; Give the underlying client library time to close the connection
    ;; from the previous test and fire calls to all
    ;; listeners. Otherwise those pending listener calls will taint
    ;; the logs we want to check in this test.
    (Thread/sleep 250)
    (let [event-listeners-config (into {} (map (fn [[k v]]
                                                 [k (if (sequential? v)
                                                      (mapv prepare-listener base-config v)
                                                      (prepare-listener base-config v))]))
                                       event-listeners)
          config (-> base-config
                     (assoc-in [:broker-config :listeners] event-listeners-config))
          {:keys [client logger] :as amqp} (init-key config)]
      (is (and client (instance? dev.gethop.pubsub.amqp.PubSubAMQPClient client)))
      (ig/halt-key! :dev.gethop.pubsub/amqp amqp)
      ;; Give the underlying client library time to close the
      ;; connection and fire calls to all listeners, before checking
      ;; the logs. Otherwise we may miss some log entries.
      (Thread/sleep 250)
      (is (some (fn [[level _ _ _ event data]]
                  (and (= level :info)
                       (= event ::shutdown-listener)
                       (= data {:root-cause :amqp-method-executed,
                                :amqp-method-name "connection.close"})))
                @(:logs logger)))))

  (testing "SSL connection is established"
    (let [config (-> base-config
                     (assoc-in [:broker-config :transport] :ssl)
                     (assoc-in [:broker-config :port] (:port ssl-config))
                     (assoc :ssl-config ssl-config))
          {:keys [client] :as amqp} (init-key config)]
      (is (and client (instance? dev.gethop.pubsub.amqp.PubSubAMQPClient client)))
      (ig/halt-key! :dev.gethop.pubsub/amqp amqp)))

  (testing "Connection establish attempt retries on failure"
    (let [max-retries 1    ;; It means try once and then retry once.
          backoff-ms [1 2] ;; Wait 1 ms between retries, with a max of 2 ms.
          config (-> base-config
                     (assoc-in [:broker-config :host] "doesn-exist.invalid")
                     (assoc :max-retries max-retries)
                     (assoc :backoff-ms backoff-ms))
          {:keys [client logger]} (init-key config)
          logs (:logs logger)]
      (is (and (nil? client)
               (= max-retries
                  (count
                   (filter
                    #(= (nth % 4) ;; Each log's fifth element is a log info.
                        :dev.gethop.pubsub.amqp/retrying-connection-attempt)
                    @logs))))))))

(deftest publish-test
  (testing "Publishing to a queue with no consumers, message counts increases"
    (let [config base-config
          {:keys [client] :as amqp} (init-key config)
          channel (:channel client)]
      (lq/declare channel queue queue-attrs)
      (let [exchange default-exchange-name
            routing-key queue
            payload (nippy/freeze payload)
            opts {}
            status-before (lq/status channel queue)
            _ (core/publish! client {:exchange exchange :routing-key routing-key} payload opts)
            ;; Let the message arrive and be routed to the queue(s)
            _ (Thread/sleep 250)
            status-after (lq/status channel queue)]
        (is (< (:message-count status-before) (:message-count status-after)))
        (ig/halt-key! :dev.gethop.pubsub/amqp amqp)))))

(deftest subscribe-and-consume-test
  (testing "Consuming messages from a queue decreases queue's message count"
    (let [config base-config
          {:keys [client] :as amqp} (init-key config)
          channel (:channel client)]
      (lq/declare channel queue queue-attrs)
      (let [opts {:queue-attrs queue-attrs :consumer-opts {:auto-ack true}}
            status-before (lq/status channel queue)
            tag (core/subscribe! client queue opts consuming-callback)
            status-after (lq/status channel queue)]
        ;; If the queue was empty, the message count remains equal!
        (is (>= (:message-count status-before) (:message-count status-after)))
        (core/unsubscribe! client tag)
        (ig/halt-key! :dev.gethop.pubsub/amqp amqp)))))
