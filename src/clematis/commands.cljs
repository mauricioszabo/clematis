(ns clematis.commands
  (:require [clojure.core.async :as async :include-macros true]
            [clojure.string :as str]
            [cljs.core.async.impl.protocols :as a-protocols]
            [nvim.parser :as parser]))

; (defn text [^js nvim]
;   (.. nvim -buffer
;       (then #(.-lines %))
;       (then js->clj)))

(defn new-window [^js nvim buffer enter opts]
  (. nvim
    (request "nvim_open_win" #js [buffer enter (clj->js opts)])))

(defn- with-buffer [^js nvim ^js buffer where text]
  (.. buffer
      (setLines (clj->js (str/split-lines text))
                #js {:start 0 :end 0 :strictIndexing true})
      (then #(. buffer setOption "modifiable" false))
      (then #(new-window nvim buffer false {:relative "cursor"
                                            :focusable true
                                            :anchor "NW"
                                            :width 50
                                            :height 10
                                            :row 0
                                            :col 10}))))

(defn open-window! [^js nvim where text]
  (.. nvim
      (createBuffer false true)
      (then #(with-buffer nvim % where text))))

(defonce result-windows (atom #{}))
(defn new-result! [^js nvim]
  (let [w (open-window! nvim nil "...loading...")]
    (. w (then #(swap! result-windows conj w)))
    w))

(defn clear-results! []
  (doseq [window @result-windows]
    (.close ^js window)
    (swap! result-windows disj window)))
