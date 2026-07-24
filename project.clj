(defproject dev.gethop/pubsub "0.4.3"
  :description "Duct + Integrant library wrapping MQTT & AQMP publish/subcribe clients with a common boundary protocol"
  :url "https://github.com/gethop-dev/pubsub"
  :license {:name "Mozilla Public Licence 2.0"
            :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :min-lein-version "2.9.8"
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [clojurewerkz/machine_head "1.0.0"]
                 [com.novemberain/langohr "5.6.0"]
                 [diehard/diehard "0.12.1"]
                 [duct/logger "0.3.0"]
                 [integrant/integrant "0.8.0"]
                 [org.bouncycastle/bcprov-jdk15on "1.70"]
                 [org.bouncycastle/bcpkix-jdk15on "1.70"]]
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
   :project/dev {:plugins [[jonase/eastwood "1.4.3"]
                           [dev.weavejester/lein-cljfmt "0.16.5"]]
                 :dependencies [[com.taoensso/nippy "3.6.2"]]}})
