(ns iboga.core
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [iboga.impl :as impl]
            [iboga.meta :as meta]
            [medley.core :as m])
  (:import [com.ib.client EClientSocket EJavaSignal EReader]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;util;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn qualify-key [parent k]
  (keyword (str (namespace parent) "." (name parent)) (name k)))

(defn invoke [obj mname args]
  (clojure.lang.Reflector/invokeInstanceMethod obj mname (to-array args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;schema;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema (atom {}))

(defn set-schema!
  ([m] (reset! schema m))
  ([k attrs] (swap! schema assoc k attrs))
  ([k attr v] (swap! schema assoc-in [k attr] v)))

;;(defn get-spec [k]          (get-in @schema [k :spec]))
(defn get-default-value       [k] (get-in @schema [k :default-value]))
(defn contains-default-value? [k] (contains? (get @schema k) :default-value))

(defn get-default-fn [k]    (get-in @schema [k :default-fn]))
(defn get-to-ib [k]         (get-in @schema [k :to-ib]))
(defn get-from-ib [k]       (get-in @schema [k :from-ib]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;defaults;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-default-values [req-key argmap]
  (reduce
   (fn [m k]
     (let [missing? (not (contains? m k))
           dflt     (get-default-value k)]
       (cond-> m
         (and missing? (some? dflt)) (assoc k dflt))))
   argmap
   (meta/req-key->field-keys req-key)))

(defn add-default-fns [req-key argmap]
  (reduce
   (fn [m k]
     (let [missing? (not (contains? m k))
           dflt-fn  (get-default-fn k)]
       (cond-> m
         (and missing? dflt-fn) (assoc k (dflt-fn argmap)))))
   argmap
   (meta/req-key->field-keys req-key)))

(defn add-default-nils [req-key argmap]
  (reduce
   (fn [m k]
     (cond-> m
       (not (contains? m k)) (assoc k nil)))
   argmap
   (meta/req-key->field-keys req-key)))

(defn add-defaults [req-key argmap]
  (->> argmap
       (add-default-values req-key)
       (add-default-fns req-key)
       (add-default-nils req-key)))

(defn optional? [arg]
  (boolean
   (or (contains-default-value? arg)
       (get-default-fn arg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;specs;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;for now we wont' be picky about numbers
(defn spec-for-class [class]
  (cond
    (#{Float/TYPE Double/TYPE Integer/TYPE Long/TYPE} class) number?
    (= Boolean/TYPE class) boolean?
    :else #(instance? class %)))

(defn ibkr-spec-key [k] (qualify-key k :ibkr))

(defn def-field-specs []
  (doseq [[k f] meta/spec-key->field]
    (let  [ibkr-key   (ibkr-spec-key k)
           collection (:java/collection f)
           class      (or collection (:java/class f))
           field-spec (or (meta/field-isa k) ibkr-key)]
      (eval `(s/def ~ibkr-key ~(spec-for-class class)))
      (eval `(s/def ~k ~(if collection
                          `(s/coll-of ~field-spec)
                          field-spec))))))

(defn def-enum-specs []
  (doseq [[k s] meta/enum-sets]
    (eval `(s/def ~k ~s))))

(defn def-struct-specs []
  (doseq [[k fields] meta/struct-key->field-keys]
    (let [fields (vec fields)]
      (eval `(s/def ~k (s/keys :opt ~fields))))))

(defn def-req-specs []
  (doseq [[req-key params] meta/req-key->field-keys]
    (let [{opt true
           req false} (group-by optional? params)]
      (eval `(s/def ~req-key
               (s/keys ~@(when (not-empty opt) [:opt opt])
                       ~@(when (not-empty req) [:req req])))))))

(defn def-schema-specs []
  (doseq [[k {:keys [spec]}] @schema]
    (when spec
      (eval `(s/def ~k ~spec)))))

(defn init-specs []
  (def-enum-specs)
  (def-struct-specs)
  (def-req-specs)
  (def-field-specs)
  (def-schema-specs))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;transform;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn construct [struct-key args]
  (let [cname (name (.getName (meta/struct-key->class struct-key)))]
    (clojure.lang.Reflector/invokeConstructor
     (resolve (symbol cname))
     (to-array args))))

(defn map->obj [m type-key]
  (let [obj (construct type-key [])]
    (doseq [[k v] m]
      (invoke obj (meta/struct-field-key->ib-name k) [v]))
    obj))

(defn obj->map [obj]
  (->> (meta/struct-class->getter-fields (class obj))
       (map
        (fn [{:keys [spec-key ib-name]}]
          (when-some [v (invoke obj ib-name [])]
            (m/map-entry spec-key v))))
       (into {})))

(def to-java-coll
  {java.util.List (fn [xs] (java.util.ArrayList. xs))
   java.util.Map  (fn [xs] (java.util.HashMap xs))
   java.util.Set  (fn [xs] (java.util.HashSet. xs))})

(defn to-ib
  ([m]
   (m/map-kv #(m/map-entry %1 (to-ib %1 %2)) m))
  ([k x]
   (let [collection-class (meta/field-collection k)]
     (if-let [java-coll-fn (to-java-coll collection-class)]
       (java-coll-fn (map #(to-ib (meta/field-isa k) %) x))
       
       (let [type-key (or
                       ((set (keys meta/struct-key->class)) k)
                       (meta/field-isa k))
             to-ib-fn (or (get-to-ib type-key)
                          (get-to-ib k))]
         (cond
           ;;if we have a to-ib fn for its type or key we do that
           to-ib-fn (to-ib-fn x)

           ;;if it has a type but no custom translation, we turn it into the type of
           ;;object described by its type key
           type-key (map->obj (to-ib x) type-key)

           :else x))))))

(def to-clj-coll
  {java.util.ArrayList (fn [xs] (into [] xs))
   java.util.HashMap   (fn [xs] (into {} xs))
   java.util.HashSet   (fn [xs] (into #{} xs))})

(defn from-ib
  ([m] (m/map-kv #(m/map-entry %1 (from-ib %1 %2)) m))
  ([k x]
   (let [from-ib-fn  (get-from-ib k)
         clj-coll-fn (to-clj-coll (class x))]
     (cond
       clj-coll-fn (clj-coll-fn (map #(from-ib (meta/field-isa k) %) x))
       
       ;;allow custom translation to/from ib
       (and (not from-ib-fn) (meta/struct-class->getter-fields (class x)))
       (from-ib (obj->map x))

       from-ib-fn (from-ib-fn x)

       (meta/iboga-enum-classes (type x)) (str x)
       
       :else x))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;init;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(set-schema! impl/default-schema)
(init-specs)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;qualifying;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn qualify-map [parent m]
  (m/map-kv
   (fn [k v]
     (let [k (qualify-key parent k)
           field-type (meta/field-isa k)
           v (if field-type
               (if (vector? v)
                 (mapv #(qualify-map field-type %) v)
                 (qualify-map field-type v))
               v)]
       (m/map-entry k v)))
   m))

(defn unqualify [k] (keyword (name k)))

;;doesn't currently handle nested sequences
(defn unqualify-map [m]
  (m/map-kv
   (fn [k v] (m/map-entry (unqualify k) (if (map? v) (unqualify-map v) v)))
   m))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EWrapper;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-reify-specs [cb]
  (map
   (fn [{:keys [msym signature msg]}]
     (list msym signature
           (list cb msg)))
   meta/ewrapper-data))

(defmacro wrapper [cb] 
  `(reify com.ib.client.EWrapper ~@(make-reify-specs cb)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;client;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn unqualify-msg [msg]
  (-> msg
      (update 0 unqualify)
      (update 1 (comp unqualify-map from-ib))))

(defn process-messages [client reader signal]
  (while (.isConnected client)
    (.waitForSignal signal)
    (.processMsgs reader)))

(defn client
  "Takes one or more message handler functions and returns a map which
  represents an IB api client."
  [& handlers]
  (let [handlers       (atom (set handlers))
        handle-message (fn [msg]
                         (doseq [f @handlers]
                           (try (f (unqualify-msg msg))
                                (catch Throwable t
                                  (log/error t "Error handling message")))))
        wrap           (wrapper handle-message)
        sig            (EJavaSignal.)
        ecs            (EClientSocket. wrap sig)
        next-id        (atom 0)
        next-id-fn     #(swap! next-id inc)] ;;todo: seperate order ids?
    {:connect-fn (fn [host port & [client-id]]
                   (.eConnect ecs host port (or client-id (rand-int (Integer/MAX_VALUE))))
                   (let [reader (EReader. ecs sig)]
                     (.start reader)
                     (future (process-messages ecs reader sig))))
     :ecs        ecs
     :handlers   handlers
     :next-id    next-id-fn}))

(defn connect
  "Takes a connection map, a host string, a port number and optionally a
  client-id and connects to the IB api server. If no client id is
  provided, a random integer will be used."
  [conn host port & [client-id]]
  ((:connect-fn conn) host port client-id))

(defn disconnect [conn] (-> conn :ecs .eDisconnect))

(defn connected? [conn] (-> conn :ecs .isConnected))

(defn add-handler [conn f] (swap! (:handlers conn) conj f))

(defn remove-handler [conn f] (swap! (:handlers conn) disj f))

;;TODO: next-id shouldn't clash with order-id. See:
;;https://github.com/InteractiveBrokers/tws-api/blob/master/source/javaclient/com/ib/controller/ApiController.java#L149
(defn next-id [conn] ((:next-id conn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;req;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn req-spec-key [k & [arg]]
  (if arg
    (qualify-key (req-spec-key k) arg)
    (qualify-key :iboga/req k)))

(defn argmap->arglist [req-key argmap]
  (mapv argmap (meta/req-key->field-keys req-key)))

(defn arglist->argmap [req-key arglist]
  (zipmap (meta/req-key->field-keys req-key) arglist))

(defn ensure-argmap [[req-key args :as request]]
  (cond-> request
    (vector? args) (update 1 #(arglist->argmap req-key %))))

(defn ensure-qualified-argmap [[req-key args :as req]]
  ;;(assert (map? args))
  (update req 1 #(qualify-map req-key %)))

(defn qualify-req [req]
  (-> req
      (update 0 req-spec-key)
      ensure-argmap
      ensure-qualified-argmap))

(defn invoke-req [ecs req-key args]
  (invoke ecs (meta/msg-key->ib-name req-key) args))

(def validate? (atom true))

(defn validate-reqs [b] (reset! validate? b))

(defn assert-valid-req [k args]
  (when-not (s/valid? k args)
    (throw (Exception. (ex-info "Invalid request" (s/explain-data k args))))))

(defn req [conn request]
  (if-not (connected? conn)
    (throw (Exception. "Not connected"))
    (let [[req-key argmap] (qualify-req request)
          argmap           (add-defaults req-key argmap)]
      (when @validate?
        (assert-valid-req req-key argmap))
      (->> argmap
           to-ib
           (argmap->arglist req-key)
           (invoke-req (:ecs conn) req-key)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;repl helpers;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn msg-name-search
  "Returns pairs of Interactive Brokers method name strings which contain the search string to the message key used to make requests/handle received messages in Iboga. Case insensitive."
  [ib-name-str]
  (->> meta/ib-msg-name->spec-key
       (m/map-vals unqualify)
       (filter #(.contains (.toLowerCase (first %)) ib-name-str))
       (sort-by first)))