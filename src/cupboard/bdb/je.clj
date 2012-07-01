(ns cupboard.bdb.je
  (:require [clojure.java.io :as io])
  (:use cupboard.utils cupboard.bdb.je-marshal)
  (:import [com.sleepycat.je DatabaseException DatabaseEntry LockMode CacheMode]
           [com.sleepycat.je CheckpointConfig StatsConfig VerifyConfig]
           [com.sleepycat.je Environment EnvironmentConfig EnvironmentMutableConfig]
           [com.sleepycat.je Transaction TransactionConfig]
           [com.sleepycat.je Database DatabaseConfig]
           [com.sleepycat.je Cursor SecondaryCursor JoinCursor CursorConfig JoinConfig]
           [com.sleepycat.je SecondaryDatabase SecondaryConfig SecondaryKeyCreator]))



;;; ----------------------------------------------------------------------------
;;; useful structs
;;; ----------------------------------------------------------------------------

(defstruct db-env
  :dir
  :transactional
  :env-handle)


(defstruct txn
  :status
  :txn-handle)


(defstruct db
  :name
  :sorted-duplicates
  :db-handle)


(defstruct db-sec
  :name
  :key-creator-fn
  :sorted-duplicates
  :db-sec-handle)


(defstruct db-cursor
  :db
  :cursor-handle)


(defstruct db-join-cursor
  :cursors
  :join-cursor-handle)


(defn db-primary?
  "Returns true if the given struct represents a primary database."
  [s]
  (and (contains? s :db-handle)
       (= Database (class @(:db-handle s)))))


(defn db-secondary?
  "Returns true if the given struct represents a primary database."
  [s]
  (and (contains? s :db-sec-handle)
       (= SecondaryDatabase (class @(:db-sec-handle s)))))


(defn db-cursor-primary?
  "Returns true if the given struct represents a primary database cursor."
  [s]
  (= Cursor (class @(:cursor-handle s))))


(defn db-cursor-sec?
  "Returns true if the given struct represents a secondary database cursor."
  [s]
  (= SecondaryCursor (class @(:cursor-handle s))))



;;; ----------------------------------------------------------------------------
;;; convenience functions, macros, and maps
;;; ----------------------------------------------------------------------------

