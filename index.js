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
  plugin.registerCommand('EvalSelection', () => {
    cmds().eval_selection(plugin)
  }, { sync: false });
  plugin.registerCommand('EvalTopLevel', () => {
    cmds().eval_top_level(plugin)
  }, { sync: false });
  plugin.registerCommand('EvalBlock', () => {
    cmds().eval_block(plugin)
  }, { sync: false });

  plugin.registerFunction('expandView', () => {
    cmds().expand_view(plugin)
  }, { sync: false });
};

//
// module.exports = plugin => {
//   plugin.setOptions({ dev: false })
//
//   plugin.registerCommand('EchoMessage', async () => {
//       try {
//         await plugin.nvim.outWrite('Dayman (ah-ah-ah) \n');
//       } catch (err) {
//         console.error(err);
//       }
//     }, { sync: false });
//
//   plugin.registerFunction('SetLines', () => {
//     return plugin.nvim.setLine('May I offer you an egg in these troubling times')
//       .then(() => console.log('Line should be set'))
//   }, {sync: false})
//
//   plugin.registerAutocmd('BufEnter', async (fileName) => {
//     await plugin.nvim.buffer.append('BufEnter for a JS File?')
//   }, {sync: false, pattern: '*.js', eval: 'expand("<afile>")'})
//
//   plugin.registerCommand('ConnnectSocketREPL', cljs.connect_socket, { sync: false });
//   plugin.registerCommand('ConnnectEmbedded', cljs.connect_embedded, { sync: false });
//   plugin.registerCommand('EvalSelection', cljs.eval_selection, { sync: false });
//   plugin.registerCommand('EvalTopLevel', cljs.eval_top_level, { sync: false });
//   plugin.registerCommand('EvalBlock', cljs.eval_block, { sync: false });
//   // plugin.registerFunction('SetLines',() => {
//   //   return plugin.nvim.setLine('May I offer you an egg in these troubling times')
//   //     .then(() => console.log('Line should be set'))
//   // }, {sync: false})
//   //
//   // plugin.registerAutocmd('BufEnter', async (fileName) => {
//   //   await plugin.nvim.buffer.append('BufEnter for a JS File?')
//   // }, {sync: false, pattern: '*.js', eval: 'expand("<afile>")'})
// };
