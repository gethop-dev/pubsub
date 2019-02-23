;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns magnet.pubsub.amqp
  (:require [clojure.spec.alpha :as s]
            [diehard.core :as diehard]
            [duct.logger :refer [log]]
            [integrant.core :as ig]
            [langohr.basic :as lb]
            [langohr.channel :as lch]
            [langohr.consumers :as lc]
            [langohr.core :as rmq]
            [langohr.queue :as lq]
            [magnet.pubsub.core :as core]
            [magnet.pubsub.custom-ssl :as custom-ssl]))

(s/def ::transport #{:tcp :ssl})

(def ^:const default-transport
  "Default transport to use to connect to AMQP broker.
  Can be either `:tcp` or `:ssl"
  :ssl)

(def ^:const default-ssl-port
  "Default SSL port to use to connect to AMQP broker"
  5671)

(def ^:const default-tcp-port
  "Default plain TCP port to use to connect to AMQP broker"
  5672)

(def ^:const default-vhost
  "Default AMQP broker virtual host to connect to"
  "/")

(def ^:const default-max-retries
  "Default limit of attempts to connect to AMQP broker"
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

(defn- fallback
  "Connection attempt fallback, when retries don't succeed.
  We log the connection failure and return `nil` for the client
  key (to signal the `init-key` caller the failure)"
  [logger exception]
  (log logger :report ::cant-connect-amqp-broker [(.getMessage exception)])
  {:client nil :logger logger})

(s/def ::conn #(instance? com.novemberain.langohr.Connection %))
(s/def ::channel  #(instance? com.rabbitmq.client.Channel %))
(s/def ::exchange string?)
(s/def ::routing-key string?)
(s/def ::destination (s/keys :req-un [::exchange ::routing-key]))
(s/def ::queue string?)
(s/def ::payload bytes?)
(s/def ::publish!-opts map?)

(defn- private-publish!
  "Publish a AMQP message with `payload` as data to given `destination`.
  `channel` must be an already existing channel associated with an open connection.
  `destination` must be a map with the following keys:

  * `:exchange`: the name of an already existing AMQP exchange.
  * `:routing-key`: the key used to route the message through the
                    exchange to its destination queue(s).

  See `langohr.basic/publish` documentation for the list of possible
  keys for the `opts` map parameter."
  [channel destination payload opts]
  {:pre [(and (s/valid? ::channel channel)
              (s/valid? ::destination destination)
              (s/valid? ::payload payload)
              (s/valid? ::publish!-opts opts))]}
  (let [{:keys [exchange routing-key]} destination]
    (lb/publish channel exchange routing-key payload opts)))

(s/def ::private-publish!-args (s/cat :channel ::channel :destination ::destination :payload ::payload :opts ::publish!-opts))
(s/fdef private-publish!
  :args ::private-publish!-args)

(s/def ::queue-attrs map?)
(s/def ::subscribe!-opts (s/keys :opt-un [::queue-attrs ::consumer-opts]))
(s/def ::tag string?)

(defn- private-subscribe!
  "Subscribe to receive messages from `queue`.
  `channel` must be an already existing channel associated with an
  open connection. `callback` is expected to receive three arguments:

     * the channel where the message was received
     * a map with meta-data about the message
     * the message payload (as a byte array)

  `opts` is a map with the following optional keys:

     * `queue-attrs`: A map. See `langohr.queue/declare documentation
                     for the list of possible keys for this key.
     * `consumer-opts`: A map. See `langohr.consumers/subscribe` and
                        `langohr.basic/consumer` documentation for the
                        list of possible keys for this key.

  Returns a subscription tag that is needed for unsubscribing."
  [channel queue opts callback]
  {:pre [(and (s/valid? ::channel channel)
              (s/valid? ::queue queue)
              (s/valid? ::subscribe!-opts opts)
              (fn? callback))]}
  (lq/declare channel queue (:queue-attrs opts))
  (lc/subscribe channel queue callback (:consumer-opts opts)))

(s/def ::private-subscribe!-args (s/cat :channel ::channel :queue ::queue :opts ::subscribe!-opts :callback fn?))
(s/def ::private-subscribe!-ret ::tag)
(s/fdef private-subscribe!
  :args ::private-subscribe!-args
  :ret ::private-subscribe!-ret)

(defn- private-unsubscribe!
  "Unsubscribe to receive messages from the queue associated with `tag`"
  [channel tag]
  {:pre [(and (s/valid? ::channel channel)
              (s/valid? ::tag tag))]}
  (try
    (lb/cancel channel tag)
    (catch Exception e
      ;; The tag is invalid or we are not susbscribed any more, so ignore it.
      nil)))

(s/def ::private-unsubscribe!-args (s/cat :channel ::channel :tag ::tag))
(s/fdef private-unsubscribe!
  :args ::private-unsubscribe!-args)

(defrecord PubSubAMQPClient [conn channel]
  core/PubSubClient
  (publish! [this destination payload opts]
    (private-publish! (:channel this) destination payload opts))
  (subscribe! [this topic-or-queue opts callback]
    (private-subscribe! (:channel this) topic-or-queue opts callback))
  (unsubscribe! [this tag]
    (private-unsubscribe! (:channel this) tag)))

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
  (let [{:keys [transport host port vhost username password opts]
         :or {transport default-transport
              vhost default-vhost
              opts {}} :as config} broker-config
        config (cond-> config
                 (nil? port) (update :port (fn [_]
                                             (if (= transport :ssl)
                                               default-ssl-port
                                               default-tcp-port)))
                 (string? port) (update :port #(Long/valueOf %))
                 (= transport :ssl) (-> (conj {:ssl true})
                                        (conj {:ssl-context (custom-ssl/custom-ssl-context ssl-config)})))]
    (log logger :report ::starting-connection)
    (diehard/with-retry {:retry-on Exception
                         :listener (listener logger max-retries)
                         :policy (retry-policy max-retries backoff-ms)
                         :fallback (fn [_ exception] (fallback logger exception))}
      (let [conn (rmq/connect config)
            channel (lch/open conn)]
        (log logger :report ::connection-started)
        {:logger logger
         :client (->PubSubAMQPClient conn channel)}))))

(s/def ::connect-args (s/cat :config ::config))
(s/fdef connect
  :args ::connect-args)

(defmethod ig/init-key :magnet.pubsub/amqp [_ config]
  (connect config))

(defmethod ig/halt-key! :magnet.pubsub/amqp [_ {:keys [client logger]}]
  (log logger :report ::releasing-connection)
  (let [{:keys [conn channel]} client]
    (when (and channel (rmq/open? channel))
      (rmq/close channel))
    (when (and conn (rmq/open? conn))
      (rmq/close conn))))
