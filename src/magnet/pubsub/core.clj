;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns magnet.pubsub.core)

(defprotocol PubSubClient
  "Abstraction for MQTT + AMQP clients publish/subscribe operations"
  (publish! [this destination payload opts]
    "Publish a message with `payload` as payload, and optional `opts`
     as message meta-data. Uses `destination` to decide how to route
     the message to subscribers. Depending on the protocol
     implementation this can be a topic name (MQTT), or a map
     specifying an exchange and message routing key (AMQP).")
  (subscribe! [this topic-or-queue opts callback]
    "Subscribe to receive messages from `topic-or-queue`.
     `opts` is a map with transport-specific options such as QoS
     levels, content type, etc. `callback` function is called
     everytime a message is received. `callback` is expected to
     receive three arguments:

     * the topic or queue name
     * a map with meta-data about the message
     * the message payload (as a byte array)

     Returns a subscription tag that is needed for unsubscribing.")
  (unsubscribe! [this tag]
    "Unsubscribe to receive messages from the topic or queue
    associated with `tag`"))
