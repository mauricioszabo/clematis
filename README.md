# Clematis
CLVIM: Clematis virginiana (devil's darning needles)

![Clematis](https://raw.githubusercontent.com/mauricioszabo/clematis/master/doc/clematis.jpg)

Clematis is a NeoVIM package (version >= 4.0, because we're using Floating Windows feature) for Clojure and ClojureScript.
Is an implementation of REPL-Tooling package for NeoVIM.

## Why another plug-in?

Clematis is focused on data visualization. It is an implementation of REPL Tooling, the same tool that powers Atom's package Chlorine, on the text-based editor NeoVIM - no GUI is necessary. The idea is to create a full powered plug-in for NeoVIM and other text-based editors focused on extensability - one that you could hit a keystroke and literally see your data, dive as deep as you want on it, and use these visualizations to help you code your solution (for example, when you get a Java object as a result, you can see all methods/constructors/attributes on it without needing external tools like Javadoc and GOTO Definition).

![](https://github.com/mauricioszabo/clematis/blob/master/doc/example.gif?raw=true)

## Installation
You need to have the newest NeoVIM installed. Now, on the official site, they present packages for Ubuntu and Debian, but they're outdated. Use the AppImage.

You also need to have Node.JS installed. Once installed, install the bindings for VIM globally (yeah, not ideal but NeoVIM with Node are not that permissive):

```
$ npm install -g neovim
```

Now you're ready to go!

## What's with the name?

Clematis Virginiana is sometimes abbreviated as CLVIM, which is perfect for the job.

It is also a beautiful name, and a beautiful flower, so be it!

## Disclaimer:
Nothing is working that well so far. It's still too early to use this plug-in. Contributions are welcome :)

## Related Projects:
* [Chlorine](https://github.com/mauricioszabo/atom-chlorine)
* [REPL Tooling](https://github.com/mauricioszabo/repl-tooling)
