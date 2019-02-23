;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns magnet.pubsub.mqtt-test
  (:require [clojure.java.io :as io]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer :all]
            [duct.logger :as logger]
            [integrant.core :as ig]
            [magnet.pubsub.core :as core]
            [magnet.pubsub.mqtt :as mqtt]
            [taoensso.nippy :as nippy]))

(defn enable-instrumentation [f]
  (-> (stest/enumerate-namespace 'magnet.pubsub.mqtt) stest/instrument)
  (f))

(use-fixtures :once enable-instrumentation)

(defrecord AtomLogger [logs]
  logger/Logger
  (-log [logger level ns-str file line id event data]
    (swap! logs conj [level ns-str file line event data])))

(def sensor-id "825b4260-f5a6-45ed-9e27-c96358b0126f")

(def topic (str "sensor/" sensor-id "/data"))

(def payload {:unit :volts
              :data [{:timestamp 1549901822.425 :value 12.34}
                     {:timestamp 1549901822.725 :value 12.29}
                     {:timestamp 1549901823.023 :value 12.32}
                     {:timestamp 1549901823.212 :value 12.30}]})

(def base-config {:broker-config {:transport :tcp
                                  :host (System/getenv "MQTT_TESTS_HOST")
                                  :port (System/getenv "MQTT_TESTS_PORT")
                                  :username (System/getenv "MQTT_TESTS_USERNAME")
                                  :password (System/getenv "MQTT_TESTS_PASSWORD")}})

(def ssl-config {:port (System/getenv "MQTT_TESTS_SSL_PORT")
                 :tls-version (System/getenv "MQTT_TESTS_SSL_TLS_VERSION")
                 :ca-crt-file (System/getenv "MQTT_TESTS_SSL_CA_CRT_FILE")
                 :crt-file (System/getenv "MQTT_TESTS_SSL_CRT_FILE")
                 :key-file (System/getenv "MQTT_TESTS_SSL_KEY_FILE")
                 :key-password (System/getenv "MQTT_TESTS_SSL_KEY_PASSWORD")})

(def published-messages (atom 0))

(def consumed-messages (atom 0))

(defn- delivery-callback
  [token]
  (swap! published-messages inc))

(defn- consuming-callback
  [channel metadata ^bytes consumed-payload]
  (let [value (nippy/thaw consumed-payload)]
    (if (= value payload)
      (swap! consumed-messages inc))))

(defn- init-key
  [config]
  (let [logs (atom [])
        logger (->AtomLogger logs)
        config (-> config
                   (assoc :logger logger))]
    (ig/init-key :magnet.pubsub/mqtt config)))

(deftest conn-test
  (testing "TCP connection is established"
    (let [config base-config
          {:keys [client] :as mqtt} (init-key config)]
      (is (and client (instance? magnet.pubsub.mqtt.PubSubMQTTClient client)))
      (ig/halt-key! :magnet.pubsub/mqtt mqtt)))

  (testing "SSL connection is established"
    (let [config (-> base-config
                     (assoc-in [:broker-config :transport] :ssl)
                     (assoc-in [:broker-config :port] (:port ssl-config))
                     (assoc :ssl-config ssl-config))
          {:keys [client] :as mqtt} (init-key config)]
      (is (and client (instance? magnet.pubsub.mqtt.PubSubMQTTClient client)))
      (ig/halt-key! :magnet.pubsub/mqtt mqtt)))

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
                        :magnet.pubsub.mqtt/retrying-connection-attempt)
                    @logs))))))))

(deftest publish-consume-test
  (testing "Publishing and consuming messages to/from a topic"
    (let [config (-> base-config
                     (assoc-in [:broker-config :on-delivery-complete] delivery-callback))
          {:keys [client] :as mqtt} (init-key config)]
      (let [payload (nippy/freeze payload)
            publish-opts {:qos 1}
            published-messages-before @published-messages
            subscribe-opts {:qos 1}
            consumed-messages-before @consumed-messages
            tag (core/subscribe! client topic subscribe-opts consuming-callback)]
        (core/publish! client topic payload publish-opts)
        ;; Let the broker route the messages to the consumers and receive the messages locally
        (Thread/sleep 250)
        (is (and (> @published-messages published-messages-before)
                 (> @consumed-messages consumed-messages-before)))
        (core/unsubscribe! client tag)
        (ig/halt-key! :magnet.pubsub/mqtt mqtt)))))
