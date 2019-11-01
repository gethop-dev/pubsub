[![Build Status](https://api.travis-ci.com/magnetcoop/pubsub.svg?branch=master)](https://travis-ci.com/magnetcoop/pubsub)
[![Clojars Project](https://img.shields.io/clojars/v/magnet/pubsub.svg)](https://clojars.org/magnet/pubsub)

# Duct MQTT and AMQP Publish Subscribe library

An opinionated [Duct](https://github.com/duct-framework/duct) library that provides [Integrant](https://github.com/weavejester/integrant) keys for publishing and subscribing to messages using MQTT or AMQP.

It wraps [machine_head](https://github.com/clojurewerkz/machine_head) and [langohr](https://github.com/michaelklishin/langohr) libraries for MQTT and AMQP respectively. It implements a common usage pattern for both libraries, implemented as a protocol, so you can use them interchangeably. But if you need to go deeper and use `machine_head` or `langohr` features directly, it lets you do so. See below for examples on using the library.

## Installation

[![Clojars Project](https://clojars.org/magnet/pubsub/latest-version.svg)](https://clojars.org/magnet/pubsub)

## Usage

### Configuration

This library provides two Integrant keys, one for each pubsub communication protocol: `:magnet.pubsub/mqtt` and `:magnet.pubsub/amqp`

#### `:magnet.pubsub/mqtt`

This key expects a configuration map that includes several mandatory configuration keys, plus some optional ones. These are the mandatory keys:

* `:broker-config`: the value of this key is a map with the following keys:
  * `:host`: The hostname or IP address of the MQTT broker. This key is MANDATORY.
  * `:transport`: The type of transport protocol used to connect to the MQTT broker. The only supported values are `:tcp` and `:ssl`. This key is OPTIONAL, and defaults to `:ssl`.
  * `:port`: The port where the MQTT broker accepts connections for the configured transport protocol. This key is OPTIONAL and defaults to the standard MQTT port for the configured `:transport`.
  * `:username`: If the MQTT broker requires authentication, this is the username to connect with. This key is OPTIONAL, and the default value is an empty username.
  * `:password`: If the MQTT broker requires authentication, this is the password to connect with. This key is OPTIONAL, and the default value is an empty password.
  * `:opts`: [clojurewerkz.machine-head.client/connect](https://github.com/clojurewerkz/machine_head/blob/master/src/clojure/clojurewerkz/machine_head/client.clj) accepts special MQTT connection options like `:auto-reconnect`, `:connection-timeout`, etc. If you need/want to use any of these options, you can specify them here as a map. This key is OPTIONAL.
* `:logger`: usually a reference to `:duct/logger` key. But you can use any Integrant key derived from `:duct/logger` (such as `:duct.logger/timbre`).

If you need a custom SSL/TLS configuration (minimum SSL/TLS version for the connection, non-standard SSL/TLS port, custom CA certificates, client certificates, etc.) you can specify the following optional configuration key:

* `:ssl-config`: The value of this key is a map with the following configuration keys (all of them are OPTIONAL):
  * `:tls-version`: A string with a valid SSL/TLS version to use for the SSL connection. The default is "TLSv1.2". Other valid values can be found at https://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html#SSLContext
  * `:ca-crt-file`: Path to a file with a custom Certification Authority (CA) certificate in PEM format (or a certificate bundle in PEM format, with a full certification chain), used to validate the MQTT broker certificate.
  * `:crt-file`: Path to a file with the client certificate, in PEM format.
  * `:key-file`: Path to a file with the client private key, in PEM format.
  * `:key-password`: Password used to decrypt the client private key, if it is encrypted.

You can also configure the following optional configuration keys to specify how to handle connection attempts to the broker:

* `:max-retries`: If the connection attempt fails, how many retries we want to attempt before giving up.
* `:backoff-ms`: This is a vector in the form `[initial-delay-ms max-delay-ms multiplier]` to control the delay between each retry. The delay for nth retry will be `(max (* initial-delay-ms n multiplier) max-delay-ms)`. If `multiplier` is not specified (or if it is `nil`), a multiplier of 2 is used. All times are in milli-seconds.

Key initialization returns a map with two keys. A key called `:logger` which holds a copy of the logger configuration setting (to be used in the `halt-key!` method). And a key called `:client`, which is a `PubSubMQTTClient` record that can be used to perform the publishing and subscribing operations described below. Also notice that the `PubSubMQTTClient` record has a key called `:conn` that is an instance of a `machine_head` MQTT client connection. You can use this value to perform calls into the `machine_head` library functions directly.

#### `:magnet.pubsub/amqp`

This key expects a configuration map that includes several mandatory configuration keys, plus some optional ones. These are the mandatory keys:

* `:broker-config`: the value of this key is a map with the following keys:
  * `:transport`: The type of transport protocol used to connect to the AMQP broker. The only supported values are `:tcp` and `:ssl`. This key is OPTIONAL, and defaults to `:ssl`.
  * `:host`: The hostname or IP address of the AMQP broker. This key is MANDATORY.
  * `:port`: The port where the AMQP broker accepts connections for the configured transport protocol. This key is OPTIONAL and defaults to the standard AMQP SSL/TLS port for the configured `:transport`.
  * `:vhost`: Virtual host of the AMQP broker to connect to (in case the broker supports virtual hosts). This key is OPTIONAL, and defaults to a virtual host called `/`.
  * `:username`: If the AMQP broker requires authentication, this is the username to connect with. This key is OPTIONAL, and the default value is an empty username.
  * `:password`: If the AMQP broker requires authentication, this is the password to connect with. This key is OPTIONAL, and the default value is an empty password.
  * `:opts`: [langohr.core/connect](https://github.com/michaelklishin/langohr/blob/master/src/clojure/langohr/core.clj) accepts special [AMQP connect options](http://clojurerabbitmq.info/articles/connecting.html#using-a-map-of-parameters) like `:requested-heartbeat`, `:connection-timeout`, etc. If you need/want to use any of these options, you can specify them here as a map. This key is OPTIONAL.
* `:logger`: usually a reference to `:duct/logger` key. But you can use any Integrant key derived from `:duct/logger` (such as `:duct.logger/timbre`).

Again, if you need a custom SSL/TLS configuration (minimum SSL/TLS version for the connection, non-standard SSL/TLS port, custom CA certificates, client certificates, etc.) you can specify the the same `:ssl-config` optional configuration key, with the same structure and values as the MQTT Integrant key.

You can also specify the `:max-retries` and `:backoff-ms` optional configuration keys that are available for the MQTT Integrant key.

Key initialization returns a map with two keys. A key called `:logger` which holds a copy of the logger configuration setting (to be used in the `halt-key!` method). And a key called `:client`, which is a `PubSubAMQPClient` record that can be used to perform the publishing and subscribing operations described below. Also notice that the `PubSubAMQPClient` record has a key called `:channel` that is an instance of an already opened `langohr` AMQP channel. You can use this value to perform calls into the `langohr` library functions directly.

#### Configuration examples

MQTT example usage with most of the optional configuration keys, using custom CA and client certificates, custom TLS version, custom broker port, user authentication, and special connection options:

``` edn
  :magnet.pubsub/mqtt
  {:broker-config {:transport :ssl
                   :port 32768
                   :host (System/getenv "MQTT_HOST")
                   :port (System/getenv "MQTT_PORT")
                   :username (System/getenv "MQTT_USERNAME")
                   :password (System/getenv "MQTT_PASSWORD")
                   :opts {:auto-reconnect true
                          :max-inflight 5}}
   :ssl-config {:tls-version "TLSv1.1"
                :ca-crt-file (System/getenv "MQTT_SSL_CA_CRT_FILE")
                :crt-file (System/getenv "MQTT_SSL_CRT_FILE")
                :key-file (System/getenv "MQTT_SSL_KEY_FILE")
                :key-password (System/getenv "MQTT_SSL_KEY_PASSWORD")}
   :logger #ig/ref :duct/logger}
```

MQTT example usage, for non-SSL non-authenticated connection to the broker, using standard TCP port:


``` edn
  :magnet.pubsub/mqtt
  {:broker-config {:transport :tcp
                   :host (System/getenv "MQTT_HOST")}
   :logger #ig/ref :duct/logger}
```

AMQP example usage with most of the optional configuration keys, using custom CA and client certificates, custom TLS version, custom broker port, user authentication, and special connection options:

``` edn
  :magnet.pubsub/amqp
  {:broker-config {:transport :ssl
                   :port 32768
                   :host (System/getenv "AMQP_HOST")
                   :port (System/getenv "AMQP_PORT")
                   :vhost (System/getenv "AMQP_VHOST")
                   :username (System/getenv "AMQP_USERNAME")
                   :password (System/getenv "AMQP_PASSWORD")
                   :opts {:requested-heartbeat 120}}
   :ssl-config {:tls-version "TLSv1.1"
                :ca-crt-file (System/getenv "AMQP_SSL_CA_CRT_FILE")
                :crt-file (System/getenv "AMQP_SSL_CRT_FILE")
                :key-file (System/getenv "AMQP_SSL_KEY_FILE")
                :key-password (System/getenv "AMQP_SSL_KEY_PASSWORD")}
   :logger #ig/ref :duct/logger}
```

AMQP example usage, for non-SSL non-authenticated connection to the broker, using standard TCP port and virtual host:

``` edn
  :magnet.pubsub/amqp
  {:broker-config {:transport :tcp
                   :host (System/getenv "AMQP_HOST")}}
   :logger #ig/ref :duct/logger}
```

### Publishing and consuming (subscribing to) messages

#### MQTT

In this example we publish some structured Clojure data, serialized to a byte array using Nippy serialization (MQTT only deals with streams of bytes). The idea is that both the publisher and the consumer are Clojure applications that want to exchange native Clojure data structures (using an efficient serialization like Nippy).

First we define some vars to specify the topic we want to publish to/consume from, and the data to be exchanged:

``` clojure
user> (def sensor-id "825b4260-f5a6-45ed-9e27-c96358b0126f")
#'user/sensor-id
user> (def topic (str "sensor/" sensor-id "/data"))
#'user/topic
user> (def payload {:unit :volts
                    :data [{:timestamp 1549901822.425 :value 12.34}
                           {:timestamp 1549901822.725 :value 12.29}
                           {:timestamp 1549901823.023 :value 12.32}
                           {:timestamp 1549901823.212 :value 12.30}]})
#'user/payload
user> 
```

Then we need to define the configuration we'll use to initialize the `:magnet.pubsub/mqtt` Integrant key. We use a SSL/TLS connection to the broker, with standard CA certificates involved, and we need to provide a username and password:

``` clojure
user> (def config {:broker-config {:host (System/getenv "MQTT_HOST")
                                   :transport :ssl
                                   :username (System/getenv "MQTT_USERNAME")
                                   :password (System/getenv "MQTT_PASSWORD")}
                   :logger nil})
#'user/config
user> 
```

Some MQTT brokers (like RabbitMQ with the MQTT plugin, or mosquitto) have the option to notify the publisher when the message has been delivered to at least one subscriber. If we want to use that feature, we need to define a callback function that will be invoked when the broker notifies us back. So let's define a delivery callback (we'll ignore the `token` parameter of the callback function in this example):

``` clojure
user> (defn delivery-callback [_]
        (println "Publisher: message delivered!"))
#'user/delivery-callback
user> 
```

We are going to play the roles of both the publisher and the consumer in the same sample code. So we need to define a consuming callback function too. In this particular example, we don't care about received messages' metadata, so we ignore it:

``` clojure
user> (require '[clojure.pprint :refer [pprint]]
               '[taoensso.nippy :as nippy])
nil
user> (defn consuming-callback [topic _ ^bytes received-payload]
        (let [value (nippy/thaw received-payload)]
          (println (format  "Consumer: received message from topic: %s" topic))
          (println "Consumer: payload follows:")
          (pprint value)))
#'user/consuming-callback
user> 
```

Now that we have all pieces in place, we can initialize the `:magnet.pubsub/mqtt` Integrant key to get a PubSubMQTTClient record. We extend `:broker-config` to include the optional delivery callback function:

``` clojure
user> (require '[integrant.core :as ig]
               '[magnet.pubsub.mqtt :as mqtt])
nil
user> (def mqtt (->
                   config
                   (assoc-in [:broker-config :on-delivery-complete] delivery-callback)
                   (->> (ig/init-key :magnet.pubsub/mqtt))))
#'user/mqtt
user> (def client (:client mqtt))
#'user/client
user> 
```

Now that we have the PubSubMQTTClient record, we can use the generic protocol methods defined in `magnet.pubsub.core` to publish, subcribe and unsubscribe to topics. We tell the MQTT broker that we want to subscribe to the topic we are interested in, with a QoS of 1. When we subscribe to a topic, we receive a `tag` from the broker. We need that tag later to cancel the subscription. So store it:

``` clojure
(require '[magnet.pubsub.core :as pubsub])
nil
user> (def tag (pubsub/subscribe! client topic {:qos 1} consuming-callback))
#'user/tag
user> 
```

Once the subscriber is ready, we can publish our message. This time we tell the MQTT broker that we want to publish our message with a QoS of 0 (the default, if not specified). Depending on the latency of the connection between the broker and the machine where we are running the example, it might take just a few milliseconds to receive the message in the consuming callback (and the delivery callback of the publisher). So we may see the output of both callbacks almost as soon as we execute the following function call:

``` clojure
user> (pubsub/publish! client topic (nippy/freeze payload) {})
nil
Consumer: received message from topic: sensor/825b4260-f5a6-45ed-9e27-c96358b0126f/data
Consumer: payload follows:
{:unit :volts
 :data
 [{:timestamp 1.549901822425E9, :value 12.34}
  {:timestamp 1.549901822725E9, :value 12.29}
  {:timestamp 1.549901823023E9, :value 12.32}
  {:timestamp 1.549901823212E9, :value 12.3}]}
Publisher: message delivered!
user> 
```

Now that the message has been published and consumed, we can tear everything down. First we unsubscribe from the topic:

``` clojure
user> (pubsub/unsubscribe! client tag)
nil
user> 
```

And then we halt the Integrant key to close the connection and free up resources:

``` clojure
user> (ig/halt-key! :magnet.pubsub/mqtt mqtt)
#object[org.eclipse.paho.client.mqttv3.MqttClient
        "0x303efcb1"
        "org.eclipse.paho.client.mqttv3.MqttClient@303efcb1"]
user> 
```

#### AMQP

In this example we publish some structured Clojure data as JSON, serialized to a byte array (again AMQP only deals with streams of bytes). The idea is that the publisher is our Clojure application, but the consumer is implemented in some other technology and can only consume JSON data.

Again we first require all the relevant namespaces:

``` clojure
user> (require '[clojure.data.json :as json]
               '[clojure.pprint :refer [pprint]]
               '[integrant.core :as ig]
               '[langohr.queue :as lq]
               '[magnet.pubsub.core :as pubsub]
               '[magnet.pubsub.amqp :as amqp])
SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
SLF4J: Defaulting to no-operation (NOP) logger implementation
SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
nil
user> 
```

First we define some vars to specify the queue we want to consume from, and the data to be exchanged. The queue name will also be the routing key for the exchange we will use (see later):

``` clojure
user> (def sensor-id "825b4260-f5a6-45ed-9e27-c96358b0126f")
#'user/sensor-id
user> (def queue (str "sensor." sensor-id ".data"))
#'user/queue
user> (def payload {:unit :volts
                    :data [{:timestamp 1549901822.425 :value 12.34}
                           {:timestamp 1549901822.725 :value 12.29}
                           {:timestamp 1549901823.023 :value 12.32}
                           {:timestamp 1549901823.212 :value 12.30}]})
#'user/payload
user> 
```

We use the default direct exchange that is always available in AMQP brokers (unnamed, referred to as an empty string)

``` clojure
user> (def exchange "")
#'user/exchange
user> 
```

and define the attributes of the queue that we want to use, to be able to declare it. Declaration can be done either in the publisher, the subscriber or the broker itself, but it is important to use the same attributes in all places.

Declaring a queue will cause it to be created if it does not already exist. The declaration will have no effect if the queue does already exist and its attributes are the same as those in the declaration. When the existing queue attributes are not the same as those in the declaration a channel-level exception is raised.

Let's create a durable queue that is not deleted when the publishers and subscribers go away:

``` clojure
user> (def queue-attrs {:durable true :auto-delete false})
#'user/queue-attrs
user> 
```

Then we need to define the configuration we'll use to initialize the `:magnet.pubsub/amqp` Integrant key. We use a SSL/TLS connection to the broker, with standard CA certificates involved, and we need to provide a username and password:


``` clojure
user> (def config {:broker-config {:host (System/getenv "AMQP_HOST")
                                   :transport :ssl
                                   :vhost (System/getenv "AMQP_VHOST")
                                   :username (System/getenv "AMQP_USERNAME")
                                   :password (System/getenv "AMQP_PASSWORD")}
                    :logger nil})
#'user/config
user> 
```

Again, we are going to play the roles of both the publisher and the consumer in the same sample code. So we need to define a consuming callback function. In this particular example we are interested in some message metadata, namely the routing key value and the MIME content type of the message:

``` clojure
user> (require '[clojure.data.json :as json]
               '[clojure.pprint :refer [pprint]])
nil
user> (defn consuming-callback [channel metadata ^bytes received-payload]
        (let [value (json/read-str (String. received-payload "UTF-8"))
              {:keys [routing-key content-type]} metadata]
          (println (format "Consumer: received message with routing-key: %s" routing-key))
          (println (format "Consumer: decoded payload follows (with Content-Type: %s)" content-type))
          (pprint value)))
#'user/consuming-callback
user> 
```

Now that we have all pieces in place, we can initialize the `:magnet.pubsub/amqp` Integrant key to get a PubSubAMQPClient record:

``` clojure
user> (require '[integrant.core :as ig])
nil
user> (def amqp (ig/init-key :magnet.pubsub/amqp config))
#'user/amqp
user> (def client (:client amqp))
#'user/client
user> 
```

To declare the queue in our publisher (so we don't need to do it in the AMQP broker beforehand, to make this example self-contained) we can use the channel that was created as part of the connection to the broker. So retrieve it from the PubSubAMQPClient record and declare the queue:

``` clojure
user> (require '[langohr.queue :as lq])
SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
SLF4J: Defaulting to no-operation (NOP) logger implementation
SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
nil
user> (def channel (:channel client))
#'user/channel
user> (lq/declare channel queue queue-attrs)
{:queue "sensor.825b4260-f5a6-45ed-9e27-c96358b0126f.data",
 :message_count 0,
 :consumer_count 0,
 :message-count 0,
 :consumer-count 0}
user> 
```

Now that we have the PubSubAMQPClient record, we can use the generic protocol methods defined in `magnet.pubsub.core` to publish, subcribe and unsubscribe to queues. So we subscribe to the queue we are interested in. When subscribing to a queue, we also need to specify the queue attributes to use (the subscriber declares the queue too, as in the general case it can't know whether it has been declared before). We are also going to specify an optional configuration setting for the consumer, `:auto-ack`, so the AMQP library automatically ACKs every received message to the broker.

When we subscribe to a queue, we receive a `tag` from the broker that we later need to cancel the subscription. So we need to remember it.

NOTICE: if there were pending, un-ACKed messages in the queue from previous attempts, we might receive them when we execute the `pubsub/subscribe!` method call.

``` clojure
user> (require '[magnet.pubsub.core :as pubsub])
nil
user> 
user> (def subscribe-opts {:queue-attrs queue-attrs :consumer-opts {:auto-ack true}})
#'user/subscribe-opts
user> (def tag (pubsub/subscribe! client queue subscribe-opts consuming-callback))
#'user/tag
user> 
```

Once the subscriber is ready, we can publish our message. This time we tell the AMQP broker that we want to attach some metadata attributes to the message we are publishing. In particular, we state that the MIME content type of our message is `application/json`.

Again, depending on the latency of the connection between the broker and the machine where we are running the example, it might take just a few milliseconds to receive the message in the consuming callback. So we may see the output of the consuming callback almost as soon as we execute `pubsub/publish!` method call:


``` clojure
user> (let [payload (byte-array (map (comp byte int) (json/write-str payload)))
            publish-opts {:content-type "application/json"}
            routing-key queue]
        (pubsub/publish! client {:exchange exchange :routing-key queue} payload publish-opts))
nil
Consumer: received message with routing-key: sensor.825b4260-f5a6-45ed-9e27-c96358b0126f.data
Consumer: decoded payload follows (with Content-Type: application/json)
{"unit" "volts",
 "data"
 [{"timestamp" 1.549901822425E9, "value" 12.34}
  {"timestamp" 1.549901822725E9, "value" 12.29}
  {"timestamp" 1.549901823023E9, "value" 12.32}
  {"timestamp" 1.549901823212E9, "value" 12.3}]}
user> 
```

Now that the message has been published and consumed, we can tear everything down. We unsubscribe from the queue (using the tag) and then halt the Integrant key to close the connection and free up resources:


``` clojure
user> (pubsub/unsubscribe! client tag)
nil
user> (ig/halt-key! :magnet.pubsub/amqp amqp)
nil
user> 
```

## License

Copyright (c) 2018, 2019 Magnet S Coop.

The source code for the library is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
