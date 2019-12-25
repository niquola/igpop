(ns json-rpc.tcp
  (:require [promesa.core :as p]
            [json-rpc.procedure]
            [clojure.string :as str]
            [cheshire.core])
  (:import
   [java.nio.channels
    AsynchronousServerSocketChannel
    AsynchronousSocketChannel
    SocketChannel
    CompletionHandler
    AsynchronousCloseException]
   [java.net InetSocketAddress]
   [java.nio.charset StandardCharsets]
   [java.nio ByteBuffer]))

(set! *warn-on-reflection* true)

(defn client [^String host ^Integer port]
  (let [cl-addr (InetSocketAddress. host port)
        cl (SocketChannel/open cl-addr)]
    cl))

(defn client-send [^SocketChannel cl data]
  (let [^ByteBuffer buf (ByteBuffer/wrap data)]
    (try (.write cl buf)
         (catch Exception e
           (.close cl)))))

(defn parse-header [s]
  (Integer/parseInt (str/trim (second (str/split (str/trim s) #":")))))

(def header-seq [(byte \return) (byte \newline) (byte \return) (byte \newline)])

(defn buf-to-string [^ByteBuffer buf]
  (let [_    (.flip buf)
        bs   (byte-array (.remaining buf))
        _    (.get buf bs)]
    (.rewind buf)
    (String. bs StandardCharsets/UTF_8)))

(defn decoder [on-message]
  (let [state (volatile! :header)
        ^ByteBuffer header-buf (ByteBuffer/allocateDirect 1000)
        ^ByteBuffer body-buf (ByteBuffer/allocateDirect 100000)
        header-state (volatile! 0)
        body-size    (volatile! nil)]
    (fn [^ByteBuffer buf]
      (let [_s @state]
        ;; (println "Parse" _s buf)
        (cond
          (= :header _s)
          (do
            (loop [i 0 hs @header-state]
              (if (.hasRemaining buf)
                (let [c (.get buf)]
                  (.put header-buf c)
                  (if (= c (nth header-seq hs))
                    (if (= 3 hs)
                      (let [size (parse-header (buf-to-string header-buf))]
                        (.clear header-buf)
                        (vreset! header-state 0)
                        (vreset! state :body)
                        (vreset! body-size size))
                      (recur (inc i) (vreset! header-state (inc hs))))
                    (recur (inc i) hs)))
                :header)))

          (= :body _s)
          (let [cb (.position body-buf)
                bs @body-size
                cnt (.remaining buf)
                remain (- bs (.position body-buf))
                new-cnt (+ cb cnt)]
            (if (> bs new-cnt)
              (.put body-buf buf)

              (let [ba (byte-array remain)
                    _ (.get buf ba)]
                (.put body-buf ba)
                (on-message (cheshire.core/parse-string (buf-to-string body-buf) keyword))
                (.clear body-buf)
                (vreset! state :header)
                (vreset! body-size nil))))))
      (when (.hasRemaining buf)
        (recur buf)))))

(defn read-channel [^AsynchronousSocketChannel channel conns]
  (let [buf (ByteBuffer/allocateDirect 10000)
        on-message (fn [msg]
                     (println "* " (:method msg))
                     (let [res (cond-> (json-rpc.procedure/proc msg)
                                 (:id msg) (assoc :id (:id msg)))]
                       (when (:id msg)
                         (let [json-res (cheshire.core/generate-string res)
                               res-bytes (.getBytes json-res StandardCharsets/UTF_8)]
                           (println "Resp" res)
                           (.write channel (ByteBuffer/wrap (.getBytes (format "Content-Length: %s\r\n\r\n" (count res-bytes)))))
                           (.write channel (ByteBuffer/wrap res-bytes))))))
        decode (decoder on-message)]
    ;; (println "read channel")
    (.read channel buf nil
           (reify CompletionHandler
             (completed [this cnt _]
               #_(println "Completed" cnt)
               (when (= -1 cnt)
                 (println "Disconnected " channel)
                 (swap! conns disj channel))
               (when (> cnt 0)
                 (.flip buf)
                 (decode buf)
                 (.clear buf)
                 (.read channel buf nil this)))
             (failed [this e _]
               (if (instance? AsynchronousCloseException e)
                 (println "Closed " channel)
                 (do (.close channel)
                     (println "! Failed (read):" e))))))))

(defn handler [listener on-message conns]
  (reify CompletionHandler
    (failed [this e _]
      (if (instance? AsynchronousCloseException e)
        (println "Closed..")
        (println "! Failed (read):" e)))
    (completed [this sc _]
      (println "Incomming connection " sc)
      (swap! conns conj sc)
      (.accept ^AsynchronousServerSocketChannel listener nil  this)
      (read-channel sc conns))))

(defn start [ctx]
  (let [assc (AsynchronousServerSocketChannel/open)
        port 7345
        sa  (InetSocketAddress. port)
        listener (.bind assc sa)
        on-message (or (:on-message @ctx) println)
        conns (atom #{})]
    (println "tcp server started at  " port)
    (.accept listener nil (handler listener on-message conns))
    (swap! ctx (fn [ctx] (update ctx :lsp assoc :sock assc :conns conns))))
  ctx)


(defn stop [ctx]
  (when-let [conns (get-in @ctx [:lsp :conns])]
    (doseq [c @conns]
      (.close ^AsynchronousSocketChannel c))
    (reset! conns #{}))

  (when-let [sock (get-in @ctx [:lsp :sock])]
    (println "Stop server")
    (.close ^AsynchronousSocketChannel sock)
    (println "ok")))



(comment
  (stop ctx)

  (def ctx (start (atom {:lsp {:port 7345}})))

  ctx

  (def cl (client "localhost" 7777))

  (.close cl)

  (.isOpen cl)
  (.isConnected cl)

  (client-send cl (for [i (range 100)] {:a i}))

  )

