# CodeMirror bundle for the process editor

`src/main/resources/static/js/vendor/codemirror.js` is a local bundle (no CDN at runtime, works
without internet access). Rebuild:

```sh
npm i codemirror@6 @codemirror/lang-python@6 @codemirror/lang-sql@6 @codemirror/lang-yaml@6 \
      @codemirror/lang-json@6 @codemirror/legacy-modes@6 @codemirror/language@6 \
      @codemirror/commands@6 @codemirror/view@6 @codemirror/state@6 esbuild@0.24
npx esbuild entry.js --bundle --minify --format=iife --outfile=codemirror.js
cp codemirror.js ../../src/main/resources/static/js/vendor/codemirror.js
```

Built with codemirror 6.0.2. Exposes `window.SdmxdwEditor.attach(textarea, language, onSave)`
with syntax highlighting for Python, R, SQL, YAML and JSON.
Rebuild: regenerate `codemirror.js.LICENSE.txt` (licence texts of all bundled packages from `node_modules`) together with the bundle.
