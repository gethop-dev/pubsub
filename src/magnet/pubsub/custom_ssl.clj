;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/

(ns magnet.pubsub.custom-ssl
  "Based on Java code from
  http://web.archive.org/web/20190207161102/https://gist.github.com/jimrok/d25cb45b840f5a4ad700
  and https://stackoverflow.com/a/18161536"
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s])
  (:import [java.security KeyStore Security]
           [java.security.cert Certificate CertificateFactory]
           [javax.net.ssl KeyManagerFactory SSLContext TrustManagerFactory]
           [org.bouncycastle.jce.provider BouncyCastleProvider]
           [org.bouncycastle.openssl PEMEncryptedKeyPair PEMKeyPair PEMParser]
           [org.bouncycastle.openssl.jcajce JcaPEMKeyConverter JcePEMDecryptorProviderBuilder]))

(def ^:const default-tls-version
  "TLSv1.2")

(defn- pem-crt-to-keystore
  "Read one (or more) certificates in PEM format from a file.
  Return a native Java keystore with the certificate(s) in it."
  [crt-file alias-prefix]
  (try
    (with-open [is (io/input-stream (io/file crt-file))]
      (let [certs (-> (. CertificateFactory getInstance "X.509")
                      (.generateCertificates is))
            aliases-certs (map-indexed (fn [idx itm] [(str alias-prefix "." idx) itm]) certs)
            keystore  (doto (->> (. KeyStore getDefaultType)
                                 (. KeyStore getInstance))
                        (.load nil nil))]
        (doseq [[alias cert] aliases-certs]
          (doto keystore
            (.setCertificateEntry alias cert)))
        keystore))
    (catch java.io.FileNotFoundException e
      (throw (ex-info "crt-file-not-found" {:crt-file crt-file})))
    (catch java.security.cert.CertificateParsingException e
      (throw (ex-info "invalid-crt-file" {:crt-file crt-file})))))

(defn- custom-trust-manager
  "Create a custom trust manager, containing the certificate(s) stored in `ca-crt-file`"
  [{:keys [ca-crt-file]}]
  (when ca-crt-file
    (let [keystore (pem-crt-to-keystore ca-crt-file "ca-certificate")
          trust-manager-factory (. TrustManagerFactory getInstance "X509")]
      (.init trust-manager-factory keystore)
      (.getTrustManagers trust-manager-factory))))

(defn- raw-key-to-private-key
  "Create a private key in native Java format, from a raw private key object."
  [raw-key password]
  (let [key-converter (-> (JcaPEMKeyConverter.)
                          (.setProvider "BC"))]
    (cond
      (instance? PEMEncryptedKeyPair raw-key)
      (try
        (let [decrypt-provider (-> (JcePEMDecryptorProviderBuilder.)
                                   (.build password))
              private-keyinfo (-> (.decryptKeyPair raw-key decrypt-provider)
                                  (.getPrivateKeyInfo))]
          (.getPrivateKey key-converter private-keyinfo))
        (catch org.bouncycastle.openssl.PEMException e
          (throw (ex-info "invalid-or-missing-key-password" {:reason :invalid-password})))
        (catch org.bouncycastle.openssl.EncryptionException e
          (throw (ex-info "invalid-or-missing-key-password" {:reason :invalid-password}))))

      (instance? PEMKeyPair raw-key)
      (.getPrivateKey key-converter (.getPrivateKeyInfo raw-key))

      true
      (throw (ex-info "invalid-key" {:reason :invalid-key})))))

