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

(defn new-result! [^js nvim]
  (let [w (open-window! nvim nil "...loading...")
        buffer (. w then #(.-buffer %))]
    (.. js/Promise
        (all #js [buffer w])
        (then (fn [[buffer window]]
                (.setOption window "wrap" false))))
    (.. w
        (then #(aset nvim "window" %))
        (then #(do
                 (.command nvim "map <buffer> <Return> :ClematisExpandView<CR>")
                 (.command nvim "map <buffer> o :ClematisExpandView<CR>"))))
    w))

(def ^private original-state {:clj-eval nil
                              :clj-aux nil
                              :cljs-eval nil
                              :commands nil})
(defonce state (atom original-state))

(defn- on-disconnect! []
  (reset! state original-state)
  (info "Disconnected from REPL"))

(defn- get-vim-data []
  (when-let [nvim ^js @nvim]
    (let [buffer (. nvim -buffer)
          file-name (. buffer then #(.-name %))
          code (.. buffer
                   (then #(.-lines %))
                   (then #(str/join "\n" %)))
          row (.eval nvim "line('.')")
          col (.eval nvim "col('.')")]
      (.. js/Promise
          (all #js [code file-name row col])
          (then (fn [[code file-name row col]]
                  {:contents code
                   :filename file-name
                   :range [[(dec row) (dec col)] [(dec row) (dec col)]]}))))))

(defonce eval-state (atom {:window nil
                           :id nil}))

(defn- on-start-eval [eval-id range]
  (when-let [existing-win ^js (:window @eval-state)]
    (.close existing-win))
  (let [window ^js (new-result! @nvim)]
    (. window then #(swap! eval-state assoc
                           :window %
                           :id eval-id))))

(defn- replace-buffer [buffer-p string]
  (.. buffer-p (then #(replace-buffer-text % string))))

(defonce commands-in-buffer (atom {}))
(defn- render-result-into-buffer [result buffer-p]
  (let [[string specials] (-> result render/txt-for-result render/repr->lines)]
    (. buffer-p then (fn [buffer]
                       (swap! commands-in-buffer update-in [:buffers (.-id buffer)]
                              merge {:specials specials
                                     :result result})))
    (replace-buffer buffer-p string)))

(defn- on-end-eval [result eval-id range]
  (when (and (= eval-id (:id @eval-state))
             (:window @eval-state))
    (let [win ^js (:window @eval-state)
          result (render/parse-result result (:clj-eval @state))]
      (->> win .-buffer (render-result-into-buffer result)))))

(defn connect! [host port]
  (when-not (:clj-eval @state)
    (..
      (conn/connect! host port
                     {:on-disconnect on-disconnect!
                      :on-stdout identity
                      :on-stderr identity
                      :editor-data get-vim-data
                      :on-eval on-end-eval
                      :on-start-eval on-start-eval})
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

(defn- run-fun-and-expand [fun buffer-p result]
  (fun #(render-result-into-buffer result buffer-p)))

(defn expand-block [^js nvim]
  (let [pos (get-cur-position)
        cur-buffer (.-buffer nvim)]
    (.. js/Promise
        (all #js [pos cur-buffer])
        (then (fn [[[_ row col] buffer]]
                (let [{:keys [result specials]} (get-in @commands-in-buffer
                                                        [:buffers (.-id buffer)])]
                  (some-> (get specials [row col])
                          (run-fun-and-expand cur-buffer result))))))))

(defn evaluate-top-block []
  (let [cmd (-> @state :commands :evaluate-top-block :command)]
    (and cmd (cmd))))

(defn evaluate-block []
  (let [cmd (-> @state :commands :evaluate-block :command)]
    (and cmd (cmd))))

(defn disconnect! []
  (let [cmd (-> @state :commands :disconnect :command)]
    (and cmd (cmd))))
