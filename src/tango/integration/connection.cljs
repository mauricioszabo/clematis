(ns tango.integration.connection
  (:require [promesa.core :as p]
            [duck-repled.editor-helpers :refer [read-file]]
            [duck-repled.core :as duck]
            [orbit.nrepl.evaluator :as orbit-nrepl]
            [orbit.shadow.evaluator :as orbit-shadow]
            [tango.commands-to-repl.pathom :as pathom]
            ["fs" :as fs]
            ["path" :as path]
            [orbit.evaluation :as eval]
            [tango.ui.edn :as edn-ui]
            [tango.integration.interpreter :as int]
            [tango.integration.autocomplete :as autocomplete]
            [tango.ui.console :as tango-console]
            [reagent.dom :as rdom]))

(defn- callback-fn [state ^js watcher output]
  ; (js/console.log "O" (pr-str output))
  (let [{:keys [on-stdout on-stderr on-result on-disconnect on-patch on-diagnostic]}
        (:editor/callbacks @state)]
    (when (nil? output)
      (swap! state assoc-in [:editor/callbacks :on-disconnect] identity)
      (eval/close! (:repl/evaluator @state))
      (some-> watcher .close)
      (on-disconnect))

    (when-let [out (:out output)] (and on-stdout (on-stdout out)))
    (when-let [out (:err output)] (and on-stderr (on-stderr out)))
    (when (or (contains? output :result)
              (contains? output :error))
      (on-result output))
    (when-not (or (:out output)
                  (:err output)
                  (nil? output)
                  (:patch output))
      (on-diagnostic output))
    (when on-patch
      (when-let [patch (:orbit.patch/result output)]
        (on-patch (assoc output :result patch)))
      (when-let [patch (:orbit.patch/error output)]
        (on-patch (assoc output :result ^:tango/wrapped-error [patch]))))))

(defn- prepare-patch [{:keys [on-patch get-rendered-results] :as callbacks}]
  (if on-patch
    callbacks
    (assoc callbacks :on-patch
           (fn [patch]
             (doseq [res (get-rendered-results)
                     :let [state (-> res meta :patches)
                           to-patch (get @state (:orbit.patch/id patch))]
                     :when to-patch]
               (reset! to-patch (:result patch)))))))

