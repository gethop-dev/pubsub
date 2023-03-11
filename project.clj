(defproject dev.gethop/pubsub "0.3.7-SNAPSHOT"
  :description "Duct + Integrant library wrapping MQTT & AQMP publish/subcribe clients with a common boundary protocol"
  :url "https://github.com/gethop-dev/pubsub"
  :license {:name "Mozilla Public Licence 2.0"
            :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :min-lein-version "2.9.8"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [clojurewerkz/machine_head "1.0.0"]
                 [com.novemberain/langohr "5.1.0"]
                 [diehard "0.9.4"]
                 [duct/logger "0.3.0"]
                 [integrant "0.8.0"]
                 [org.bouncycastle/bcprov-jdk15on "1.65"]
                 [org.bouncycastle/bcpkix-jdk15on "1.65"]]
  :deploy-repositories [["snapshots" {:url "https://clojars.org/repo"
                                      :username :env/CLOJARS_USERNAME
                                      :password :env/CLOJARS_PASSWORD
                                      :sign-releases false}]
                        ["releases"  {:url "https://clojars.org/repo"
                                      :username :env/CLOJARS_USERNAME
                                      :password :env/CLOJARS_PASSWORD
                                      :sign-releases false}]]
  :profiles
  {:dev [:project/dev :profiles/dev]
   :repl {:repl-options {:host "0.0.0.0"
                         :port 4001}}
   :profiles/dev {}
   :project/dev {:plugins [[jonase/eastwood "1.2.3"]
                           [lein-cljfmt "0.8.0"]]
                 :dependencies [[com.taoensso/nippy "2.14.0"]]}})
