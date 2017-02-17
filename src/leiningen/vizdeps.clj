(ns leiningen.vizdeps
  "Graphviz visualization of project dependencies."
  (:require [leiningen.core.main :as main]
            [clojure.tools.cli :refer [parse-opts]]
            [dorothy.core :as d]
            [clojure.java.browse :refer [browse-url]]
            [leiningen.core.classpath :as classpath]
            [leiningen.core.project :as project]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io File]))

(defn ^:private dependency->label
  [dependency]
  (let [[artifact-name version] dependency]
    (format "%s%n%s" artifact-name version)))

(defn gen-graph-id
  [k]
  (str (gensym (str (name k) "-"))))

(defn ^:private add-edge
  [graph from-graph-id to-graph-id]
  (update-in graph [:edges] conj [from-graph-id to-graph-id]))

(defn ^:private add-node
  [graph node-id attributes]
  (assoc-in graph [:nodes node-id] (if (string? attributes)
                                     {:label attributes}
                                     attributes)))

(defn ^:private immediate-dependencies
  [project dependency]
  (-> (#'classpath/get-dependencies-memoized
        :dependencies nil
        (assoc project :dependencies [dependency])
        nil)
      (get dependency)
      ;; Tracking dependencies on Clojure itself overwhelms the graph
      (as-> $
            (remove #(= 'org.clojure/clojure (first %))
                    $))
      vec))

(declare ^:private add-dependencies)

(defn ^:private add-dependency-tree
  [graph project containing-node-id dependency]
  (let [artifact (first dependency)
        node-id (get-in graph [:deps artifact])]
    ;; When the node has been found from some other dependency,
    ;; just add a new edge to it.
    ;; TODO: Can we label the edge w/ the version number on conflict?
    (if node-id
      (add-edge graph containing-node-id node-id)
      ;; Otherwise its a new dependency in the graph and we want
      ;; to add the corresponding node and the edge to it,
      ;; but also take care of dependencies of the new node.
      (let [sub-node-id (-> dependency first gen-graph-id)]
        (-> graph
            (assoc-in [:deps (first dependency)] sub-node-id)
            (add-node sub-node-id (dependency->label dependency))
            (add-edge containing-node-id sub-node-id)
            (add-dependencies sub-node-id project
                              (immediate-dependencies project dependency)))))))

(defn ^:private add-dependencies
  [graph containing-node-id project dependencies]
  (reduce (fn [g coord]
            (add-dependency-tree g project containing-node-id coord))
          graph
          dependencies))

(defn ^:private dependency-graph
  "Builds out a structured dependency graph, from which a Dorothy node graph can be constructed."
  [project include-dev]
  (let [profiles (if-not include-dev [:user] [:user :dev])
        project' (project/set-profiles project profiles)
        root-dependency [(symbol (-> project :group str) (-> project :name str)) (:version project)]]

    ;; :nodes - map from node graph id to node attributes
    ;; :edges - list of edge tuples [from graph id, to graph id]
    ;; :sets - map from set id (a keyword or a symbol, typically) to a generated node graph id (a symbol)
    ;; :deps - map from artifact symbol (e.g., com.walmartlab/shared-deps) to a generated node graph id
    ;; :processed - set of artifact symbols that have been fully processed
    (add-dependencies {:nodes {:root {:label (dependency->label root-dependency)
                                      :shape :doubleoctagon}}
                       :edges []
                       :sets {}
                       :deps {}}
                      :root
                      project'
                      (:dependencies project'))))

(defn ^:private node-graph
  [dependency-graph options]
  (reduce into
          [(d/graph-attrs {:rankdir (if (:vertical options) :TD :LR)})]
          [(for [[k v] (:nodes dependency-graph)]
             [k v])
           (:edges dependency-graph)]))

(defn ^:private build-dot
  [project options]
  (-> (dependency-graph project (:dev options))
      (node-graph options)
      d/digraph
      d/dot))

(defn ^:private allowed-extension
  [path]
  (let [x (str/last-index-of path ".")
        ext (subs path (inc x))]
    (#{"png" "pdf"} ext)))

(def ^:private cli-options
  [["-o" "--output-file FILE" "Output file path. Extension chooses format: pdf or png."
    :id :output-path
    :default "target/dependencies.pdf"
    :validate [allowed-extension "Supported output formats are 'pdf' and 'png'."]]
   ["-s" "--save-dot" "Save the generated GraphViz DOT file well as the output file."]
   ["-n" "--no-view" "If given, the image will not be opened after creation."
    :default false]
   ["-v" "--vertical" "Use a vertical, not horizontal, layout."]
   ["-d" "--dev" "Include :dev dependencies in the graph."]
   ["-h" "--help" "This usage summary."]])

(defn ^:private usage [summary errors]
  (let [main (->> ["Usage: lein vizdeps [options]"
                   ""
                   "Options:"
                   summary]
                  (str/join \newline))]
    (println main)

    (when errors
      (println "\nErrors:")
      (doseq [e errors] (println " " e)))))

(defn vizdeps
  "Visualizes dependencies using Graphviz.

  Normally, this will generate an image and raise a frame to view it.
  Command line options allow the image to be written to a file instead."
  {:pass-through-help true}
  [project & args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]

    (if (or (:help options) errors)
      (usage summary errors)
      (let [{:keys [output-path no-view]} options
            output-format (-> output-path allowed-extension keyword)
            ^File output-file (io/file output-path)
            output-dir (.getParentFile output-file)]

        (when output-dir
          (.mkdirs output-dir))

        (let [dot (build-dot project options)]

          (when (:save-dot options)
            (let [x (str/last-index-of output-path ".")
                  dot-path (str (subs output-path 0 x) ".dot")
                  ^File dot-file (io/file dot-path)]
              (spit dot-file dot)))

          (d/save! dot output-file {:format output-format})

          (main/info "Wrote dependency chart to:" output-path))

        (when-not no-view
          (browse-url output-path))))))
