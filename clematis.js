let cljs

module.exports = plugin => {
  plugin.setOptions({ dev: true });

  function cmds() {
    if(!cljs) cljs = require('./js/index')
    return cljs
  }

  plugin.registerCommand('ConnnectSocketREPL', async (params) => {
    cmds().connect_socket(plugin, params)
  }, { sync: false, nargs: '*'
 });
  plugin.registerCommand('ConnnectEmbedded', () => {
    cmds().connect_embedded(plugin)
  }, { sync: false });
  plugin.registerCommand('DisconnectREPLS', () => {
    cmds().disconnect(plugin)
  }, { sync: false });
  plugin.registerCommand('EvalSelection', () => {
    cmds().eval_selection(plugin)
  }, { sync: false });
  plugin.registerCommand('EvalTopLevel', () => {
    cmds().eval_top_level(plugin)
  }, { sync: false });
  plugin.registerCommand('EvalBlock', () => {
    cmds().eval_block(plugin)
  }, { sync: false });

  plugin.registerCommand('ClematisExpandView', () => {
    cmds().expand_view(plugin)
  }, { sync: false });
};
