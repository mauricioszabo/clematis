(ns clematis.commands
  (:require [clojure.core.async :as async :include-macros true]
            [clojure.string :as str]
            [repl-tooling.editor-integration.connection :as conn]
            [repl-tooling.editor-helpers :as helpers]
            [repl-tooling.editor-integration.renderer :as render]
            [repl-tooling.eval :as eval]))

(defonce nvim (atom nil))

(defn new-window [^js nvim buffer enter opts]
  (. nvim
    (request "nvim_open_win" #js [buffer enter (clj->js opts)])))

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

(defonce results (atom {:windows #{} :last-id nil}))
(defn new-result! [^js nvim]
  (let [w (open-window! nvim nil "...loading...")]
    (. w (then #(swap! results update :windows conj w)))
    w))

(defn clear-results! []
  (doseq [window (:windows @results)]
    (.close ^js window)
    (swap! results update :windows disj window)))

(defonce state (atom {:clj-eval nil
                      :clj-aux nil
                      :cljs-eval nil
                      :commands nil}))

(defn connect! []
  (when-not (:clj-eval @state)
    (..
      (conn/connect! "localhost" 9000
                     {:on-disconnect identity
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

(defn- parse-specials [specials last-elem curr-text elem]
  (let [txt-size (-> elem (nth 1) count)
        curr-row (count curr-text)
        fun (peek elem)]
    (reduce (fn [specials col] (assoc specials [last-elem col] fun))
            specials (range curr-row (+ curr-row txt-size)))))

(defn- parse-elem [position lines specials]
  (let [[elem text function] position
        last-elem (-> lines count dec)
        curr-text (peek lines)]
    (case elem
      :row (recur (rest position) (conj lines "") specials)
      :text [(assoc lines last-elem (str curr-text text)) specials]
      :button [(assoc lines last-elem (str curr-text " " text " "))
               (parse-specials specials last-elem curr-text position)]
      :expand [(assoc lines last-elem (str curr-text text " "))
               (parse-specials specials last-elem curr-text position)]
      (reduce (fn [[lines specials] position] (parse-elem position lines specials))
              [lines specials] position))))

(defn result->string [result-struct]
  (parse-elem result-struct [] {}))

#_
(connect!)
#_
(eval/evaluate (:clj-eval @state)
               "(map str (range 100))"
               {}
               (fn [res]
                 (let [parsed (helpers/parse-result res)
                       result (render/parse-result parsed (:clj-eval @state))]
                   (->> result
                        render/txt-for-result
                        result->string
                        first
                        (open-window! @nvim nil)))))
