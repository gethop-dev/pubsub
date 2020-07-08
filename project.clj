(defproject magnet/pubsub "0.3.4-SNAPSHOT"
  :description "Duct + Integrant library wrapping MQTT & AQMP publish/subcribe clients with a common boundary protocol"
  :url "https://github.com/magnetcoop/pubsub"
  :license {:name "Mozilla Public Licence 2.0"
            :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :min-lein-version "2.9.0"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [clojurewerkz/machine_head "1.0.0"]
                 [com.novemberain/langohr "5.1.0"]
                 [diehard "0.9.4"]
                 [duct/logger "0.3.0"]
                 [integrant "0.8.0"]
                 [org.bouncycastle/bcprov-jdk15on "1.65"]
                 [org.bouncycastle/bcpkix-jdk15on "1.65"]]
  :deploy-repositories [["snapshots" {:url "https://clojars.org/repo"
                                      :username :env/clojars_username
                                      :password :env/clojars_password
                                      :sign-releases false}]
                        ["releases"  {:url "https://clojars.org/repo"
                                      :username :env/clojars_username
                                      :password :env/clojars_password
                                      :sign-releases false}]]
  :profiles
  {:dev [:project/dev :profiles/dev]
   :repl {:repl-options {:host "0.0.0.0"
                         :port 4001}}
   :profiles/dev {}
   :project/dev {:plugins [[jonase/eastwood "0.3.11"]
                           [lein-cljfmt "0.6.7"]]
                 :dependencies [[com.taoensso/nippy "2.14.0"]]}})
