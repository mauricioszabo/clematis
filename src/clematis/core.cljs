(ns clematis.core
  (:require [clematis.commands :as cmds]))

(defonce nvim (atom nil))

(defn- echo []
  (. ^js @nvim outWrite "And I say hey,ey,eyeye! \n"))

(defn main [^js plugin cmd]
  (reset! nvim (.-nvim plugin))
  (reset! cmds/nvim (.-nvim plugin))
  (when cmd (cmd)))

(defn start
  "Hook to start. Also used as a hook for hot code reload."
  []
  (cmds/info "Reloaded Clematis")
  (js/setTimeout #(cmds/info "\n") 4000))

(defn connect! [plugin params]
  (reset! cmds/nvim (.-nvim plugin))
  (reset! nvim (.-nvim plugin))
  (let [[host port] (js->clj params)]
    (cmds/connect! host (int port))))

(def connect-socket (atom connect!))
(def eval-selection (atom main))
(def eval-top-level (atom #(main % cmds/evaluate-top-block)))
(def eval-block (atom #(main % cmds/evaluate-block)))
(def expand-view (atom #(main % (fn [] (cmds/expand-block @nvim)))))

(defn- run-tooling-cmd [nvim command]
  (def cmd (-> @cmds/state :commands command :command))
  (main nvim (-> @cmds/state :commands command :command)))

(def exports #js {:connect_socket #(@connect-socket %1 %2 %3)
                  :expand_view #(@expand-view %)
                  :disconnect #(main % cmds/disconnect!)

                  :eval_selection #(@eval-selection %)
                  :eval_top_level #(@eval-top-level %)
                  :eval_block #(@eval-block %)
                  :doc_for_var #(run-tooling-cmd % :doc-for-var)
                  :load_file #(run-tooling-cmd % :load-file)
                  :break_evaluation #(run-tooling-cmd % :break-evaluation)
                  :connect_embedded #(run-tooling-cmd % :connect-embedded)})