(defn- pem-key-to-keystore
  "Read a private key in PEM format from a file (optionally encrypted with a password).
  Add the key to the received native Java keystore, and return the updated keystore."
  [key-file password keystore cert-alias]
  (try
    (with-open [key-reader (io/reader (io/file key-file))]
      (let [;; Add BouncyCastle as a security provider, so we can use its
            ;; PKCS#1 and PKCS#8 PEM readers and converters to load private keys.
            _ (. Security addProvider (BouncyCastleProvider.))
            pem-parser (PEMParser. key-reader)
            raw-key (.readObject pem-parser)
            _ (.close pem-parser)
            private-key (raw-key-to-private-key raw-key password)
            cert (.getCertificate keystore cert-alias)]
        (doto keystore
          (.setKeyEntry "private-key" private-key password (into-array Certificate [cert])))))
    (catch java.io.FileNotFoundException e
      (throw (ex-info "key-file-not-found" {:key-file key-file})))
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info "invalid-key-file" {:key-file key-file
                                          :details (ex-data e)})))))

(defn- custom-key-manager
  "Create a custom key manager, containing the cert and private key stored in `crt-file` and `key-file`
  If the private key is encrypted, use `key-password` to decrypt it."
  [{:keys [crt-file key-file key-password] :as ssl-config}]
  (when (and crt-file key-file)
    (let [keystore (pem-crt-to-keystore crt-file "certificate")
          password (char-array key-password)
          keystore (pem-key-to-keystore key-file password keystore "certificate.0")
          key-manager-factory (doto (->> (. KeyManagerFactory getDefaultAlgorithm)
                                         (. KeyManagerFactory getInstance))
                                (.init keystore password))]
      (.getKeyManagers key-manager-factory))))

(s/def ::path-or-file (s/or :string string?
                            :URL #(instance? java.net.URL %)
                            :URI #(instance? java.net.URI %)
                            :file #(instance? java.io.File %)))
(s/def ::tls-version (s/nilable string?))
(s/def ::ca-crt-file (s/nilable ::path-or-file))
(s/def ::crt-file (s/nilable ::path-or-file))
(s/def ::key-file (s/nilable ::path-or-file))
(s/def ::key-password (s/nilable string?))
(s/def ::ssl-config (s/keys :opt-un [::tls-version ::ca-crt-file ::crt-file ::key-file ::key-password]))

(defn custom-ssl-context
  "Creata a custom SSLContext using the certificates and keys passed in.
  Use it when you need to use custom (e.g., self-signed) certificates
  for a SSL connection."
  [{:keys [tls-version
           ca-crt-file
           crt-file
           key-file
           key-password]
    :or {tls-version default-tls-version} :as ssl-config}]
  (try
    (let [ssl-context (. SSLContext getInstance tls-version)
          trust-manager (custom-trust-manager ssl-config)
          key-manager (custom-key-manager ssl-config)]
      (.init ssl-context key-manager trust-manager nil)
      ssl-context)
    (catch java.security.NoSuchAlgorithmException e
      (throw (ex-info "invalid-tls-version" {:reason :invalid-tls-version})))))

(s/def ::custom-ssl-context-args (s/cat :ssl-config ::ssl-config))
(s/fdef custom-ssl-context
  :args ::custom-ssl-context-args)

(defn custom-ssl-socket-factory
  "Creata a custom SSLSocketFactory using the certificates and keys passed in.
  Use it when you need to use custom (e.g., self-signed) certificates
  for a SSL connection."
  [ssl-config]
  (let [ssl-context (custom-ssl-context ssl-config)]
    (.getSocketFactory ssl-context)))

(s/def ::custom-ssl-socket-factory-args (s/cat :ssl-config ::ssl-config))
(s/fdef custom-ssl-socket-factory
  :args ::custom-ssl-socket-factory-args)

(defn custom-ssl-engine
  "Creata a custom SSLEngine using the certificates and keys passed in.
  Use it when you need to use custom (e.g., self-signed) certificates
  for a SSL connection."
  [ssl-config]
  (let [ssl-context (custom-ssl-context ssl-config)]
    (.createSSLEngine ssl-context)))

(s/def ::custom-ssl-engine-args (s/cat :ssl-config ::ssl-config))
(s/fdef custom-ssl-engine
  :args ::custom-ssl-engine-args)
