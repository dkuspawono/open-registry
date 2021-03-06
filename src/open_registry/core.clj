(ns open-registry.core
  (:require [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [ipfs-api.files :refer [write read path-exists? ls]]
            [npm-registry-follow.core :refer [poll-for-changes]]
            [open-registry.http :as http]
            [open-registry.metrics :as metrics]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn setup-exception-handler []
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (log/error ex "Uncaught exception on" (.getName thread))))))

(defn handle-change-error [ex]
  (log/error ex))

(defn handle-change [package-name]
  (if (:error? package-name)
    (handle-change-error (:exception package-name))
    ;; handle error
    (let [path (format "/npmjs.org/%s/metadata.json" package-name)
          exists? (path-exists? http/api-multiaddr path)]
      (log/infof "[received update] %s" package-name)
      (when exists?
        ;; TODO seems sometimes npm are not up-to-date with their own registry
        ;; as too quick requests can give us old information, even though the
        ;; replication server told us there was a change
        (http/metadata-handler package-name true))
      (if exists?
        (future (metrics/increase :app/change-feed-update))
        (future (metrics/increase :app/change-feed-skip))))))

;; Listens for changes to the npm registry and updates the metadata for
;; packages that already exists in our cache
(defn update-metadata-for-existing-packages []
  (log/info "Now listening for npm registry changes")
  (poll-for-changes handle-change))

(defn is-scoped? [pkg-name]
  (= (get pkg-name 0) \@))

;; Gets all currently installed packages
(defn get-all-installed-packages
  ([]
   (get-all-installed-packages "/npmjs.org"))
  ([directory]
   (let [packages (ls http/api-multiaddr directory)
         to-return (atom [])]
     (doseq [package packages
             :let [pkg-name (:name package)
                   full-path (str directory "/" pkg-name)]]
       (if (is-scoped? pkg-name)
         (let [pkgs (get-all-installed-packages full-path)]
           (doseq [pkg pkgs]
             (swap! to-return conj (str pkg-name "/" pkg))))
         (swap! to-return conj pkg-name)))
     @to-return)))

;; runs through ALL currently installed packages and forces an update
(defn update-all-packages []
  (let [pkgs (get-all-installed-packages)
        updated (atom 0)
        t (count pkgs)]
    (doseq [pkg pkgs]
      (http/metadata-handler pkg true)
      (swap! updated inc)
      (log/infof "[boot-update] %s/%s" @updated t))
    (log/info "[boot-update] done!")))

(comment
  (get-all-installed-packages)
  (update-all-packages))

(defn -main
  [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "5432"))
        metrics-port (Integer/parseInt (or (System/getenv "METRICS_PORT") "2345"))
        threads (Integer/parseInt (or (System/getenv "SERVER_THREADS") "128"))
        in-dev? (Boolean/parseBoolean (or (System/getenv "SERVER_DEV") "false"))
        update-on-boot? (Boolean/parseBoolean (or (System/getenv "UPDATE_ON_BOOT") "false"))]
    (setup-exception-handler)
    ;; TODO should schedule this once a day or something, to make sure we don't
    ;; miss updates from the polling/streaming
    (when update-on-boot?
      (future (update-all-packages)))
    (update-metadata-for-existing-packages)
    (http/start-server port threads in-dev?)
    (metrics/start-server metrics-port)
  ))

(comment
  (println "hello world")
  (future
    (http/start-server 5432 8)
    )
  
  )
