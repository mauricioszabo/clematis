(ns clematis.commands)

(defonce nvim (atom nil))

(defn info [text]
  (. ^js @nvim outWrite (str text "\n")))
