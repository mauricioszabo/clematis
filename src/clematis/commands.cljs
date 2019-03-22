(ns clematis.commands
  (:require [clojure.core.async :as async :include-macros true]
            [clojure.string :as str]
            [repl-tooling.editor-integration.connection :as conn]
            [repl-tooling.editor-helpers :as helpers]
            [repl-tooling.editor-integration.renderer :as render]
            [reagent.core :as r]
            [repl-tooling.eval :as eval]
            [reagent.dom.server :as r-server]
            ["jsdom" :refer [JSDOM] :as jsdom]))

; jsdom
(defonce nvim (atom nil))

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

(defn- new-dom [view]
  (let [dom (new JSDOM
              "<!doctype html><html><body>
<style>
a.chevron.closed::after {
  content: \">\";
}
a.chevron.opened::after {
  content: \"v\";
}
div.result div.row {
  flex-direction: column;
}
</style>
<div id='res'></div></body></html>"
              #js {:runScripts "dangerously"
                   :resources "usable"})]

    (aset js/global "window" (.-window dom))
    (r/render view (.. dom -window -document (querySelector "#res")))
    dom))

#_#_#_
(.. dom -window -document (querySelector "body") -innerHTML)
(.. dom -window -document (querySelector "#res") -innerHTML)
(.. dom -window -document (querySelector "#res") -textContent)

#_
(connect!)
#_
(eval/evaluate (:clj-eval @state)
               "(map str (range 100))"
               {}
               (fn [res]
                 (let [parsed (helpers/parse-result res)
                       result (render/parse-result parsed (:clj-eval @state))
                       view (render/view-for-result result)]
                   (def parsed parsed)
                   (def result result)
                   (def view view)
                   (def dom (new-dom view))
                   (open-window! @nvim nil (pr-str view)))))

#_
(.. dom -window -document (querySelector "#res a") click)
#_#_
(r-server/render-to-string view)
(r-server/render-to-static-markup view)

#_
(r/render (render/view-for-result result)
          (.. dom -window -document (querySelector "#res")))
#_
(.. dom -window -document (querySelector "#res .children") -textContent)
#_
(.. dom -window -document (querySelector "body") -innerHTML)
