(ns clematis.core
  (:require [clematis.commands :as cmds]
            [promesa.core :as p]
            [tango.integration.connection :as conn]))

(defonce commands (atom nil))
(def e (atom nil))

#_
((->> @commands
      (into {})
      :disconnect
      :command))
(js/console.error "Help?")
(defn- connect-socket [params]
  (p/catch
   (p/let [callbacks {:on-disconnect (fn []
                                       (cmds/info "Disconnected from REPL"))
                      ; :on-start-eval (constantly nil)
                      :on-eval (fn [res] (cmds/info (pr-str res)))
                      ; :get-console (constantly nil)
                      :on-diagnostic (constantly nil)
                      ; :get-rendered-results (constantly nil)
                      ; :open-editor (constantly nil)
                      ; :prompt (constantly nil)
                      :config-directory "/tmp/clematis"
                      :editor-data (constantly {:range [[0 0] [0 0]]
                                                :filename "foo.clj"
                                                :contents "(range 5)"})
                      :notify #(cmds/info (pr-str %))
                      :register-commands #(reset! commands %)}
           state (conn/connect! "localhost" 44129 callbacks)]
     (def global-state state)
     (cmds/info "Connected!"))
   #(do
      (reset! e %)
      (js/console.error %)
      (cmds/info "Error connecting: "))))

; (.-stack
;  @e)

(defn exports [^js plugin]
  ; (js/console.log "Trying...")
  (reset! cmds/nvim (.-nvim plugin))
  #js {:connect_socket #(connect-socket %)})

(defn ^:dev/after-load reload []
  (cmds/info "Reloaded Clematis")
  (js/setTimeout #(cmds/info "\n") 4000))
