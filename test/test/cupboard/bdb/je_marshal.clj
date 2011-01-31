(ns test.cupboard.bdb.je-marshal
  (:use [clojure test])
  (:use cupboard.bdb.je-marshal)
  (:import [org.joda.time DateTime LocalDate LocalTime LocalDateTime DateTimeZone])
  (:import [com.sleepycat.je DatabaseEntry OperationStatus]))


(deftest type-marshaling
  (let [tnil     nil
        tboolean true
        tchar    \c
        tbyte    (byte 1)
        tshort   (short 1)
        tint     (int 1)
        tlong    (long 1)
        tbigint  (bigint 1)
        tratio   (/ 1 2)
	tfloat   (float 1)
        tdouble  1.0
        tstring  "hello world"
        tdate    (java.util.Date.)
        jt-dt    (DateTime.)
        jt-ld    (LocalDate.)
        jt-lt    (LocalTime.)
        jt-ldt   (LocalDateTime.)
        tuuid    (java.util.UUID/randomUUID)
        tkeyword :one
        tsymbol  'one
        tlist    (list 1 2 3)
        tseq     (lazy-seq (cons 1 (lazy-seq (cons 2 (lazy-seq (cons 3 (lazy-seq)))))))
        tvector  [1 2 3]
        tmap     {:one 1 :two 2 :three 3}
        tset     #{:one 2 'three}]
    (is (= (unmarshal-db-entry (marshal-db-entry tnil)) tnil))
    (is (= (unmarshal-db-entry (marshal-db-entry tboolean)) tboolean))
    (is (= (unmarshal-db-entry (marshal-db-entry tchar)) tchar))
    (is (= (unmarshal-db-entry (marshal-db-entry tbyte)) tbyte))
    (is (= (unmarshal-db-entry (marshal-db-entry tshort)) tshort))
    (is (= (unmarshal-db-entry (marshal-db-entry tint)) tint))
    (is (= (unmarshal-db-entry (marshal-db-entry tlong)) tlong))
    (is (= (unmarshal-db-entry (marshal-db-entry tbigint)) tbigint))
    (is (= (unmarshal-db-entry (marshal-db-entry tratio)) tratio))
    (is (= (unmarshal-db-entry (marshal-db-entry tfloat)) tfloat))
    (is (= (unmarshal-db-entry (marshal-db-entry tdouble)) tdouble))
    (is (= (unmarshal-db-entry (marshal-db-entry tstring)) tstring))
    (is (= (unmarshal-db-entry (marshal-db-entry tdate)) tdate))
    (is (= (unmarshal-db-entry (marshal-db-entry jt-dt)) jt-dt))
    (is (= (unmarshal-db-entry (marshal-db-entry jt-ld)) jt-ld))
    (is (= (unmarshal-db-entry (marshal-db-entry jt-lt)) jt-lt))
    (is (= (unmarshal-db-entry (marshal-db-entry jt-ldt)) jt-ldt))
    (is (= (unmarshal-db-entry (marshal-db-entry tuuid)) tuuid))
    (is (= (unmarshal-db-entry (marshal-db-entry tkeyword)) tkeyword))
    (is (= (unmarshal-db-entry (marshal-db-entry tsymbol)) tsymbol))
    (is (= (unmarshal-db-entry (marshal-db-entry tlist)) tlist))
    (is (= (unmarshal-db-entry (marshal-db-entry tseq)) tseq))
    (is (= (unmarshal-db-entry (marshal-db-entry tvector)) tvector))
    (is (= (unmarshal-db-entry (marshal-db-entry tmap)) tmap))
    (is (= (unmarshal-db-entry (marshal-db-entry tset)) tset))))

(deftest java-array-marshalling
  (are [x] (every? true? (map = x (unmarshal-db-entry (marshal-db-entry x))))
       (boolean-array [true false])
       (byte-array [(byte 13) (byte 11)])
       (char-array [(char 11) (char 13)])
       (double-array [(double 127.5) (double 3.14157)])
       (float-array [(float 19.2) (float 27.5)])
       (int-array [1 2 3])
       (long-array [(long 32769) (long 32770)])
       (object-array [(java.util.Date.)])
       (short-array [(short 1) (short 19)])))
	 
	
(deftest other-marshaling
  (let [db-entry-empty    (marshal-db-entry (DatabaseEntry.))
        db-entry-nil      (marshal-db-entry nil)
        db-entry-obj      (DatabaseEntry.)
        db-entry-prealloc (marshal-db-entry "hello world" db-entry-obj)]
    (is (= (.getSize db-entry-empty) 0))
    (is (= (.getSize db-entry-nil) 1))
    (is (identical? db-entry-obj db-entry-prealloc))
    (is (identical? db-entry-prealloc (marshal-db-entry db-entry-prealloc)))))


(deftest optional-marshaling
  (let [arg-map {:data "hello"}]
    (is (not= 0 (.getSize (marshal-db-entry* arg-map :data))))
    (is (= 0 (.getSize (marshal-db-entry* arg-map :key))))))


(deftest optional-unmarshaling
  (is (= (unmarshal-db-entry* OperationStatus/SUCCESS
                              (marshal-db-entry "one")
                              (marshal-db-entry 1))
         ["one" 1]))
  (is (= (unmarshal-db-entry* OperationStatus/NOTFOUND
                              (marshal-db-entry "one")
                              (marshal-db-entry 1))
         [])))
