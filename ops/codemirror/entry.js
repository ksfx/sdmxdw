// CodeMirror 6 bundle for the SDMxDW process editor (built with esbuild, see cm-build in the docs)
import { EditorView, basicSetup } from "codemirror";
import { keymap } from "@codemirror/view";
import { EditorState } from "@codemirror/state";
import { indentWithTab } from "@codemirror/commands";
import { StreamLanguage, indentUnit } from "@codemirror/language";
import { python } from "@codemirror/lang-python";
import { sql } from "@codemirror/lang-sql";
import { yaml } from "@codemirror/lang-yaml";
import { json } from "@codemirror/lang-json";
import { r } from "@codemirror/legacy-modes/mode/r";

const languages = {
  python: () => python(),
  r: () => StreamLanguage.define(r),
  sql: () => sql(),
  yaml: () => yaml(),
  json: () => json(),
};

/** Turns a <textarea> into an editor; the textarea keeps the content for form submits. */
window.SdmxdwEditor = {
  attach(textarea, language, onSave) {
    const extensions = [
      basicSetup,
      keymap.of([indentWithTab, { key: "Mod-s", preventDefault: true, run: () => { onSave && onSave(); return true; } }]),
      indentUnit.of(language === "r" ? "  " : "    "),
      EditorView.updateListener.of((u) => { if (u.docChanged) { textarea.value = u.state.doc.toString(); textarea.dispatchEvent(new Event("input")); } }),
      EditorView.theme({ "&": { fontSize: "13px", border: "1px solid oklch(var(--b3))", borderRadius: "0.5rem" }, ".cm-scroller": { fontFamily: "ui-monospace, monospace", minHeight: "60vh" } }),
    ];
    if (textarea.readOnly) extensions.push(EditorState.readOnly.of(true));
    if (languages[language]) extensions.push(languages[language]());
    const view = new EditorView({ doc: textarea.value, extensions });
    textarea.style.display = "none";
    textarea.parentNode.insertBefore(view.dom, textarea);
    return view;
  },
};
