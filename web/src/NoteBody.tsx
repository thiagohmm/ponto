import { memo, useEffect, useLayoutEffect, useRef, type MutableRefObject, type RefObject } from "react";
import {
  caretIn, clean, closestRun, dirty, dropEmptyRuns, lineOf, linePoint,
  linesOf, lookAt, makeRun, markPlaceholder, mergeLook, placeIn, posOf,
  renderEditor, restyle, sameLook, selectSpan, serializeEditor, splitRunAtCaret,
  TODO_PREFIX, ZWSP, type Look, type StylePatch,
} from "./noteBodyDom";

export type { Look, StylePatch };

const Surface = memo(function Surface({
  placeholder, surface,
}: {
  placeholder: string;
  surface: RefObject<HTMLDivElement | null>;
}) {
  return (
    <div
      ref={surface}
      className="vault-body"
      contentEditable
      suppressContentEditableWarning
      role="textbox"
      aria-multiline="true"
      spellCheck
      data-placeholder={placeholder}
    />
  );
});

export type NoteBodyHandle = {
  focus(): void;
  insertTodo(): void;
  holdSelection(): void;
  apply(patch: StylePatch): void;
};

type Props = {
  body: string;
  placeholder: string;
  onChange: (body: string) => void;
  onLook: (look: Look) => void;
  handle: MutableRefObject<NoteBodyHandle | null>;
};

