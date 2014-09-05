; This is a custom tests runner script used by the Maven clojure plugin.
; The configuration to use this script lives in pom-common-internal/pom.xml

(ns com.liveramp.build_tools.common.clj-test-runner
  (:import [java.io PrintStream ByteArrayOutputStream])
  (:require [clojure.string :as str]
            [clojure.test :refer :all]))

(defn get-property [name]
  (let [v (System/getProperty name)]
    (if (= (str "${" name "}") v)
      nil
      v)))

(defn get-and-validate-config []
  (let [java-test-case (get-property "test")
        output-dir (System/getProperty "outputDir")
        namespaces (->> (file-seq (clojure.java.io/file "test/clojure"))
                        (filter #(and (.isFile %) (.endsWith (.getName %) ".clj")))
                        (map #(-> (.getPath %)
                                  (.replaceAll "test/clojure/" "")
                                  (.replaceAll "/" ".")
                                  (.replaceAll "_" "-")
                                  (.replaceAll ".clj" "")
                                  symbol)))
        find-test-case (fn [tc all]
                         (some #(if (or (= (name %) tc)
                                        (= (-> % name (str/split #"\.") last) tc))
                                  %
                                  nil) all))
        raw-test-case (get-property "ctest")
        test-case (if (nil? raw-test-case)
                    nil
                    (find-test-case raw-test-case namespaces))
        config {:namespaces (if (nil? test-case) namespaces (list test-case))
                :output-dir output-dir}]
    (if (and (not (nil? raw-test-case))
             (not (some #(= % test-case) namespaces)))
      (throw (Exception. (str raw-test-case " is not a valid test namespace."))))
    (when (not (nil? java-test-case))
      (println "Skipping clojure tests since -Dtest was specified for Java.")
      (System/exit 0))
    (when (empty? namespaces)
      (println "No test namespaces found.")
      (System/exit 0))
    config))

(def escape-xml-map
  (zipmap "'<>\"&" (map #(str \& % \;) '[apos lt gt quot amp])))

(defn- escape-xml [text]
  (apply str (map #(escape-xml-map % %) text)))

(defn xml-escaping-writer
  [writer]
  (proxy
      [java.io.FilterWriter] [writer]
    (write [text]
      (if (string? text)
        (.write writer (escape-xml text))
        (.write writer text)))
    ))

(defn total_errors [summary]
  (+ (:error summary) (:fail summary)))

(defn print-results [results]
  (println (str "Tests run: " (:test results)
                ", Assertions: " (:pass results)
                ", Failures: " (:fail results)
                ", Errors: " (:error results))))

(defn run-all []
  (let [{output-dir :output-dir, namespaces :namespaces, test-case :test-case} (get-and-validate-config)]
    (.mkdirs (java.io.File. output-dir))
    (let [results (atom {})
          individual-result (atom nil)
          cur-stream (atom nil)
          delegating-stream (proxy [PrintStream] [(ByteArrayOutputStream.)]
                              (print [s] (.print @cur-stream s))
                              (println [s] (.println @cur-stream s))
                              (write
                                ([buf off len] (.write @cur-stream buf off len))
                                ([b] (.write @cur-stream b))))]
      (System/setOut delegating-stream)
      (System/setErr delegating-stream)
      (doseq [tns namespaces]
        (with-open [s (java.io.PrintStream. (str output-dir "/" tns ".txt"))
                    w (java.io.BufferedWriter. (java.io.OutputStreamWriter. s))]
          (println "Running" tns)
          (reset! cur-stream s)
          (binding [*err* w
                    *out* w
                    *test-out* w]
            (require tns)
            (let [r (run-tests tns)]
              (reset! individual-result (select-keys r [:pass :test :error :fail]))))
          (print-results @individual-result)
          (swap! results (partial merge-with +) (select-keys @individual-result [:pass :test :error :fail]))))
      (shutdown-agents)
      (println "\nResults: \n")
      (print-results @results)
      (if (> (total_errors @results) 0)
        (println "There are test failures."))
      (System/exit (total_errors @results)))))

(when-not *compile-files*
  (try
    (run-all)
    (catch Throwable e
      (.printStackTrace e *err*)
      (System/exit 1))))

