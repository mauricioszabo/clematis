(ns clematis.dom
  (:require ["jsdom" :refer [JSDOM]]
            [reagent.core :as r]
            [reagent.dom :as rdom]))

; const dom = new JSDOM(`<body>
;                       <script>document.body.appendChild(document.createElement("hr"));</script>
;                       </body>`, { runScripts: "dangerously"});

(defonce dom
  (JSDOM. "<body><div id='app'></div></body>"
          #js {:runScripts "dangerously"
               :pretendToBeVisual true}))

(defonce state (r/atom 0))
(defn counter []
  [:div "Counter: " @state])

#_
(swap! state inc)

#_
(rdom/render [counter]
             (.. dom -window -document (querySelector "div#app")))

#_
(.. dom -window -document -body (querySelector "div#app") -innerHTML)