(defmacro def-with-db-macro [macro-name open-fn close-fn]
  `(defmacro ~macro-name [[var# & open-args#] & body#]
     `(let [~var# (apply ~~open-fn [~@open-args#])]
        (try
         ~@body#
         (finally (~~close-fn ~var#))))))


(defonce ^:dynamic *lock-modes*
  ;; NB: :serializable is not available here, as it does not make sense outside
  ;; a transaction.
  {:read-uncommitted LockMode/READ_UNCOMMITTED
   :dirty-read LockMode/READ_UNCOMMITTED
   :read-committed LockMode/READ_COMMITTED
   :default LockMode/DEFAULT
   :repeatable-read LockMode/DEFAULT
   :rmw LockMode/RMW
   :read-modify-write LockMode/RMW})


(defn db-*-verify-conf-obj [conf-args]
  (let [defaults {:output-stream System/out
                  :propagate-exceptions false
                  :aggressive false
                  :print-info false
                  :show-progress false}
        conf (merge defaults (args-map conf-args))
        conf-obj (doto (VerifyConfig.)
                   (.setPropagateExceptions (:propagate-exceptions conf))
                   (.setAggressive (:aggressive conf))
                   (.setPrintInfo (:print-info conf))
                   (.setShowProgressStream (:output-stream conf)))]
    [conf conf-obj]))



;;; ----------------------------------------------------------------------------
;;; database environments
;;; ----------------------------------------------------------------------------

(defn- db-env-set-mutable-opts
  "conf: a map of options
   conf-obj: EnvironmentConfig or EnvironmentMutableConfig"
  [conf conf-obj]
  (when (contains? conf :cache-percent)
    (.setCachePercent conf-obj (:cache-percent conf)))
  (when (contains? conf :cache-bytes)
    (.setCacheSize conf-obj (:cache-bytes conf)))
  (when (contains? conf :txn-no-sync)
    (.setTxnNoSync conf-obj (:txn-no-sync conf)))
  (when (contains? conf :txn-write-no-sync)
    (.setTxnWriteNoSync conf-obj (:txn-write-no-sync conf)))
  ;; CHECKPOINTER_HIGH_PRIORITY helps with large-cache, high-write-rate
  ;; environments
  ;; http://blogs.oracle.com/charlesLamb/2009/05/berkeley_db_java_edition_clean.html
  ;; May result in a cleaner backlog, consider using along with a higher
  ;; CLEANER_THREADS count.
  (when (contains? conf :checkpointer-high-priority)
    (.setConfigParam conf-obj EnvironmentConfig/CHECKPOINTER_HIGH_PRIORITY
                     (str (:checkpointer-high-priority conf))))
  (when (contains? conf :n-cleaner-threads)
    (.setConfigParam conf-obj EnvironmentConfig/CLEANER_THREADS
                     (str (:n-cleaner-threads conf))))
  ;; TXN_DEADLOCK_STACK_TRACE causes deadlock messages to also print a stack trace
  ;; http://www.oracle.com/technology/products/berkeley-db/faq/je_faq.html#23
  (when (contains? conf :txn-deadlock-stack-trace)
    (.setConfigParam conf-obj EnvironmentConfig/TXN_DEADLOCK_STACK_TRACE
                     (str (:txn-deadlock-stack-trace conf))))
  ;; TXN_DUMP_LOCKS causes deadlock messages to also print the full lock table
  ;; http://www.oracle.com/technology/products/berkeley-db/faq/je_faq.html#23
  (when (contains? conf :txn-dump-locks)
    (.setConfigParam conf-obj EnvironmentConfig/TXN_DUMP_LOCKS
                     (str (:txn-dump-locks conf))))
  ;; ENV_RUN_CHECKPOINTER determines if the checkpointer thread runs
  (when (contains? conf :run-checkpointer)
    (.setConfigParam conf-obj EnvironmentConfig/ENV_RUN_CHECKPOINTER
                     (str (:run-checkpointer conf))))
  ;; ENV_RUN_CLEANER determines if the checkpointer thread runs
  (when (contains? conf :run-cleaner)
    (.setConfigParam conf-obj EnvironmentConfig/ENV_RUN_CLEANER
                     (str (:run-cleaner conf))))
  ;; ENV_RUN_IN_COMPRESSOR determines if the compressor thread runs (removes
  ;; empty subtrees from the database)
  (when (contains? conf :run-compressor)
    (.setConfigParam conf-obj EnvironmentConfig/ENV_RUN_IN_COMPRESSOR
                     (str (:run-compressor conf)))))


(defn db-env-open [dir & conf-args]
  (let [defaults {:allow-create false
                  :read-only false
                  :transactional false
                  :shared-cache false
                  :lock-timeout-msec 500
                  :txn-timeout-msec 0
                  :txn-no-sync false
                  :txn-write-no-sync false
                  :txn-serializable-isolation false}
        #^java.io.File dir (io/file dir)
        conf (merge defaults (args-map conf-args))
        conf-obj (doto (EnvironmentConfig.)
                   (.setAllowCreate (:allow-create conf))
                   (.setReadOnly (:read-only conf))
                   (.setTransactional (:transactional conf))
                   (.setSharedCache (:shared-cache conf))
                   (.setLockTimeout (long (* 1000 (:lock-timeout-msec conf))))
                   (.setTxnTimeout (long (* 1000 (:txn-timeout-msec conf))))
                   (.setTxnSerializableIsolation (:txn-serializable-isolation conf)))]
    ;; process immutable configuration parameters
    (when (contains? conf :db-log-max-bytes)
      (.setConfigParam conf-obj EnvironmentConfig/LOG_FILE_MAX (str (:db-log-max-bytes conf))))
    (when (contains? conf :in-memory-only)
      (.setConfigParam conf-obj EnvironmentConfig/LOG_MEM_ONLY (str (:in-memory-only conf))))
    ;; LOG_USE_ODSYNC helps with environments stored on network volumes, see
    ;; http://www.oracle.com/technology/products/berkeley-db/faq/je_faq.html#1
    ;; http://blogs.oracle.com/charlesLamb/2009/05/berkeley_db_java_edition_clean.html
    (when (contains? conf :db-log-use-odsync)
      (.setConfigParam conf-obj EnvironmentConfig/LOG_USE_ODSYNC
                       (str (:db-log-use-odsync conf))))
    ;; process mutable configuration parameters (shared with db-env-modify)
    (db-env-set-mutable-opts conf conf-obj)
    ;; make the environment area and open it
    (when-not (.exists dir) (.mkdir dir))
    (struct db-env
            dir
            (:transactional conf)
            (atom (Environment. dir conf-obj)))))


(defn db-env-close [db-env]
  (try
   (let [#^Environment env-handle @(:env-handle db-env)]
     (.close env-handle))
   (finally (reset! (:env-handle db-env) nil))))


(def-with-db-macro with-db-env `db-env-open `db-env-close)


(defn db-env-modify [db-env & opts-args]
  (let [opts (args-map opts-args)
        #^Environment env-handle @(:env-handle db-env)
        #^EnvironmentMutableConfig conf-obj (.getMutableConfig env-handle)]
    (db-env-set-mutable-opts opts conf-obj)
    (.setMutableConfig env-handle conf-obj)))


(defn db-env-remove-db [db-env db-name & opts-args]
  (let [defaults {:txn nil}
        opts (merge defaults (args-map opts-args))
        #^Environment env-handle @(:env-handle db-env)]
    (.removeDatabase env-handle (deref* (-> opts :txn :txn-handle)) db-name)))


(defn db-env-rename-db [db-env old-name new-name & opts-args]
  (let [defaults {:txn nil}
        opts (merge defaults (args-map opts-args))
        #^Environment env-handle @(:env-handle db-env)]
    (.renameDatabase env-handle (deref* (-> opts :txn :txn-handle))
                     old-name new-name)))


(defn db-env-truncate-db [db-env db-name & opts-args]
  (let [defaults {:txn nil
                  :count false}
        opts (merge defaults (args-map opts-args))
        #^Environment env-handle @(:env-handle db-env)]
    (.truncateDatabase env-handle (deref* (-> opts :txn :txn-handle))
                       db-name (opts :count))))


(defn db-env-sync [db-env]
  (.sync #^Environment @(:env-handle db-env)))


(defn db-env-checkpoint [db-env & opts]
  (let [opts (args-map opts)
        #^CheckpointConfig cpc (when-not (empty? opts) (CheckpointConfig.))
        #^Environment env-handle @(:env-handle db-env)]
    (when (opts :force) (.setForce cpc true))
    (when (contains? opts :threshold-kbytes) (.setKBytes cpc (opts :threshold-kbytes)))
    (when (contains? opts :threshold-mins) (.setMinutes cpc (opts :threshold-mins)))
    (when (opts :minimize-recovery-time) (.setMinimizeRecoveryTime cpc true))
    (.checkpoint env-handle cpc)))


(defn db-env-clean-log
  "Cleans database log files and prepares them for disposal at next checkpoint.
   Normally done by background thread. Returns number of log files cleaned. May
   be called repeatedly until it returns 0."
  [db-env]
  (.cleanLog #^Environment @(:env-handle db-env)))


(defn db-env-evict-memory
  "Keeps memory usage within defined cache boundaries. Normally done by
   background thread."
  [db-env]
  (.evictMemory #^Environment @(:env-handle db-env)))


(defn db-env-compress
  "Compresses in-memory data structures after deletes. Normally done by
   background thread."
  [db-env]
  (.compress #^Environment @(:env-handle db-env)))


(defn db-env-verify
  "Runs the expensive environment verification routine."
  [db-env & conf-args]
  (let [[conf conf-obj] (db-*-verify-conf-obj conf-args)
        #^Environment env-handle @(:env-handle db-env)]
    (.verify env-handle conf-obj (:output-stream conf))))


(defn db-env-stats [db-env & conf-args]
  (let [defaults {:reset-stats false
                  ;; environment stats
                  :n-cache-bytes false
                  :n-cache-misses false
                  :n-fsyncs false
                  :n-random-reads false
                  :n-random-writes false
                  :n-seq-reads false
                  :n-seq-writes false
                  :n-total-log-bytes false
                  ;; transaction stats
                  :n-txn-active false
                  :n-txn-begins false
                  :n-txn-aborts false
                  :n-txn-commits false
                  ;; lock stats (slow mode!)
                  :n-lock-owners false
                  :n-read-locks false
                  :n-total-locks false
                  :n-lock-waiting-txns false
                  :n-write-locks false
                  ;; lock stats (fast mode)
                  :n-lock-requests false
                  :n-lock-waits false}
        conf (merge defaults (args-map conf-args))
        #^StatsConfig conf-obj (StatsConfig.)
        #^Environment env-handle @(:env-handle db-env)
        env-stats [:n-cache-bytes :n-cache-misses :n-fsyncs :n-random-reads
                   :n-random-writes :n-seq-reads :n-seq-writes :n-total-log-bytes]
        txn-stats [:n-txn-active :n-txn-begins :n-txn-aborts :n-txn-commits]
        lock-stats-slow [:n-lock-owners :n-read-locks :n-total-locks
                         :n-lock-waiting-txns :n-write-locks]
        lock-stats-fast [:n-lock-requests :n-lock-waits]
        result (atom {})]
    ;; enable fast statistics when possible
    (.setFast conf-obj (any? identity (vals (select-keys conf lock-stats-slow))))
    ;; reset statistics afterwards if requested
    (.setClear conf-obj (:reset-stats conf))
    ;; gather all requested statistics...
    (letfn [(add-result [k f stat-obj]
              (when (get conf k) (swap! result assoc k (f stat-obj))))]
      ;; gather environment statistics if any requested
      (when (any? identity (vals (select-keys conf env-stats)))
        (let [#^EnvironmentStats env-stat-obj (.getStats env-handle conf-obj)]
          (add-result :n-cache-bytes #(.getCacheTotalBytes %) env-stat-obj)
          (add-result :n-cache-misses #(.getNCacheMiss %) env-stat-obj)
          (add-result :n-fsyncs #(.getNFSyncs %) env-stat-obj)
          (add-result :n-random-reads #(.getNRandomReads %) env-stat-obj)
          (add-result :n-random-writes #(.getNRandomWrites %) env-stat-obj)
          (add-result :n-seq-reads #(.getNSequentialReads %) env-stat-obj)
          (add-result :n-seq-writes #(.getNSequentialWrites %) env-stat-obj)
          (add-result :n-total-log-bytes #(.getTotalLogSize %) env-stat-obj)))
      ;; gather transaction statistics if any requested
      (when (any? identity (vals (select-keys conf txn-stats)))
        (let [#^TransactionStats txn-stat-obj (.getTransactionStats env-handle conf-obj)]
          (add-result :n-txn-active #(.getNActive %) txn-stat-obj)
          (add-result :n-txn-begins #(.getNBegins %) txn-stat-obj)
          (add-result :n-txn-aborts #(.getNAborts %) txn-stat-obj)
          (add-result :n-txn-commits #(.getNCommits %) txn-stat-obj)))
      ;; gather lock statistics if any requested
      (when (any? identity
                  (vals (select-keys
                         conf (concat lock-stats-slow lock-stats-fast))))
        (let [#^LockStats lock-stat-obj (.getLockStats env-handle conf-obj)]
          (add-result :n-lock-owners #(.getNOwners %) lock-stat-obj)
          (add-result :n-read-locks #(.getNReadLocks %) lock-stat-obj)
          (add-result :n-total-locks #(.getNTotalLocks %) lock-stat-obj)
          (add-result :n-lock-waiting-txns #(.getNWaiters %) lock-stat-obj)
          (add-result :n-write-locks #(.getNWriteLocks %) lock-stat-obj)
          (add-result :n-lock-requests #(.getNRequests %) lock-stat-obj)
          (add-result :n-lock-waits #(.getNWaits %) lock-stat-obj))))
    ;; ...and return them
    @result))



;;; ----------------------------------------------------------------------------
;;; transactions
;;; ----------------------------------------------------------------------------

(defn db-txn-begin [db-env & conf-args]
  (let [defaults {:txn nil              ; parent transaction; not supported in JE
                  :isolation :repeatable-read}
        conf (merge defaults (args-map conf-args))
        conf-obj (let [co (TransactionConfig.)]
                   (when (contains? conf :no-sync)
                     (.setNoSync co (:no-sync conf)))
                   (when (contains? conf :write-no-sync)
                     (.setWriteNoSync co (:write-no-sync conf)))
                   (when (contains? conf :no-wait)
                     (.setNoWait co (:no-wait conf)))
                   (cond
                     (= (:isolation conf) :read-uncommitted) (.setReadUncommitted co true)
                     (= (:isolation conf) :read-committed) (.setReadCommitted co true)
                     (= (:isolation conf) :serializable) (.setSerializableIsolation co true))
                   co)
        #^Environment env-handle @(:env-handle db-env)
        txn-obj (.beginTransaction env-handle
                                   (deref* (-> conf :txn :txn-handle))
                                   conf-obj)]
    ;; a little more configuration goes here
    (when (contains? conf :lock-timeout-msec)
      (.setLockTimeout txn-obj (long (* 1000 (:lock-timeout-msec conf)))))
    (when (contains? conf :txn-timeout-msec)
      (.setTxnTimeout txn-obj (long (* 1000 (:txn-timeout-msec conf)))))
    ;; finally return the transaction
    (struct txn
            (atom :open)
            (atom txn-obj))))


(defn db-txn-commit [txn & conf-args]
  (let [defaults {:no-sync false
                  :write-no-sync false}
        conf (merge defaults (args-map conf-args))
        #^Transaction txn-handle @(:txn-handle txn)]
    (try
     (cond (:no-sync conf) (.commitNoSync txn-handle)
           (:write-no-sync conf) (.commitWriteNoSync txn-handle)
           :else (.commit txn-handle))
     (reset! (:status txn) :committed)
     (catch DatabaseException de
       (reset! (:status txn) de)
       (throw de))
     (finally (reset! (:txn-handle txn) nil)))))


(defn db-txn-abort [txn]
  (let [#^Transaction txn-handle @(:txn-handle txn)]
    (try
     (.abort txn-handle)
     (reset! (:status txn) :aborted)
     (catch DatabaseException de
       (reset! (:status txn) de)
       (throw de))
     (finally (reset! (:txn-handle txn) nil)))))


(def-with-db-macro with-db-txn `db-txn-begin
  `(fn [txn#] (when (= @(:status txn#) :open) (db-txn-commit txn#))))



;;; ----------------------------------------------------------------------------
;;; primary databases
;;; ----------------------------------------------------------------------------

;; TODO: Add support for setting :btree-comparator and :duplicate-comparator
;; TODO: Add support for overriding :btree-comparator and :duplicate-comparator
;; (DatabaseConfig.setOverrideBtreeCompatator(), etc.)
(defn db-open [db-env name & conf-args]
  (let [defaults {:txn nil
                  :allow-create false
                  :deferred-write false
                  :temporary false
                  :sorted-duplicates false
                  :exclusive-create false
                  :transactional (:transactional db-env)}
        #^Environment env-handle @(:env-handle db-env)
        conf (merge defaults
                    {:read-only (.. env-handle getConfig getReadOnly)}
                    (args-map conf-args))
        conf-obj (doto (DatabaseConfig.)
                   (.setAllowCreate (:allow-create conf))
                   (.setDeferredWrite (:deferred-write conf))
                   (.setSortedDuplicates (:sorted-duplicates conf))
                   (.setExclusiveCreate (:exclusive-create conf))
                   (.setReadOnly (:read-only conf))
                   (.setTransactional (:transactional conf)))]
    (struct db
            name
            (:sorted-duplicates conf)
            (atom (.openDatabase env-handle
                                 (deref* (-> conf :txn :txn-handle))
                                 name conf-obj)))))


(defn db-close [db]
  (try
   (.close #^Database @(:db-handle db))
   (finally (reset! (:db-handle db) nil))))


(def-with-db-macro with-db `db-open `db-close)


(defn db-sync [db]
  (let [#^Database db-handle @(:db-handle db)]
    (when (.. db-handle getConfig getDeferredWrite)
      (.sync db-handle))))


(defn db-put
  "Optional keyword arguments:
     :no-dup-data  --- if true, then calls .putNoDupData
     :no-overwrite --- if true, then calls .putNoOverwrite"
  [db key data & opts-args]
  (let [defaults {:txn nil}
        opts (merge defaults (args-map opts-args))
        key-entry (marshal-db-entry key)
        data-entry (marshal-db-entry data)
        #^Database db-handle @(:db-handle db)]
    (cond (opts :no-dup-data) (.putNoDupData db-handle
                                             (deref* (-> opts :txn :txn-handle))
                                             key-entry data-entry)
          (opts :no-overwrite) (.putNoOverwrite db-handle
                                                (deref* (-> opts :txn :txn-handle))
                                                key-entry data-entry)
          :else (.put db-handle
                      (deref* (-> opts :txn :txn-handle)) key-entry data-entry))))


(defn db-get
  "Optional keyword arguments:
     :search-both --- uses Database.getSearchBoth with data specified in :data
     :data        --- if specified, can recycle DatabaseEntry; also used for getSearchBoth"
  [db key & opts-args]
  (let [defaults {:txn nil
                  :search-both false
                  :lock-mode :default}
        opts (merge defaults (args-map opts-args))
        key-entry (marshal-db-entry key)
        data-entry (marshal-db-entry* opts :data)
        lock-mode (*lock-modes* (opts :lock-mode))
        #^Database db-handle @(:db-handle db)
        result (if (opts :search-both)
                   (.getSearchBoth db-handle
                                   (deref* (-> opts :txn :txn-handle))
                                   key-entry data-entry lock-mode)
                   (.get db-handle
                         (deref* (-> opts :txn :txn-handle))
                         key-entry data-entry lock-mode))]
    (unmarshal-db-entry* result key-entry data-entry)))


(defn db-delete [db key & opts-args]
  (let [defaults {:txn nil}
        opts (merge defaults (args-map opts-args))
        key-entry (marshal-db-entry key)
        #^Database db-handle @(:db-handle db)]
    (.delete db-handle (deref* (-> opts :txn :txn-handle)) key-entry)))


(defn db-count [db]
  (.count #^Database @(:db-handle db)))


(defn db-verify
  "Runs the expensive database verification routine."
  [db & conf-args]
  (let [[conf conf-obj] (db-*-verify-conf-obj conf-args)
        #^Database db-handle @(:db-handle db)]
    (.verify db-handle conf-obj)))



;;; ----------------------------------------------------------------------------
;;; secondary databases (indices)
;;; ----------------------------------------------------------------------------

(defn db-sec-open [db-env db-primary name & conf-args]
  (let [defaults {:txn nil
                  :key-creator-fn first
                  :allow-create false
                  :sorted-duplicates false
                  :allow-populate true
                  :transactional (:transactional db-env)}
        #^Environment env-handle @(:env-handle db-env)
        #^Database db-primary-handle @(db-primary :db-handle)
        conf (merge defaults
                    {:read-only (.. db-primary-handle getConfig getReadOnly)}
                    (args-map conf-args))
        key-creator-fn (:key-creator-fn conf)
        key-creator (proxy [SecondaryKeyCreator] []
                      (createSecondaryKey [_ key-entry data-entry result-entry]
                        (let [data (unmarshal-db-entry data-entry)
                              sec-data (key-creator-fn data)]
                          (if sec-data
                              (do (marshal-db-entry sec-data result-entry)
                                  true)
                              false))))
        conf-obj (doto (SecondaryConfig.)
                   (.setKeyCreator key-creator)
                   (.setAllowCreate (:allow-create conf))
                   (.setSortedDuplicates (:sorted-duplicates conf))
                   (.setAllowPopulate (:allow-populate conf))
                   (.setReadOnly (:read-only conf))
                   (.setTransactional (:transactional conf)))]
    (struct db-sec
            name
            key-creator-fn
            (:sorted-duplicates conf)
            (atom (.openSecondaryDatabase env-handle
                                          (deref* (-> conf :txn :txn-handle))
                                          name db-primary-handle conf-obj)))))


(defn db-sec-close [db-sec]
  (try
   (.close #^SecondaryDatabase @(:db-sec-handle db-sec))
   (finally (reset! (:db-sec-handle db-sec) nil))))


(def-with-db-macro with-db-sec `db-sec-open `db-sec-close)


(defn db-sec-get
  "Optional keyword arguments:
     :key  --- if specified, recycles DatabaseEntry
     :data --- if specified, recycles DatabaseEntry"
  [db-sec search-key & opts-args]
  (let [defaults {:txn nil
                  :lock-mode :default}
        opts (merge defaults (args-map opts-args))
        search-key-entry (marshal-db-entry search-key)
        key-entry (marshal-db-entry* opts :key)
        data-entry (marshal-db-entry* opts :data)
        #^SecondaryDatabase db-sec-handle @(:db-sec-handle db-sec)
        result (.get db-sec-handle
                     (deref* (-> opts :txn :txn-handle))
                     search-key-entry key-entry data-entry
                     (*lock-modes* (opts :lock-mode)))]
    (unmarshal-db-entry* result key-entry data-entry)))


(defn db-sec-delete [db-sec search-key & opts-args]
  (let [defaults {:txn nil}
        opts (merge defaults (args-map opts-args))
        search-entry (marshal-db-entry search-key)
        #^SecondaryDatabase db-sec-handle @(:db-sec-handle db-sec)]
    (.delete db-sec-handle (deref* (-> opts :txn :txn-handle)) search-entry)))


(defn db-sec-verify
  "Runs the expensive secondary database verification routine."
  [db-sec & conf-args]
  (let [[conf conf-obj] (db-*-verify-conf-obj conf-args)
        #^SecondaryDatabase db-sec-handle @(:db-sec-handle db-sec)]
    (.verify db-sec-handle conf-obj)))



;;; ----------------------------------------------------------------------------
;;; database cursors
;;;
;;; This code supports both primary and secondary cursors, and treats them
;;; differently only where absolutely necessary. Calling code should simply pass
;;; the appropriate database into db-cursor-open.
;;; ----------------------------------------------------------------------------

(defn db-cursor-open [db & conf-args]
  (let [defaults {:txn nil
                  :isolation :repeatable-read}
        conf (merge defaults (args-map conf-args))
        conf-obj (let [co (CursorConfig.)]
                   (cond
                     (= (:isolation conf) :read-uncommitted) (.setReadUncommitted co true)
                     (= (:isolation conf) :read-committed) (.setReadCommitted co true)))]
    (struct db-cursor
            db
            (atom
             (if (db-primary? db)
                 (.openCursor #^Database @(:db-handle db)
                              (deref* (-> conf :txn :txn-handle))
                              conf-obj)
                 (.openSecondaryCursor #^SecondaryDatabase @(:db-sec-handle db)
                                       (deref* (-> conf :txn :txn-handle))
                                       conf-obj))))))


(defn db-cursor-close [db-cursor]
  (try
   (.close #^Cursor @(db-cursor :cursor-handle))
   (finally (reset! (db-cursor :cursor-handle) nil))))


(def-with-db-macro with-db-cursor `db-cursor-open `db-cursor-close)


(defn db-cursor-search
  "Optional keyword arguments:
     :search-both --- use Database.getSearchBoth functions
     :pkey        --- for cursors on secondary databases only, specifies the primary key value
     :data        --- if specified, positions the cursor by both key and :data values
     :exact       --- if true, match the key and optional :data exactly"
  [db-cursor key & opts-args]
  (let [defaults {:search-both false
                  :exact false
                  :lock-mode :default}
        opts (merge defaults (args-map opts-args))
        search-both (opts :search-both)
        exact (opts :exact)
        lock-mode (*lock-modes* (opts :lock-mode))
        key-entry (marshal-db-entry key)
        pkey-entry (when (db-cursor-sec? db-cursor) (marshal-db-entry* opts :pkey))
        data-entry (marshal-db-entry* opts :data)
        ;; search-fn1 is for primary database cursor lookups
        search-fn1 (cond
                     (and search-both exact) #(.getSearchBoth #^Cursor %1 %2 %3 %4)
                     search-both #(.getSearchBothRange #^Cursor %1 %2 %3 %4)
                     exact #(.getSearchKey #^Cursor %1 %2 %3 %4)
                     :else #(.getSearchKeyRange #^Cursor %1 %2 %3 %4))
        ;; search-fn2 is for secondary database cursor lookups
        search-fn2 (cond
                     (and search-both exact) #(.getSearchBoth #^SecondaryCursor %1 %2 %3 %4 %5)
                     search-both #(.getSearchBothRange #^SecondaryCursor %1 %2 %3 %4 %5)
                     exact #(.getSearchKey #^SecondaryCursor %1 %2 %3 %4 %5)
                     :else #(.getSearchKeyRange #^SecondaryCursor %1 %2 %3 %4 %5))
        result (if (db-cursor-primary? db-cursor)
                   (search-fn1 @(db-cursor :cursor-handle)
                               key-entry data-entry lock-mode)
                   (search-fn2 @(db-cursor :cursor-handle)
                               key-entry pkey-entry data-entry lock-mode))]
    (unmarshal-db-entry* result
                         (if (db-cursor-primary? db-cursor) key-entry pkey-entry)
                         data-entry)))


(defmacro def-db-cursor-simple-position [name java-fn]
  `(defn ~name
     "Optional keyword arguments:
        :pkey --- for cursors on secondary databases only, specifies the primary key value
        :key  --- if specified, reuses the given DatabaseEntry
        :data --- if specified, reuses the given DatabaseEntry"
     [db-cursor# & opts-args#]
     (let [defaults# {:lock-mode :default}
           opts# (merge defaults# (args-map opts-args#))
           key-entry# (marshal-db-entry* opts# :key)
           pkey-entry# (when (db-cursor-sec? db-cursor#) (marshal-db-entry* opts# :pkey))
           data-entry# (marshal-db-entry* opts# :data)
           lock-mode# (*lock-modes* (opts# :lock-mode))
           result# (if (db-cursor-primary? db-cursor#)
                       (~java-fn #^Cursor @(db-cursor# :cursor-handle)
                                 key-entry# data-entry# lock-mode#)
                       (~java-fn #^SecondaryCursor @(db-cursor# :cursor-handle)
                                 key-entry# pkey-entry# data-entry# lock-mode#))]
       (unmarshal-db-entry* result#
                            (if (db-cursor-primary? db-cursor#) key-entry# pkey-entry#)
                            data-entry#))))

(def-db-cursor-simple-position db-cursor-first .getFirst)
(def-db-cursor-simple-position db-cursor-current .getCurrent)
(def-db-cursor-simple-position db-cursor-last .getLast)


(defn db-cursor-next
  "Optional keyword arguments:
     :key  --- if specified, reuses the given DatabaseEntry
     :data --- if specified, reuses the given DatabaseEntry"
  [db-cursor & opts-args]
  (let [defaults {:direction :forward
                  :skip-dups false
                  :lock-mode :default}
        opts (merge defaults (args-map opts-args))
        direction (opts :direction)
        skip-dups (opts :skip-dups)
        key-entry (marshal-db-entry* opts :key)
        pkey-entry (when (db-cursor-sec? db-cursor) (marshal-db-entry* opts :pkey))
        data-entry (marshal-db-entry* opts :data)
        lock-mode (*lock-modes* (opts :lock-mode))
        ;; next-fn1 is for primary database cursors
        next-fn1 (cond
                   (and (= direction :forward) skip-dups) #(.getNextNoDup #^Cursor %1 %2 %3 %4)
                   (and (= direction :back) skip-dups) #(.getPrevNoDup #^Cursor %1 %2 %3 %4)
                   (= direction :forward) #(.getNext #^Cursor %1 %2 %3 %4)
                   (= direction :back) #(.getPrev #^Cursor %1 %2 %3 %4))
        ;; next-fn2 is for secondary database cursors
        next-fn2 (cond
                   (and (= direction :forward) skip-dups) #(.getNextNoDup
                                                            #^SecondaryCursor %1 %2 %3 %4 %5)
                   (and (= direction :back) skip-dups) #(.getPrevNoDup
                                                         #^SecondaryCursor %1 %2 %3 %4 %5)
                   (= direction :forward) #(.getNext #^SecondaryCursor %1 %2 %3 %4 %5)
                   (= direction :back) #(.getPrev #^SecondaryCursor %1 %2 %3 %4 %5))
        result (if (db-cursor-primary? db-cursor)
                   (next-fn1 #^Cursor @(db-cursor :cursor-handle)
                             key-entry data-entry lock-mode)
                   (next-fn2 #^SecondaryCursor @(db-cursor :cursor-handle)
                             key-entry pkey-entry data-entry lock-mode))]
    (unmarshal-db-entry* result
                         (if (db-cursor-primary? db-cursor) key-entry pkey-entry)
                         data-entry)))


(defn db-cursor-put [db-cursor key data & opts-args]
  (let [opts (args-map opts-args)
        key-entry (marshal-db-entry key)
        data-entry (marshal-db-entry data)]
    (cond (opts :no-dup-data) (.putNoDupData
                               #^Cursor @(db-cursor :cursor-handle)
                               key-entry data-entry)
          (opts :no-overwrite) (.putNoOverwrite
                                #^Cursor @(db-cursor :cursor-handle)
                                key-entry data-entry)
          :else (.put #^Cursor @(db-cursor :cursor-handle) key-entry data-entry))))


(defn db-cursor-delete
  "Deletes the record the cursor currently points to."
  [db-cursor]
  (.delete @(db-cursor :cursor-handle)))


(defn db-cursor-replace
  "Replaces the data entry of the record the cursor currently points to."
  [db-cursor new-data]
  (.putCurrent #^Cursor @(db-cursor :cursor-handle) (marshal-db-entry new-data)))


(defn db-cursor-cache-mode [db-cursor mode]
  (let [mode-obj (cond (= mode :default) CacheMode/DEFAULT
                       (= mode :keep-hot) CacheMode/KEEP_HOT
                       (= mode :unchanged) CacheMode/UNCHANGED
                       :else (throw (RuntimeException. "invalid cursor cache mode")))]
    (.setCacheMode @(db-cursor :cursor-handle) mode-obj)))


(defn db-cursor-scan [db-cursor indexed-value & opts-args]
  (let [defaults {:lock-mode :default
                  :comparison-fn =}
        opts (merge defaults (args-map opts-args))
        lock-mode (opts :lock-mode)
        comparison-fn (opts :comparison-fn)
        direction (if (contains? opts :direction)
                      (opts :direction)
                      (condp = comparison-fn
                        = :forward
                        > :forward
                        >= :forward
                        < :back
                        <= :back
                        starts-with :forward
                        date= :forward
                        date> :forward
                        date>= :forward
                        date< :back
                        date<= :back
                        :forward))
        exact (or (= comparison-fn =) (= comparison-fn date=))
        ;; Use this function to extract the value from the database entry which
        ;; the cursor points to which matters for this scan.
        res-compval-fn (if (db-cursor-primary? db-cursor)
                           (fn [res] (first res))
                           (let [key-creator-fn (-> db-cursor :db :key-creator-fn)]
                             (fn [res] (key-creator-fn (second res)))))]
    (letfn [(scan-to-first []
              (loop [res (db-cursor-search db-cursor indexed-value
                                           :exact exact :lock-mode lock-mode)]
                (when-not (empty? res)
                  (if* (comparison-fn (res-compval-fn res) indexed-value)
                       res
                       (recur (db-cursor-next db-cursor
                                              :direction direction :lock-mode lock-mode))))))
            (scan [prev-res]
              (if (or (empty? prev-res)
                      (not (comparison-fn (res-compval-fn prev-res) indexed-value)))
                  (lazy-seq)
                  (lazy-seq (cons prev-res
                                  (scan (db-cursor-next db-cursor
                                                        :direction direction
                                                        :lock-mode lock-mode))))))]
      (scan (scan-to-first)))))


(defn db-join-cursor-open [db-cursors & conf-args]
  (let [defaults {:no-sort false}
        conf (merge defaults (args-map conf-args))
        conf-obj (doto (JoinConfig.)
                   (.setNoSort (:no-sort conf)))
        #^Database pdb-obj (.getPrimaryDatabase
                            #^SecondaryCursor @((first db-cursors)  :cursor-handle))]
    (struct db-join-cursor
            db-cursors
            (atom (.join pdb-obj
                         (into-array (map (comp deref :cursor-handle) db-cursors))
                         conf-obj)))))


(defn db-join-cursor-close [db-join-cursor]
  (try
   (.close #^JoinCursor @(:join-cursor-handle db-join-cursor))
   (finally (reset! (:join-cursor-handle db-join-cursor) nil))))


(def-with-db-macro with-db-join-cursor `db-join-cursor-open `db-join-cursor-close)


(defn db-join-cursor-next [db-join-cursor & opts-args]
  (let [defaults {:lock-mode :default}
        opts (merge defaults (args-map opts-args))
        key-entry (marshal-db-entry* opts :key)
        data-entry (marshal-db-entry* opts :data)
        result (.getNext
                #^JoinCursor @(:join-cursor-handle db-join-cursor)
                key-entry data-entry (*lock-modes* (opts :lock-mode)))]
    (unmarshal-db-entry* result key-entry data-entry)))
