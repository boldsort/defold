(ns editor.defold-project-search-test
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [dynamo.graph :as g]
            [editor.defold-project-search :as project-search]
            [editor.resource :as resource]
            [integration.test-util :as test-util]
            [support.test-support :refer [with-clean-system]]
            [util.thread-util :as thread-util])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:const search-project-path "test/resources/search_project")
(def ^:const timeout-ms 1000)

(defn- make-consumer [report-error!]
  {:consumed-atom (atom [])
   :future-atom (atom nil)
   :report-error! report-error!})

(defn- consumer-stop! [consumer]
  (some-> consumer :future-atom deref future-cancel)
  consumer)

(defn- consumer-start! [{:keys [consumed-atom future-atom report-error!] :as consumer} results-fn!]
  ;; Stop the consumer if it is already running.
  (consumer-stop! consumer)

  ;; Clear the consumed list and re-create a future that appends to it.
  (reset! consumed-atom [])
  (reset! future-atom
          (future
            (try
              (loop [last-response-time (System/nanoTime)]
                (thread-util/throw-if-interrupted!)
                (let [poll-time (System/nanoTime)
                      ret (loop [[result & more] (results-fn!)]
                            ;; The call to results-fn! will return whatever is
                            ;; in its buffer - it will not block to await more
                            ;; results.
                            (cond
                              (nil? result)
                              ::not-done

                              (= ::project-search/done result)
                              ::done

                              :else
                              (do (swap! consumed-atom conj result)
                                  (recur more))))]
                  (when (and (= ret ::not-done)
                             (< (- poll-time last-response-time)
                                (* 1000000 timeout-ms)))
                    (Thread/sleep 10)
                    (recur last-response-time))))
              (catch InterruptedException _
                nil)
              (catch Throwable error
                (report-error! error)
                nil))))

  ;; Return the started consumer.
  consumer)

(defn- consumer-started? [consumer]
  (-> consumer :future-atom deref some?))

(defn- consumer-finished? [consumer]
  (if-let [pending-future (-> consumer :future-atom deref)]
    (and (future-done? pending-future)
         (not (future-cancelled? pending-future)))
    false))

(defn- consumer-stopped? [consumer]
  (if-let [pending-future (-> consumer :future-atom deref)]
    (or (future-cancelled? pending-future)
        (future-done? pending-future))
    true))

(defn- consumer-consumed [consumer]
  (-> consumer :consumed-atom deref))

(defn- match->trimmed-text [{:keys [line] :as _match}]
  (string/trim line))

(defn- matched-text-by-proj-path [consumed]
  (mapv (fn [{:keys [resource matches]}]
          [(resource/proj-path resource) (mapv match->trimmed-text matches)])
        consumed))

(defn- match-proj-paths [consumed]
  (into #{}
        (map (comp resource/proj-path :resource))
        consumed))

