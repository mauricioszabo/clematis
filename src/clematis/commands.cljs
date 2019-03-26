(ns clematis.commands
  (:require [clojure.core.async :as async :include-macros true]
            [clojure.string :as str]
            [repl-tooling.editor-integration.connection :as conn]
            [repl-tooling.editor-helpers :as helpers]
            [repl-tooling.editor-integration.renderer :as render]
            [repl-tooling.eval :as eval]))

(defonce nvim (atom nil))

(defn info [text]
  (. ^js @nvim outWrite (str text "\n")))

(defn new-window [^js nvim buffer enter opts]
  (. nvim
    (request "nvim_open_win" #js [buffer enter (clj->js opts)])))

(defn- replace-buffer-text [^js buffer text]
  (.. (. buffer setOption "modifiable" true)
      (then #(.-length buffer))
      (then #(. buffer setLines (clj->js text)
               #js {:start 0 :end % :strictIndexing true}))
      (then #(. buffer setOption "modifiable" false))))

(defn- with-buffer [^js nvim ^js buffer where text]
  (let [lines (cond-> text (string? text) str/split-lines)]
    (.. buffer
        (setLines (clj->js lines)
                  #js {:start 0 :end 0 :strictIndexing true})
        (then #(. buffer setOption "modifiable" false))
        (then #(new-window nvim buffer false {:relative "cursor"
                                              :focusable true
                                              :anchor "NW"
                                              :width 50
                                              :height 10
                                              :row 0
                                              :col 10})))))

(defn open-window! [^js nvim where text]
  (.. nvim
      (createBuffer false true)
      (then #(with-buffer nvim % where text))))

(defonce results (atom {:windows #{}
                        :buffers {}}))
(defn clear-results! []
  (doseq [window (:windows @results)]
    (.close ^js window)
    (swap! results update :windows disj window))
  (swap! results assoc :buffers {}))

(defn new-result! [^js nvim]
  (let [w (open-window! nvim nil "...loading...")
        buffer (. w then #(.-buffer %))]
    (. w then #(swap! results update :windows conj %))
    (.. js/Promise
        (all #js [buffer w])
        (then (fn [[buffer window]]
                (.setOption window "wrap" false)
                (swap! results assoc-in [:buffers (.-id buffer) :window] window))))
    w))

(defonce state (atom {:clj-eval nil
                      :clj-aux nil
                      :cljs-eval nil
                      :commands nil}))

(defn- on-disconnect! []
  (info "Disconnected from REPL"))

(defn connect! [host port]
  (when-not (:clj-eval @state)
    (..
      (conn/connect! host port
                     {:on-disconnect on-disconnect!
                      :on-stdout identity
                      :on-eval identity
                      :on-stderr identity
                      :editor-data (fn []
                                     (let [nvim ^js @nvim
                                           code (.. nvim -buffer
                                                    (then #(.-lines %))
                                                    (then #(str/join "\n" %)))
                                           name (.. nvim -buffer
                                                    (then #(-name %)))]
                                       {:contents code
                                        :filename "foo.clj"
                                        :range [[0 0]
                                                [9 9]]}))})
      (then (fn [res]
              (swap! state assoc
                     :clj-eval (:clj/repl res)
                     :clj-aux (:clj/aux res)
                     :commands (:editor/commands res)))))))

(defn- get-cur-position []
  (let [lines (.. @nvim -buffer (then #(.getLines %)))
        row (.eval @nvim "line('.')")
        col (.eval @nvim "col('.')")]
    (.. js/Promise
        (all #js [(. lines then js->clj) (. row then dec) (. col then dec)])
        (then js->clj))))

(defn- replace-buffer [buffer-p string]
  (.. buffer-p (then #(replace-buffer-text % string))))

(defn- render-result-into-buffer [result buffer-p]
  (let [[string specials] (-> result render/txt-for-result render/repr->lines)]
    (. buffer-p then (fn [buffer]
                       (swap! results update-in [:buffers (.-id buffer)]
                              merge {:specials specials
                                     :result result})))
    (replace-buffer buffer-p string)))

(defn evaluate-block []
  (let [b (. (new-result! @nvim) then #(.-buffer %))]
    (.. b
        (then #(get-cur-position))
        (then (fn [[lines row col]]
                (let [code (helpers/read-next (str/join "\n" lines) (inc row) (inc col))]
                  (eval/evaluate (:clj-eval @state)
                                 code
                                 {}
                                 (fn [res]
                                   (let [parsed (helpers/parse-result res)
                                         result (render/parse-result parsed (:clj-eval @state))]
                                     (render-result-into-buffer result b))))))))))

(defn- run-fun-and-expand [fun buffer-p result]
  (fun #(render-result-into-buffer result buffer-p))
  (render-result-into-buffer result buffer-p))

(defn expand-block [^js nvim]
  (let [pos (get-cur-position)
        cur-buffer (.-buffer nvim)]
    (.. js/Promise
        (all #js [pos cur-buffer])
        (then (fn [[[_ row col] buffer]]
                (let [{:keys [result specials]} (get-in @results [:buffers (.-id buffer)])]
                  (some-> (get specials [row col])
                          (run-fun-and-expand cur-buffer result))))))))
