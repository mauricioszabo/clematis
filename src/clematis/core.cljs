(ns clematis.core
  (:require [clematis.commands :as cmds]))

(defonce nvim (atom nil))

(defn- echo []
  (. ^js @nvim outWrite "And I say hey,ey,eyeye! \n"))

(defn main [^js plugin]
  (reset! nvim (.-nvim plugin))
  (reset! cmds/nvim (.-nvim plugin))
  (cmds/open-window! @nvim nil "+ {:foo 10 :bar 20}"))

(defn start
  "Hook to start. Also used as a hook for hot code reload."
  []
  (. ^js @nvim outWrite "Reloaded Clematis \n")
  (js/setTimeout #(. ^js @nvim outWrite "\n")
                 4000))

(def connect-socket (atom main))
(def connect-embedded (atom main))
(def eval-selection (atom main))
(def eval-top-level (atom main))
(def eval-block (atom main))
(def expand-view (atom main))


(def exports #js {:connect_socket #(@connect-socket %)
                  :connect_embedded #(@connect-embedded %)
                  :eval_selection #(@eval-selection %)
                  :eval_top_level #(@eval-top-level %)
                  :eval_block #(@eval-block %)
                  :expand_view #(@expand-view %)})