(deftest mock-consumer-test
  (let [report-error! (test-util/make-call-logger)
        consumer (make-consumer report-error!)
        queue (LinkedBlockingQueue. 4)
        poll-fn #(.poll queue timeout-ms TimeUnit/MILLISECONDS)]
    (is (false? (consumer-started? consumer)))
    (is (false? (consumer-finished? consumer)))
    (is (true? (consumer-stopped? consumer)))

    (consumer-start! consumer poll-fn)
    (is (true? (consumer-started? consumer)))
    (is (false? (consumer-finished? consumer)))
    (is (false? (consumer-stopped? consumer)))
    (future (.put queue ::project-search/done))
    (is (true? (test-util/block-until true? timeout-ms consumer-finished? consumer)))
    (is (true? (consumer-started? consumer)))
    (is (true? (consumer-stopped? consumer)))

    (consumer-start! consumer poll-fn)
    (consumer-stop! consumer)
    (is (true? (consumer-started? consumer)))
    (is (false? (consumer-finished? consumer)))
    (is (true? (consumer-stopped? consumer)))))

(deftest compile-find-in-files-regex-test
  (is (= "(?i)\\Qfoo\\E" (str (project-search/compile-find-in-files-regex "foo"))))
  (testing "* is handled correctly"
    (is (= "(?i)\\Qfoo\\E.*\\Qbar\\E" (str (project-search/compile-find-in-files-regex "foo*bar")))))
  (testing "other wildcard chars are quoted"
    (is (= "(?i)\\Qfoo\\E.*\\Qbar[]().$^\\E" (str (project-search/compile-find-in-files-regex "foo*bar[]().$^")))))
  (testing "case insensitive search strings"
    (let [pattern (project-search/compile-find-in-files-regex "fOoO")]
      (is (= "fooo" (re-matches pattern "fooo"))))))

(defn- try-make-save-data-future [project]
  (let [report-error! (test-util/make-call-logger)
        save-data-future (project-search/make-file-resource-save-data-future report-error! project)]
    (when (is (= [] (test-util/call-logger-calls report-error!)))
      (let [search-paths (into #{}
                               (map (comp resource/proj-path :resource))
                               (deref save-data-future))]
        (when (is (= search-paths
                     (into #{}
                           (comp (keep #(some-> (g/node-value % :save-data) :resource))
                                 (remove resource/internal?)
                                 (keep resource/proj-path))
                           (g/node-value project :nodes))))
          save-data-future)))))

(deftest file-searcher-test
  (test-util/with-loaded-project search-project-path
    (test-util/with-ui-run-later-rebound
      (when-some [save-data-future (try-make-save-data-future project)]

        (testing "Matches expected results"
          (let [report-error! (test-util/make-call-logger)
                consumer (make-consumer report-error!)
                start-consumer! (partial consumer-start! consumer)
                stop-consumer! consumer-stop!
                {:keys [start-search! abort-search!]} (project-search/make-file-searcher save-data-future start-consumer! stop-consumer! report-error!)
                perform-search! (fn [term exts]
                                  (start-search! term exts true)
                                  (is (true? (test-util/block-until true? timeout-ms consumer-finished? consumer)))
                                  (-> consumer consumer-consumed matched-text-by-proj-path))]
            (is (= [] (perform-search! nil nil)))
            (is (= [] (perform-search! "" nil)))
            (is (= [] (perform-search! nil "")))
            (is (set/subset? #{["/modules/colors.lua" ["red = {255, 0, 0},"]]
                               ["/scripts/apples.script" ["\"Red Delicious\","]]}
                             (set (perform-search! "red" nil))))
            (is (set/subset? #{["/modules/colors.lua" ["red = {255, 0, 0},"
                                                       "green = {0, 255, 0},"
                                                       "blue = {0, 0, 255}"]]}
                             (set (perform-search! "255" nil))))
            (is (set/subset? #{["/scripts/actors.script" ["\"Will Smith\""]]
                               ["/scripts/apples.script" ["\"Granny Smith\""]]}
                             (set (perform-search! "smith" "script"))))
            (is (set/subset? #{["/modules/colors.lua" ["return {"]]
                               ["/scripts/actors.script" ["return {"]]
                               ["/scripts/apples.script" ["return {"]]}
                             (set (perform-search! "return" "lua, script"))))
            (is (some #(.startsWith % "/builtins")
                      (map first (perform-search! "return" "lua, script"))))
            (is (= [["/foo.bar" ["Buckle my shoe;"]]] (perform-search! "buckle" nil)))
            (abort-search!)
            (is (true? (test-util/block-until true? timeout-ms consumer-stopped? consumer)))
            (is (= [] (test-util/call-logger-calls report-error!)))))

        (testing "Search can be aborted"
          (let [report-error! (test-util/make-call-logger)
                consumer (make-consumer report-error!)
                start-consumer! (partial consumer-start! consumer)
                stop-consumer! consumer-stop!
                {:keys [start-search! abort-search!]} (project-search/make-file-searcher save-data-future start-consumer! stop-consumer! report-error!)]
            (start-search! "*" nil true)
            (is (true? (consumer-started? consumer)))
            (abort-search!)
            (is (true? (test-util/block-until true? timeout-ms consumer-stopped? consumer)))
            (is (= [] (test-util/call-logger-calls report-error!)))))

        (testing "Matches among specified file extensions"
          (let [report-error! (test-util/make-call-logger)
                consumer (make-consumer report-error!)
                start-consumer! (partial consumer-start! consumer)
                stop-consumer! consumer-stop!
                {:keys [start-search! abort-search!]} (project-search/make-file-searcher save-data-future start-consumer! stop-consumer! report-error!)
                search-string "peaNUTbutterjellytime"
                perform-search! (fn [term exts]
                                  (start-search! term exts true)
                                  (is (true? (test-util/block-until true? timeout-ms consumer-finished? consumer)))
                                  (-> consumer consumer-consumed matched-text-by-proj-path))]
            (are [expected-count exts]
              (= expected-count (count (perform-search! search-string exts)))
              1 "g"
              1 "go"
              1 ".go"
              1 "*.go"
              2 "go,sCR"
              2 nil
              2 ""
              0 "lua"
              1 "lua,go"
              1 " lua,  go"
              1 " lua,  GO"
              1 "script")
            (abort-search!)
            (is (true? (test-util/block-until true? timeout-ms consumer-stopped? consumer)))
            (is (= [] (test-util/call-logger-calls report-error!)))))

        (testing "Include or exclude matches inside libraries"
          (let [report-error! (test-util/make-call-logger)
                consumer (make-consumer report-error!)
                start-consumer! (partial consumer-start! consumer)
                stop-consumer! consumer-stop!
                {:keys [start-search! abort-search!]} (project-search/make-file-searcher save-data-future start-consumer! stop-consumer! report-error!)
                perform-search! (fn [term exts include-libraries?]
                                  (start-search! term exts include-libraries?)
                                  (is (true? (test-util/block-until true? timeout-ms consumer-finished? consumer)))
                                  (-> consumer consumer-consumed match-proj-paths))]
            (is (= #{}
                   (perform-search! "socket" "lua" false)))
            (is (= #{"/builtins/scripts/mobdebug.lua" "/builtins/scripts/socket.lua"}
                   (perform-search! "socket" "lua" true)))
            (abort-search!)
            (is (true? (test-util/block-until true? timeout-ms consumer-stopped? consumer)))
            (is (= [] (test-util/call-logger-calls report-error!)))))))))