(defn- prepare-console [{:keys [get-console on-stdout on-stderr] :as callbacks}]
  (cond-> callbacks
    (nil? on-stdout) (assoc :on-stdout #(tango-console/stdout (get-console) %))
    (nil? on-stderr) (assoc :on-stderr #(tango-console/stderr (get-console) %))))

(defn- file-exists? [file]
  (js/Promise. (fn [resolve] (fs/exists file resolve))))

(def ^:private default-opts
  {:on-start-eval identity
   :file-exists file-exists?
   :config-file-path nil
   :register-commands identity
   :open-editor identity
   :get-rendered-results (constantly [])
   :on-copy identity
   :on-eval identity
   :on-result identity
   :on-disconnect identity
   :editor-data identity
   :notify identity
   :get-config (constantly {:project-paths [], :eval-mode :prefer-clj})
   :prompt (fn [ & _] (js/Promise. (fn [])))})

(defn- connection-error! [error notify]
  ; (eval/close! evaluator)
  (if (= "ECONNREFUSED" error)
    (notify {:type :error
             :title "REPL not connected"
             :message (str "Connection refused. Ensure that you have a "
                           "Socket REPL started on this host/port")})
    (do
      (notify {:type :error
               :title "REPL not connected"
               :message (str "Unknown error while connecting to the REPL: "
                             error)})
      (.error js/console error)))
  nil)

(defn- did-eval [state on-eval {:keys [repl/result repl/error] :as full-result}]
  (let [on-eval (-> @state :editor/callbacks :on-eval)
        to-merge (dissoc full-result :com.wsscode.pathom3.connect.runner/attribute-errors)
        final-result (if result
                       (merge result to-merge)
                       ^:tango/error (merge error to-merge))]
    (on-eval final-result)
    final-result))

(defn- gen-eql [state]
  (let [duck-eql (duck/gen-eql)]
    (fn eql
      ([query] (eql {} query))
      ([seed query]
       (p/let [config ((-> @state
                           :editor/callbacks
                           :get-config))
               editor-contents ((-> @state
                                    :editor/callbacks
                                    :editor-data))]
         (duck-eql
          (merge {:repl/evaluator (:repl/evaluator @state)
                  :editor/data editor-contents
                  :config/eval-as (:eval-mode config)}
                 seed)
          query))))))

(defn- find-definition [state]
  (p/let [eql (-> @state :editor/features :eql)
          details (eql [{:text/current-var
                         [:definition/filename
                          :definition/contents
                          :definition/row
                          :definition/col]}])]
    (:text/current-var details)))

(defn- goto-definition [state]
  (p/let [{:definition/keys [filename contents row col]} (find-definition state)
          open-editor (-> @state :editor/callbacks :open-editor)]
    (when (and filename row)
      (open-editor (cond-> {:file-name filename :line row}
                     contents (assoc :contents (:text/contents contents))
                     col (assoc :column col))))))

(defn- doc-for-var [state]
  (p/let [on-start-eval (-> @state :editor/callbacks :on-start-eval)
          eql (-> @state :editor/features :eql)
          on-start-params (eql {:id (gensym "doc-for-var-")}
                               [:id :editor/filename :text/range :editor/data])
          _ (on-start-eval on-start-params)
          res (eql on-start-params [{:text/current-var [:render/doc]}])
          on-eval (-> @state :editor/callbacks :on-eval)]
    (if-let [interactive (-> res :text/current-var :render/doc)]
      (on-eval (assoc on-start-params
                      :result (with-meta interactive {:tango/interactive true})))
      (on-eval (assoc on-start-params
                      :result ^:tango/wrapped-error [(symbol "Couldn't find doc for var")])))))

(defn- eval-text-in-editor-context [state on-start-eval on-eval code params]
  (p/let [eql (-> @state :editor/features :eql delay)
          id (str (gensym "eval-"))
          editor-info (@eql [{:editor/contents [:editor/filename
                                                :text/range
                                                :editor/data
                                                :repl/namespace]}])
          editor-info (-> editor-info
                          :editor/contents
                          (dissoc :com.wsscode.pathom3.connect.runner/attribute-errors)
                          (assoc :id id))
          _ (on-start-eval editor-info)
          eval-params (assoc params :id id)
          query (conj [:text/range :editor/filename :editor/data]
                      (list :repl/result eval-params)
                      (list :repl/error eval-params))
          seed (cond-> (assoc editor-info :text/contents code)
                 (:namespace params) (assoc :repl/namespace (:namespace params))
                 (:row params) (assoc-in [:text/range 0 0] (:row params))
                 (:col params) (assoc-in [:text/range 0 1] (:col params)))
          res (@eql seed query)]
    (did-eval state on-eval res)))

(defn- commands-for [state]
  (let [eql (-> @state :editor/features :eql delay)
        query [:text/range :editor/filename :editor/data]
        on-start-eval (-> @state :editor/callbacks :on-start-eval)
        on-eval (-> @state :editor/callbacks :on-eval)
        eval-thing (fn [key]
                     (fn []
                       (p/let [id (str (gensym "eval-"))
                               editor-info (@eql [{key [:editor/filename :text/range :editor/data]}])
                               _ (on-start-eval (-> editor-info
                                                    (get key)
                                                    (dissoc :com.wsscode.pathom3.connect.runner/attribute-errors)
                                                    (assoc :id id)))
                               eval-params {:id id}
                               query (conj query
                                           (list :repl/result eval-params)
                                           (list :repl/error eval-params))
                               res (@eql [{key query}])]
                         (->> res key (did-eval state on-eval)))))
        evaluate (-> @state :editor/features :evaluate delay)]
    {:evaluate-top-block {:command (eval-thing :text/top-block)}
     :evaluate-block {:command (eval-thing :text/block)}
     :evaluate-selection {:command (eval-thing :text/selection)}
     :break-evaluation {:command #(-> @state :repl/evaluator eval/break!)}
     :run-tests-in-ns {:command #(do
                                   (@evaluate "(require 'clojure.test)")
                                   (@evaluate "(clojure.test/run-tests)"))}
     :run-test-for-var {:command #(p/let [{:keys [text/current-var]} (@eql [:text/current-var])]
                                    (@evaluate "(require 'clojure.test)")
                                    (@evaluate (str "(clojure.test/test-vars [#'"
                                                    (:text/contents current-var) "])")))}
     :disconnect {:command #(-> @state :repl/evaluator eval/close!)}
     :doc-for-var {:command #(doc-for-var state)}
     :load-file {:command #(p/let [{:keys [editor/filename]} (@eql [:editor/filename])
                                   res (@evaluate (str "(load-file " (pr-str filename) ")"))]
                             (prn :RES (:result res)))}
     :go-to-var-definition
     {:command #(goto-definition state)}}))

(defn- maybe-connect-shadow! [host nrepl-evaluator on-output]
  (p/let [result (eval/evaluate nrepl-evaluator
                                "(require 'shadow.cljs.devtools.cli)"
                                {:plain false})]
    (if (:error result)
      nrepl-evaluator
      (orbit-shadow/connect! host nrepl-evaluator on-output))))

(defn- features-for [state]
  {:result-for-renderer #(edn-ui/for-result % state)
   :autocomplete (autocomplete/generate state)
   :find-definition #(find-definition state)
   :pathom/add-resolver (partial pathom/add-resolver state)
   :pathom/compose-resolver (partial pathom/compose-resolver state)
   :evaluate (partial eval-text-in-editor-context state identity identity)
   :evaluate-and-render (partial eval-text-in-editor-context
                                 state
                                 (-> @state :editor/callbacks :on-start-eval)
                                 (-> @state :editor/callbacks :on-eval))})

(defn- to-range [^js start ^js end]
  [[(.-row start) (.-column start)]
   [(.-row end) (.-column end)]])

(defn- prepare-interpreter [state plugin-name]
  (let [{:keys [register-commands notify]} (:editor/callbacks @state)
        commands (:editor/commands @state)
        [evaluator additional-commands] (int/generate-evaluator plugin-name state)]
       (swap! state assoc-in [:editor/features :interpreter/evaluator]
              evaluator)

       (-> additional-commands
           (p/then (fn [additional]
                     (register-commands (concat commands additional))
                     true))
           (p/catch (fn [error]
                      (register-commands commands)
                      (notify {:type :error
                               :title "Error in config file"
                               :message (pr-str error)})
                      false)))))

(defn- make-diagnostic [editor-state output]
  (let [parse (-> @editor-state :editor/features :result-for-renderer)
        console (-> @editor-state :editor/callbacks :get-console)
        append-error (fn [error]
                       (let [div (doto (js/document.createElement "div")
                                       (.. -classList (add "content")))
                             hiccup (parse {:error error})]
                         (rdom/render hiccup div)
                         (tango-console/append (console) div ["icon-bug"])))]
    (cond
      (-> output meta :orbit.shadow/error)
      (append-error output)

      (:orbit.shadow/compile-info output)
      (tango-console/update-build-status (console) (:orbit.shadow/compile-info output))

      (:orbit.shadow/clients-changed output)
      (tango-console/update-clients (console)
                                    editor-state
                                    (:orbit.shadow/clients-changed output)))))

(defn- ensure-dir-exists! [dir]
  (js/console.error "Exists!" dir)
  (if (fs/existsSync dir)
    true
    (let [parent (path/dirname dir)
          exists? (delay (ensure-dir-exists! parent))]
      (if (= parent dir)
        false
        (do
          (when @exists? (fs/mkdirSync dir))
          @exists?)))))

(defn- make-watcher [state config-directory plugin-name]
  (when (ensure-dir-exists! config-directory)
    (js/console.error "Exists!")
    (fs/watch config-directory (fn [event filename]
                                 (p/then (prepare-interpreter state plugin-name)
                                         #(when %
                                            ((-> @state :editor/callbacks :notify)
                                             {:type :info
                                              :title "Config reloaded"})))))))

(defn connect!
  "Connects to a clojure-like REPL that supports the socket REPL protocol.
Expects host, port, and some configs/callbacks:
* config-directory -> the directory where the config file(s) will be stored
* on-start-eval -> a function that'll be called when an evaluation starts
* on-eval -> a function that'll be called when an evaluation ends
* editor-data -> a function that'll be called when a command needs editor's data.
  Editor's data is a map (or a promise that resolves to a map) with the arguments:
    :contents - the editor's contents.
    :filename - the current file's name. Can be nil if file was not saved yet.
    :range - a vector containing [[start-row start-col] [end-row end-col]], representing
      the current selection
* open-editor -> asks the editor to open an editor. Expects a map with `:filename`,
  `:line` and maybe `:contents`. If there's `:contents` key, it defines a \"virtual
  file\" so it's better to open up an read-only editor
* notify -> when something needs to be notified, this function will be called with a map
  containing :type (one of :info, :warning, or :error), :title and :message
* get-config -> when some function needs the configuration from the editor, this fn
  is called without arguments. Need to return a map with the config options.
* get-rendered-results -> gets all results that are rendered on the editor. This is
  used so that the REPL can 'patch' these results when new data appears (think
  of resolving promises in JS)
* on-patch -> patches the result. Optional, if you send a :get-rendered-results
  callback, one will be generated for you
* prompt -> when some function needs an answer from the editor, it'll call this
  callback passing :title, :message, and :arguments (a vector that is composed by
  :key and :value). The callback needs to return a `Promise` with one of the
  :key from the :arguments, or nil if nothing was selected.
* register-commands -> called with the commands that will be registered when the
  REPL connects and/or when we update the config files with new commands
* on-copy -> a function that receives a string and copies its contents to clipboard
* get-console -> a function that returns the current console for the plug-in, if one exists.
  If this callback is passed, stdout, stderr, and diagnostics will be sent to this console
  by default, so you don't need to pass the next 3 callbacks
* on-stdout -> a function that receives a string when some code prints to stdout
* on-stderr -> a function that receives a string when some code prints to stderr
* on-diagnostic -> a function that receives a Shadow-CLJS output, and renders a diagnostic
  when there are errors or warnings on the code
* on-result -> returns a clojure EDN with the result of code
* on-disconnect -> called with no arguments, will disconnect REPLs. Can be called more
than once

Returns a promise that will resolve to a map with two repls: :clj/aux will be used
to autocomplete/etc, :clj/repl will be used to evaluate code."
  [host port {:keys [notify register-commands config-directory] :as opts}]
  (p/catch
   (p/let [options (-> default-opts
                       (merge opts)
                       prepare-patch
                       prepare-console
                       (dissoc :config-directory))
           state (atom {:editor/callbacks options
                        :config/directory config-directory})
           plugin-name (-> config-directory path/basename keyword)
           _ (js/console.error config-directory (pr-str plugin-name))
           watcher (make-watcher state config-directory plugin-name)
           _ (js/console.error "Helo?")
           callback (partial callback-fn state watcher)
           _ (js/console.error "Helo?")
           nrepl (orbit-nrepl/connect! host port callback)
           _ (js/console.error "Helo?")
           orbit-evaluator (maybe-connect-shadow! host nrepl callback)
           _ (js/console.error "Helo?")
           commands (commands-for state)
           _ (js/console.error "Helo?")
           features (features-for state)
           _ (js/console.error "Helo?")]
     [;; To not "await" for the promise
      (notify {:type :info :title "nREPL Connected"})]
     (js/console.error "Helo?")
     (swap! state merge {:repl/evaluator orbit-evaluator
                         :editor/features features
                         :editor/commands commands
                         :repl/info {:host host
                                     :kind :clj
                                     :port port}})

     (when-not (:on-diagnostic opts)
       (swap! state assoc-in
              [:editor/callbacks :on-diagnostic]
              (partial make-diagnostic state)))
     ;; Evaluator stuff
     (prepare-interpreter state plugin-name)
     (pathom/generate-eql-from-state! state)

     state)
   #(connection-error! % notify)))
