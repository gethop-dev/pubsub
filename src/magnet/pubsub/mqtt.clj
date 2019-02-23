;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns magnet.pubsub.mqtt
  (:require [clojure.spec.alpha :as s]
            [clojurewerkz.machine-head.client :as mh]
            [diehard.core :as diehard]
            [duct.logger :refer [log]]
            [integrant.core :as ig]
            [magnet.pubsub.core :as core]
            [magnet.pubsub.custom-ssl :as ssl])
  (:import [org.eclipse.paho.client.mqttv3 MqttClient]))

(s/def ::qos #{0 1 2})

(def ^:const default-qos
  "Default MQTT QoS value: try to deliver message at least once"
  1)

(s/def ::transport #{:tcp :ssl})

(def ^:const default-transport
  "Default transport to use to connect to MQTT broker.
  Can be either `:tcp` or `:ssl"
  :ssl)

(def ^:const default-ssl-port
  "Default SSL port to use to connect to MQTT broker"
  8883)

(def ^:const default-tcp-port
  "Default plain TCP port to use to connect to MQTT broker"
  1883)

(def ^:const default-max-retries
  "Default limit of attempts to connect to MQTT broker"
  10)

(def ^:const default-initial-delay
  "Initial delay for retries, specified in milliseconds."
  500)

(def ^:const default-max-delay
  "Maximun delay for a connection retry, specified in milliseconds. We
  are using truncated binary exponential backoff, with `max-delay` as
  the ceiling for the retry delay."
  10000)

(def ^:const default-backoff-ms
  [default-initial-delay default-max-delay 2.0])

(defn- retry-policy [max-retries backoff-ms]
  (diehard/retry-policy-from-config
   {:max-retries max-retries
    :backoff-ms backoff-ms}))

(defn- on-retry [logger max-retries]
  (let [remaining (- max-retries diehard/*executions*)]
    (log logger :report ::retrying-connection-attempt
         {:retries-remaining remaining})))

(defn- listener [logger max-retries]
  (diehard/listeners-from-config
   {:on-retry (fn [result-value exception-thrown]
                (on-retry logger max-retries))}))

(defn- fallback [logger exception]
  (log logger :report ::cant-connect-mqtt-broker [(.getMessage exception)])
  {:logger logger :client nil})

(s/def ::conn #(instance? MqttClient %))
(s/def ::topic string?)
(s/def ::payload bytes?)
(s/def ::retained? boolean?)
(s/def ::private-publish!-opts (s/keys :opt-un [::qos ::retained?]))

(defn- private-publish!
  "Publish a MQTT message with `payload` as data to given `topic`.
  An optional `opts` map can be specified, with the following keys:

  * `:qos`: any valid MQTT Quality of Service value (0,1,2)
  * `:retained?`: boolean, set the MQTT Retained flag on the message."
  [conn topic payload {:keys [qos retained?] :or {qos default-qos retained? false} :as opts}]
  {:pre [(and (s/valid? ::conn conn)
              (s/valid? ::topic topic)
              (s/valid? ::payload payload)
              (s/valid? ::private-publish!-opts opts))]}
  (mh/publish conn ^String topic payload qos retained?))

(s/def ::private-publish!-args (s/cat :conn ::conn :topic ::topic :payload ::payload :opts ::private-publish!-opts))
(s/fdef private-publish!
  :args ::private-publish!-args)

(s/def ::private-subscribe!-opts (s/keys :opt-un [::qos ::retained?]))

(defn- private-subscribe!
  "Subscribe to receive messages from `topic`.
  `opts` is a map with the following optional keys:

  * `:qos`: any valid MQTT Quality of Service value (0,1,2)

  `callback` function is called everytime a message is
  received. `callback` is expected to receive three arguments:

     * the topic name
     * a map with meta-data about the message
     * the message payload (as a byte array)

  Returns a subscription tag that is needed for unsubscribing."
  [conn topic opts callback]
  {:pre [(and (s/valid? ::conn conn)
              (s/valid? ::topic topic)
              (s/valid? ::private-subscribe!-opts opts)
              (fn? callback))]}
  (let [{:keys [qos] :or {qos default-qos}} opts]
    (mh/subscribe conn {topic qos} callback)
    topic))

(s/def ::private-subscribe!-args (s/cat :conn ::conn :topic ::topic :opts ::private-subscribe!-opts :callback fn?))
(s/fdef private-subscribe!
  :args ::private-subscribe!-args)

(s/def ::tag string?)

(defn- private-unsubscribe!
  "Unsubscribe to receive messages from the topic associated with `tag`"
  [conn tag]
  {:pre [(and (s/valid? ::conn conn)
              (s/valid? ::tag tag))]}
  (try
    (mh/unsubscribe conn tag)
    (catch Exception e
      ;; The tag is invalid or we are not susbscribed any more, so ignore it.
      nil)))

(s/def ::private-unsubscribe!-args (s/cat :conn ::conn :topic ::topic))
(s/fdef private-unsubscribe!
  :args ::private-unsubscribe!-args)

(defrecord PubSubMQTTClient [conn]
  core/PubSubClient
  (publish! [this destination payload opts]
    (private-publish! (:conn this) destination payload opts))
  (subscribe! [this topic-or-queue opts callback]
    (private-subscribe! (:conn this) topic-or-queue opts callback))
  (unsubscribe! [this topic-or-queue]
    (private-unsubscribe! (:conn this) topic-or-queue)))

(s/def ::host string?)
(s/def ::port (s/or :string string? :integer integer?))
(s/def ::logger #(satisfies? duct.logger/Logger %))
(s/def ::username string?)
(s/def ::password string?)
(s/def ::opts map?)
(s/def ::broker-config (s/keys :req-un [::host]
                               :opt-un [::transport ::port ::username ::password ::opts]))
(s/def ::ssl-config :magnet.pubsub.custom-ssl/ssl-config)
(s/def ::max-retries :retry/max-retries) ;; From diehard.spec
(s/def ::backoff-ms :retry/backoff-ms)   ;; From diehard.spec

(s/def ::config (s/keys :req-un [::broker-config ::logger]
                        :opt-un [::ssl-config ::max-retries ::backoff-ms]))

(defn- connect
  [{:keys [broker-config ssl-config logger max-retries backoff-ms]
    :or {max-retries default-max-retries
         backoff-ms default-backoff-ms} :as config}]
  {:pre [(s/valid? ::config config)]}
  (let [conn-keys [:transport :host :port :username :password :opts]
        {:keys [transport host port username password opts]
         :or {transport default-transport
              opts {}}} broker-config
        port (or port (if (= transport :ssl) default-ssl-port default-tcp-port))
        broker-url (format "%s://%s:%s" (name transport) host port)
        conn-opts (cond-> opts
                    username (conj {:username username})
                    password (conj {:password password})
                    (= transport :ssl) (conj {:socket-factory (ssl/custom-ssl-socket-factory ssl-config)}))
        conn-config (-> (apply dissoc broker-config conn-keys)
                        (assoc :opts conn-opts))]
    (log logger :report ::starting-connection)
    (diehard/with-retry {:retry-on Exception
                         :listener (listener logger max-retries)
                         :policy (retry-policy max-retries backoff-ms)
                         :fallback (fn [_ exception] (fallback logger exception))}
      (let [conn (mh/connect broker-url conn-config)]
        (log logger :report ::connection-started)
        {:logger logger
         :client (->PubSubMQTTClient conn)}))))

(s/def ::connect-args (s/cat :config ::config))
(s/fdef connect
  :args ::connect-args)

(defmethod ig/init-key :magnet.pubsub/mqtt [_ config]
  (connect config))

(defmethod ig/halt-key! :magnet.pubsub/mqtt [_ {:keys [client logger]}]
  (log logger :report ::releasing-connection)
  (let [conn (:conn client)]
    (when (and client (mh/connected? conn))
      (mh/disconnect-and-close conn))))