export function NoteBody({ body, placeholder, onChange, onLook, handle }: Props) {
  const surface = useRef<HTMLDivElement>(null);
  const bodyRef = useRef(body);
  const onChangeRef = useRef(onChange);
  const onLookRef = useRef(onLook);
  const skip = useRef(false);
  const pending = useRef<Look | null>(null);
  const armed = useRef<HTMLElement | null>(null);
  const held = useRef<Range | null>(null);
  const freeze = useRef(false);
  const mute = useRef(0);
  bodyRef.current = body;
  onChangeRef.current = onChange;
  onLookRef.current = onLook;

  function emit() {
    const root = surface.current;
    if (!root) return;
    markPlaceholder(root);
    const next = serializeEditor(root);
    if (next === bodyRef.current) return;
    skip.current = true;
    onChangeRef.current(next);
  }

  function report(look: Look) {
    onLookRef.current(look);
  }

  function focusEnd() {
    const root = surface.current;
    if (!root) return;
    root.focus();
    const lines = linesOf(root);
    const last = lines[lines.length - 1];
    if (!last) return;
    const range = document.createRange();
    range.selectNodeContents(last);
    range.collapse(false);
    const sel = window.getSelection();
    sel?.removeAllRanges();
    sel?.addRange(range);
  }

  function splitLine() {
    const root = surface.current;
    if (!root) return;
    const range = caretIn(root);
    if (!range) return;
    if (!range.collapsed) range.deleteContents();
    const line = lineOf(root, range.startContainer);
    if (!line) return;
    const tail = document.createRange();
    tail.selectNodeContents(line);
    tail.setStart(range.startContainer, range.startOffset);
    const frag = tail.extractContents();
    const next = document.createElement("div");
    next.appendChild(frag);
    if (!next.textContent && !next.querySelector("br")) next.appendChild(document.createElement("br"));
    if (!line.textContent && !line.querySelector("br")) line.appendChild(document.createElement("br"));
    line.after(next);
    placeIn(next, 0);
  }

  function insertPlain(text: string) {
    const root = surface.current;
    if (!root) return;
    const range = caretIn(root);
    if (!range) return;
    range.deleteContents();
    const sel = window.getSelection();
    const parts = text.replaceAll("\r\n", "\n").replaceAll("\r", "\n").split("\n");
    const put = (chunk: string) => {
      if (!chunk) return;
      const cur = caretIn(root);
      if (!cur) return;
      const node = document.createTextNode(chunk);
      cur.insertNode(node);
      const line = lineOf(root, node);
      if (line) line.querySelectorAll("br").forEach((br) => {
        if (clean(line.textContent ?? "")) br.remove();
      });
      const after = document.createRange();
      after.setStartAfter(node);
      after.collapse(true);
      sel?.removeAllRanges();
      sel?.addRange(after);
    };
    put(parts[0] ?? "");
    for (let i = 1; i < parts.length; i++) {
      splitLine();
      put(parts[i] ?? "");
    }
  }

  function remember(range: Range | null) {
    if (!range) return;
    const root = surface.current;
    if (!root || !range.startContainer.isConnected || !root.contains(range.startContainer)) return;
    held.current = range.cloneRange();
  }

  function holdSelection() {
    freeze.current = true;
    const root = surface.current;
    const sel = window.getSelection();
    if (!root || !sel || sel.rangeCount === 0 || !sel.anchorNode || !root.contains(sel.anchorNode)) return;
    remember(sel.getRangeAt(0));
  }

  function rangeForApply(root: HTMLElement): Range | null {
    const live = caretIn(root);
    const saved = held.current;
    const savedOk = Boolean(saved && saved.startContainer.isConnected && root.contains(saved.startContainer));
    if (savedOk && saved && !saved.collapsed && (!live || live.collapsed)) {
      const sel = window.getSelection();
      sel?.removeAllRanges();
      sel?.addRange(saved);
      return saved;
    }
    if (live) return live;
    if (!savedOk || !saved) return null;
    const sel = window.getSelection();
    sel?.removeAllRanges();
    sel?.addRange(saved);
    return saved;
  }

  function focusCaret() {
    const root = surface.current;
    if (!root) return;
    root.focus();
    if (armed.current?.firstChild) {
      const node = armed.current.firstChild;
      placeIn(node, node.textContent?.length ?? 0);
      return;
    }
    const sel = window.getSelection();
    if (sel && sel.rangeCount && sel.anchorNode && root.contains(sel.anchorNode)) {
      remember(sel.getRangeAt(0));
    }
  }

  function arm(look: Look) {
    const root = surface.current;
    if (!root) return;
    pending.current = look.c || look.f ? look : null;
    if (!pending.current) {
      armed.current = null;
      dropEmptyRuns(root, null);
      return;
    }
    const range = caretIn(root);
    if (!range || !range.collapsed) return;
    const run = closestRun(range.startContainer);
    if (run && sameLook(run, look)) {
      armed.current = run;
      return;
    }
    if (run) splitRunAtCaret(run, caretIn(root) ?? range);
    const at = caretIn(root);
    if (!at) return;
    const span = makeRun(ZWSP, look);
    at.insertNode(span);
    const text = span.firstChild;
    if (text) placeIn(text, text.textContent?.length ?? 1);
    armed.current = span;
  }

  function apply(patch: StylePatch) {
    const root = surface.current;
    if (!root) return;
    mute.current++;
    const range = rangeForApply(root);
    if (!range) root.focus();
    if (patch.clear) {
      pending.current = null;
      armed.current = null;
      if (range && !range.collapsed) restyle(root, range, { clear: true });
      dropEmptyRuns(root, null);
      emit();
      focusCaret();
      freeze.current = false;
      report(lookAt(root));
      window.setTimeout(() => { mute.current = Math.max(0, mute.current - 1); }, 0);
      return;
    }
    if (range && !range.collapsed) {
      pending.current = null;
      armed.current = null;
      restyle(root, range, patch);
      dropEmptyRuns(root, null);
      emit();
      focusCaret();
      freeze.current = false;
      report(lookAt(root));
      window.setTimeout(() => { mute.current = Math.max(0, mute.current - 1); }, 0);
      return;
    }
    const base = pending.current ?? (range ? lookAt(root) : {});
    const next = mergeLook(base, patch);
    arm(next);
    emit();
    focusCaret();
    freeze.current = false;
    report(next);
    window.setTimeout(() => { mute.current = Math.max(0, mute.current - 1); }, 0);
  }

  function insertTodo() {
    const root = surface.current;
    if (!root) return;
    mute.current++;
    root.focus();
    const range = caretIn(root);
    const line = range ? lineOf(root, range.startContainer) : null;
    const caret = line && range ? linePoint(line, range).caret : 0;
    if (!line || caret === 0) insertPlain(TODO_PREFIX);
    else {
      splitLine();
      const nextRange = caretIn(root);
      const next = nextRange ? lineOf(root, nextRange.startContainer) : null;
      if (next) {
        const br = next.querySelector("br");
        if (br && !clean(next.textContent ?? "")) br.remove();
        const prefix = document.createTextNode(TODO_PREFIX);
        next.insertBefore(prefix, next.firstChild);
        placeIn(prefix, prefix.length);
      }
    }
    emit();
    window.setTimeout(() => { mute.current = Math.max(0, mute.current - 1); }, 0);
  }

  function onEnter(e: KeyboardEvent) {
    if (e.key !== "Enter") return;
    e.preventDefault();
    const root = surface.current;
    if (!root) return;
    pending.current = null;
    const keep = armed.current;
    armed.current = null;
    dropEmptyRuns(root, null);
    if (keep && !keep.isConnected) { /* removed */ }
    const range = caretIn(root);
    const line = range ? lineOf(root, range.startContainer) : null;
    if (!line || !range || e.shiftKey) {
      splitLine();
      emit();
      report({});
      return;
    }
    const measured = linePoint(line, range);
    const caret = measured.caret;
    const upto = measured.text.slice(0, caret);
    const m = /^( *)[-*] \[([ xX])\](.*)$/.exec(upto);
    if (!m) {
      splitLine();
      emit();
      report({});
      return;
    }
    if (m[3].trim() === "") {
      const start = document.createRange();
      start.selectNodeContents(line);
      start.setEnd(range.startContainer, range.startOffset);
      start.deleteContents();
      if (!line.textContent && !line.querySelector("br")) line.appendChild(document.createElement("br"));
      placeIn(line, 0);
      emit();
      report({});
      return;
    }
    splitLine();
    const nextRange = caretIn(root);
    const next = nextRange ? lineOf(root, nextRange.startContainer) : null;
    if (next) {
      const br = next.querySelector("br");
      if (br && clean(next.textContent ?? "") === "") br.remove();
      const prefix = document.createTextNode(m[1] + TODO_PREFIX);
      next.insertBefore(prefix, next.firstChild);
      placeIn(prefix, prefix.length);
    }
    emit();
    report({});
  }

  useLayoutEffect(() => {
    const root = surface.current;
    if (!root) return;
    renderEditor(root, bodyRef.current);
  }, []);

  useEffect(() => {
    const root = surface.current;
    if (!root) return;
    if (skip.current) {
      skip.current = false;
      return;
    }
    if (serializeEditor(root) === body) return;
    renderEditor(root, body);
  }, [body]);

  useEffect(() => {
    const root = surface.current;
    if (!root) return;

    function onInput() {
      const el = surface.current;
      if (!el) return;
      if (dirty(el)) {
        const sel = window.getSelection();
        let a = 0;
        let b = 0;
        if (sel && sel.rangeCount && sel.anchorNode && el.contains(sel.anchorNode)) {
          const r = sel.getRangeAt(0);
          a = posOf(el, r.startContainer, r.startOffset);
          b = posOf(el, r.endContainer, r.endOffset);
        }
        const text = serializeEditor(el);
        renderEditor(el, text);
        selectSpan(el, a, b);
      }
      emit();
    }

    function onPaste(e: ClipboardEvent) {
      e.preventDefault();
      insertPlain(e.clipboardData?.getData("text/plain") ?? "");
      emit();
    }

    function onKey(e: KeyboardEvent) {
      if (e.key === "Enter") onEnter(e);
    }

    function onSel() {
      if (mute.current || freeze.current) return;
      const el = surface.current;
      if (!el) return;
      const sel = window.getSelection();
      if (!sel?.anchorNode || !el.contains(sel.anchorNode)) return;
      if (sel.rangeCount) remember(sel.getRangeAt(0));
      if (armed.current && sel.isCollapsed && armed.current.contains(sel.anchorNode)) {
        report(pending.current ?? {});
        return;
      }
      if (pending.current || armed.current) {
        pending.current = null;
        armed.current = null;
        dropEmptyRuns(el, null);
        emit();
      }
      report(lookAt(el));
    }

    root.addEventListener("input", onInput);
    root.addEventListener("paste", onPaste);
    root.addEventListener("keydown", onKey);
    document.addEventListener("selectionchange", onSel);
    return () => {
      root.removeEventListener("input", onInput);
      root.removeEventListener("paste", onPaste);
      root.removeEventListener("keydown", onKey);
      document.removeEventListener("selectionchange", onSel);
    };
  }, []);

  handle.current = { focus: focusEnd, insertTodo, holdSelection, apply };

  return <Surface placeholder={placeholder} surface={surface} />;
}
