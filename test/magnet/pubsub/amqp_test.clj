;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns magnet.pubsub.amqp-test
  (:require [clojure.java.io :as io]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer :all]
            [duct.logger :as logger]
            [integrant.core :as ig]
            [langohr.queue :as lq]
            [magnet.pubsub.amqp :as amqp]
            [magnet.pubsub.core :as core]
            [taoensso.nippy :as nippy]))

(defn enable-instrumentation [f]
  (-> (stest/enumerate-namespace 'magnet.pubsub.amqp) stest/instrument)
  (f))

(use-fixtures :once enable-instrumentation)

(defrecord AtomLogger [logs]
  logger/Logger
  (-log [logger level ns-str file line id event data]
    (swap! logs conj [level ns-str file line event data])))

(def default-exchange-name
  "The name of the default AMQP exchange (empty string), which is always
  available and is configured as a direct exchange (routing all
  messages to queues named like the routing key"
  "")

(def sensor-id "825b4260-f5a6-45ed-9e27-c96358b0126f")

(def queue (str "sensor." sensor-id ".data"))

(def queue-attrs {:durable true :auto-delete false})

(def payload {:unit :volts
              :data [{:timestamp 1549901822.425 :value 12.34}
                     {:timestamp 1549901822.725 :value 12.29}
                     {:timestamp 1549901823.023 :valque 12.32}
                     {:timestamp 1549901823.212 :value 12.30}]})

(def base-config {:broker-config {:transport :tcp
                                  :host (System/getenv "AMQP_TESTS_HOST")
                                  :port (System/getenv "AMQP_TESTS_PORT")
                                  :vhost (System/getenv "AMQP_TESTS_VHOST")
                                  :username (System/getenv "AMQP_TESTS_USERNAME")
                                  :password (System/getenv "AMQP_TESTS_PASSWORD")}})

(def ssl-config {:port (System/getenv "AMQP_TESTS_SSL_PORT")
                 :tls-version (System/getenv "AMQP_TESTS_SSL_TLS_VERSION")
                 :ca-crt-file (System/getenv "AMQP_TESTS_SSL_CA_CRT_FILE")
                 :crt-file (System/getenv "AMQP_TESTS_SSL_CRT_FILE")
                 :key-file (System/getenv "AMQP_TESTS_SSL_KEY_FILE")
                 :key-password (System/getenv "AMQP_TESTS_SSL_KEY_PASSWORD")})

(defn- consuming-callback
  [channel metadata ^bytes received-payload]
  (let [value (nippy/thaw received-payload)]
    (= value payload)))

(defn- init-key
  [config]
  (let [logs (atom [])
        logger (->AtomLogger logs)
        config (-> config
                   (assoc :logger logger))]
    (ig/init-key :magnet.pubsub/amqp config)))

(deftest conn-test
  (testing "TCP connection is established"
    (let [config base-config
          {:keys [client] :as amqp} (init-key config)]
      (is (and client (instance? magnet.pubsub.amqp.PubSubAMQPClient client)))
      (ig/halt-key! :magnet.pubsub/amqp amqp)))

  (testing "SSL connection is established"
    (let [config (-> base-config
                     (assoc-in [:broker-config :transport] :ssl)
                     (assoc-in [:broker-config :port] (:port ssl-config))
                     (assoc :ssl-config ssl-config))
          {:keys [client] :as amqp} (init-key config)]
      (is (and client (instance? magnet.pubsub.amqp.PubSubAMQPClient client)))
      (ig/halt-key! :magnet.pubsub/amqp amqp)))

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
                        :magnet.pubsub.amqp/retrying-connection-attempt)
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
        (ig/halt-key! :magnet.pubsub/amqp amqp)))))

(deftest subscribe-and-consume-test
  (testing "Consuming messages from a queue decreases queue's message count"
    (let [config base-config
          {:keys [client] :as amqp} (init-key config)
          channel (:channel client)]
      (lq/declare channel queue queue-attrs)
      (let [exchange default-exchange-name
            opts {:queue-attrs queue-attrs :consumer-opts {:auto-ack true}}
            status-before (lq/status channel queue)
            tag (core/subscribe! client queue opts consuming-callback)
            status-after (lq/status channel queue)]
        ;; If the queue was empty, the message count remains equal!
        (is (>= (:message-count status-before) (:message-count status-after)))
        (core/unsubscribe! client tag)
        (ig/halt-key! :magnet.pubsub/amqp amqp)))))
