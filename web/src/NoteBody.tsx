import { memo, useEffect, useLayoutEffect, useRef, type MutableRefObject, type RefObject } from "react";
import { encodeLine, runStyle, scanLine, validColor, validFont, type Mark } from "./md";

const ZWSP = "\u200b";
const TODO_PREFIX = "- [ ] ";

export type Look = { c?: string; f?: string };
export type StylePatch = { color?: string; font?: string; clear?: boolean };

export type NoteBodyHandle = {
  focus(): void;
  insertTodo(): void;
  apply(patch: StylePatch): void;
};

type Props = {
  body: string;
  placeholder: string;
  onChange: (body: string) => void;
  onLook: (look: Look) => void;
  handle: MutableRefObject<NoteBodyHandle | null>;
};

function clean(text: string): string {
  return text.replaceAll(ZWSP, "");
}

function paint(span: HTMLElement) {
  const style = runStyle(validColor(span.dataset.c), validFont(span.dataset.f));
  span.style.color = style.color ?? "";
  span.style.fontFamily = style.fontFamily ?? "";
}

function makeRun(text: string, look: Look): HTMLSpanElement {
  const span = document.createElement("span");
  span.className = "md-run";
  if (look.c) span.dataset.c = look.c;
  if (look.f) span.dataset.f = look.f;
  paint(span);
  span.textContent = text;
  return span;
}

function renderLine(line: string): HTMLDivElement {
  const div = document.createElement("div");
  const runs = scanLine(line).filter((r) => r.text);
  if (!runs.length) {
    div.appendChild(document.createElement("br"));
    return div;
  }
  for (const run of runs) {
    if (run.c || run.f) div.appendChild(makeRun(run.text, { c: run.c, f: run.f }));
    else div.appendChild(document.createTextNode(run.text));
  }
  return div;
}

function renderEditor(root: HTMLElement, body: string) {
  const frag = document.createDocumentFragment();
  for (const line of body.split("\n")) frag.appendChild(renderLine(line));
  root.replaceChildren(frag);
  markPlaceholder(root);
}

function domRuns(el: HTMLElement): Mark[] {
  const runs: Mark[] = [];
  const push = (c: string | undefined, f: string | undefined, text: string) => {
    text = clean(text);
    if (!text) return;
    const prev = runs[runs.length - 1];
    if (prev && prev.c === c && prev.f === f) prev.text += text;
    else runs.push({ c, f, text });
  };
  const walk = (node: Node, c?: string, f?: string) => {
    if (node.nodeType === Node.TEXT_NODE) {
      push(c, f, node.textContent ?? "");
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    const child = node as HTMLElement;
    if (child.tagName === "BR") return;
    let nc = c;
    let nf = f;
    if (child.classList.contains("md-run")) {
      nc = validColor(child.dataset.c);
      nf = validFont(child.dataset.f);
    }
    child.childNodes.forEach((k) => walk(k, nc, nf));
  };
  el.childNodes.forEach((k) => walk(k));
  return runs;
}

function serializeEditor(root: HTMLElement): string {
  const blocks = [...root.children].filter((n) => n.tagName === "DIV");
  if (!blocks.length) return encodeLine(domRuns(root));
  return blocks.map((b) => encodeLine(domRuns(b as HTMLElement))).join("\n");
}

function linesOf(root: HTMLElement): HTMLElement[] {
  return [...root.children].filter((n) => n.tagName === "DIV") as HTMLElement[];
}

function textLen(node: Node): number {
  if (node.nodeType === Node.TEXT_NODE) return (node.textContent ?? "").length;
  if (node.nodeType !== Node.ELEMENT_NODE) return 0;
  if ((node as HTMLElement).tagName === "BR") return 0;
  let n = 0;
  node.childNodes.forEach((k) => { n += textLen(k); });
  return n;
}

function posOf(root: HTMLElement, container: Node, offset: number): number {
  let count = 0;
  const consider = (node: Node): boolean => {
    if (node.nodeType === Node.TEXT_NODE) {
      if (node === container) {
        count += Math.min(offset, (node.textContent ?? "").length);
        return true;
      }
      count += (node.textContent ?? "").length;
      return false;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return false;
    const el = node as HTMLElement;
    if (el.tagName === "BR") return node === container;
    if (node === container) {
      const kids = [...node.childNodes];
      for (let i = 0; i < offset && i < kids.length; i++) count += textLen(kids[i]);
      return true;
    }
    for (const kid of node.childNodes) {
      if (consider(kid)) return true;
    }
    return false;
  };
  const lines = linesOf(root);
  for (let i = 0; i < lines.length; i++) {
    if (i) count += 1;
    if (consider(lines[i])) return count;
  }
  return count;
}

function pointAt(root: HTMLElement, pos: number): { node: Node; offset: number } {
  let left = pos;
  const lines = linesOf(root);
  const place = (el: Node): { node: Node; offset: number } | null => {
    const kids = [...el.childNodes];
    for (let i = 0; i < kids.length; i++) {
      const kid = kids[i];
      if (kid.nodeType === Node.TEXT_NODE) {
        const len = (kid.textContent ?? "").length;
        if (left <= len) return { node: kid, offset: left };
        left -= len;
      } else if ((kid as HTMLElement).tagName === "BR") {
        if (left === 0) return { node: el, offset: i };
      } else {
        const hit = place(kid);
        if (hit) return hit;
      }
    }
    if (left === 0) return { node: el, offset: el.childNodes.length };
    return null;
  };
  for (let i = 0; i < lines.length; i++) {
    if (i) {
      if (left === 0) return { node: lines[i], offset: 0 };
      left -= 1;
    }
    const hit = place(lines[i]);
    if (hit) return hit;
  }
  const last = lines[lines.length - 1] ?? root;
  return { node: last, offset: last.childNodes.length };
}

function selectSpan(root: HTMLElement, start: number, end: number) {
  const a = pointAt(root, Math.min(start, end));
  const b = pointAt(root, Math.max(start, end));
  const range = document.createRange();
  range.setStart(a.node, a.offset);
  range.setEnd(b.node, b.offset);
  const sel = window.getSelection();
  sel?.removeAllRanges();
  sel?.addRange(range);
}

function markPlaceholder(root: HTMLElement) {
  const text = serializeEditor(root);
  const first = root.firstElementChild as HTMLElement | null;
  if (!first) return;
  if (!text) first.dataset.placeholder = root.dataset.placeholder ?? "";
  else delete first.dataset.placeholder;
}

function closestRun(node: Node | null): HTMLElement | null {
  const el = node?.nodeType === Node.ELEMENT_NODE ? node as HTMLElement : node?.parentElement;
  const run = el?.closest(".md-run");
  return run instanceof HTMLElement ? run : null;
}

function lineOf(root: HTMLElement, node: Node | null): HTMLElement | null {
  let n: Node | null = node;
  if (n === root) return linesOf(root)[0] ?? null;
  while (n && n.parentNode !== root) n = n.parentNode;
  if (n instanceof HTMLElement && n.tagName === "DIV") return n;
  return null;
}

function caretIn(root: HTMLElement): Range | null {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0 || !sel.anchorNode || !root.contains(sel.anchorNode)) return null;
  return sel.getRangeAt(0);
}

function linePoint(line: HTMLElement, range: Range): { text: string; caret: number } {
  let text = "";
  let caret = -1;
  const add = (raw: string, cut: number | null) => {
    const before = cut == null ? clean(raw) : clean(raw.slice(0, cut));
    const after = cut == null ? "" : clean(raw.slice(cut));
    if (cut != null && caret < 0) caret = text.length + before.length;
    text += before + after;
  };
  const visit = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      const raw = node.textContent ?? "";
      add(raw, node === range.startContainer ? range.startOffset : null);
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    if ((node as HTMLElement).tagName === "BR") return;
    if (node === range.startContainer) {
      const kids = [...node.childNodes];
      kids.slice(0, range.startOffset).forEach(visit);
      if (caret < 0) caret = text.length;
      kids.slice(range.startOffset).forEach(visit);
      return;
    }
    node.childNodes.forEach(visit);
  };
  visit(line);
  if (caret < 0) caret = text.length;
  return { text, caret };
}

function placeIn(node: Node, offset: number) {
  const range = document.createRange();
  range.setStart(node, offset);
  range.collapse(true);
  const sel = window.getSelection();
  sel?.removeAllRanges();
  sel?.addRange(range);
}

function unwrap(span: HTMLElement) {
  const parent = span.parentNode;
  if (!parent) return;
  while (span.firstChild) parent.insertBefore(span.firstChild, span);
  span.remove();
}

function sameLook(el: HTMLElement, look: Look): boolean {
  return validColor(el.dataset.c) === look.c && validFont(el.dataset.f) === look.f;
}

function covered(root: HTMLElement, range: Range, text: Text): boolean {
  if (!text.length || !root.contains(text)) return false;
  const from = Math.min(
    posOf(root, range.startContainer, range.startOffset),
    posOf(root, range.endContainer, range.endOffset),
  );
  const to = Math.max(
    posOf(root, range.startContainer, range.startOffset),
    posOf(root, range.endContainer, range.endOffset),
  );
  const textStart = posOf(root, text, 0);
  const textEnd = posOf(root, text, text.length);
  return from < textEnd && to > textStart;
}

function aloneInRun(text: Text): HTMLElement | null {
  const parent = text.parentElement;
  if (!parent?.classList.contains("md-run")) return null;
  if (parent.childNodes.length === 1 && parent.firstChild === text) return parent;
  const span = document.createElement("span");
  span.className = "md-run";
  if (parent.dataset.c) span.dataset.c = parent.dataset.c;
  if (parent.dataset.f) span.dataset.f = parent.dataset.f;
  paint(span);
  parent.parentNode?.insertBefore(span, parent);
  const before = document.createElement("span");
  before.className = "md-run";
  if (parent.dataset.c) before.dataset.c = parent.dataset.c;
  if (parent.dataset.f) before.dataset.f = parent.dataset.f;
  paint(before);
  while (parent.firstChild && parent.firstChild !== text) before.appendChild(parent.firstChild);
  span.appendChild(text);
  if (!before.textContent) before.remove();
  else parent.parentNode?.insertBefore(before, span);
  if (!parent.textContent) parent.remove();
  else paint(parent);
  return span;
}

function applyToText(text: Text, patch: StylePatch) {
  if (!clean(text.textContent ?? "")) return;
  let run = aloneInRun(text);
  if (patch.clear) {
    if (run) unwrap(run);
    return;
  }
  if (!run) {
    run = document.createElement("span");
    run.className = "md-run";
    text.parentNode?.insertBefore(run, text);
    run.appendChild(text);
  }
  if (patch.color !== undefined) {
    const c = validColor(patch.color);
    if (c) run.dataset.c = c;
    else delete run.dataset.c;
  }
  if (patch.font !== undefined) {
    const f = validFont(patch.font);
    if (f) run.dataset.f = f;
    else delete run.dataset.f;
  }
  paint(run);
  if (!validColor(run.dataset.c) && !validFont(run.dataset.f)) unwrap(run);
}

function restyle(root: HTMLElement, range: Range, patch: StylePatch) {
  const start = posOf(root, range.startContainer, range.startOffset);
  const end = posOf(root, range.endContainer, range.endOffset);
  const nodes: Text[] = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  let n: Node | null;
  while ((n = walker.nextNode())) nodes.push(n as Text);
  const targets: Text[] = [];
  for (const text of nodes) {
    if (!covered(root, range, text)) continue;
    let from = 0;
    let to = text.length;
    if (text === range.startContainer) from = Math.min(range.startOffset, text.length);
    if (text === range.endContainer) to = Math.min(range.endOffset, text.length);
    if (from >= to) continue;
    let node = text;
    if (to < node.length) node.splitText(to);
    if (from > 0) node = node.splitText(from);
    if (node.length) targets.push(node);
  }
  for (const text of targets) {
    if (text.isConnected) applyToText(text, patch);
  }
  selectSpan(root, start, end);
}

function splitRunAtCaret(run: HTMLElement, range: Range) {
  let node: Node = range.startContainer;
  let offset = range.startOffset;
  if (!run.contains(node)) return;
  if (node.nodeType === Node.TEXT_NODE) {
    const t = node as Text;
    if (offset > 0 && offset < t.length) {
      node = t.splitText(offset);
      offset = 0;
    }
  }
  if (!run.lastChild) return;
  const tail = document.createRange();
  try {
    tail.setStart(node, offset);
    tail.setEnd(run, run.childNodes.length);
  } catch {
    return;
  }
  if (tail.collapsed) {
    const parent = run.parentNode;
    if (parent) placeIn(parent, indexAfter(run));
    return;
  }
  const frag = tail.extractContents();
  if (!clean(frag.textContent ?? "")) {
    run.appendChild(frag);
    const parent = run.parentNode;
    if (parent) placeIn(parent, indexAfter(run));
    return;
  }
  const after = document.createElement("span");
  after.className = "md-run";
  if (run.dataset.c) after.dataset.c = run.dataset.c;
  if (run.dataset.f) after.dataset.f = run.dataset.f;
  paint(after);
  after.appendChild(frag);
  run.after(after);
  if (!clean(run.textContent ?? "")) {
    const next = run.nextSibling;
    run.remove();
    if (next?.parentNode) {
      const i = [...next.parentNode.childNodes].indexOf(next as ChildNode);
      placeIn(next.parentNode, Math.max(0, i));
    }
    return;
  }
  const parent = run.parentNode;
  if (parent) placeIn(parent, indexAfter(run));
}

function indexAfter(node: Node): number {
  const parent = node.parentNode;
  if (!parent) return 0;
  return [...parent.childNodes].indexOf(node as ChildNode) + 1;
}

function dropEmptyRuns(root: HTMLElement, keep: HTMLElement | null) {
  root.querySelectorAll(".md-run").forEach((el) => {
    if (el === keep) return;
    if (!clean(el.textContent ?? "")) el.remove();
  });
  for (const line of linesOf(root)) {
    if (!line.textContent && !line.querySelector("br")) line.appendChild(document.createElement("br"));
  }
}

function lookOf(text: Text): Look {
  const run = text.parentElement?.classList.contains("md-run") ? text.parentElement : null;
  return { c: validColor(run?.dataset.c), f: validFont(run?.dataset.f) };
}

function lookAt(root: HTMLElement): Look {
  const range = caretIn(root);
  if (!range) return {};
  if (range.collapsed) {
    const run = closestRun(range.startContainer);
    if (!run) return {};
    return { c: validColor(run.dataset.c), f: validFont(run.dataset.f) };
  }
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  let look: Look | null = null;
  let n: Node | null;
  while ((n = walker.nextNode())) {
    const t = n as Text;
    if (!covered(root, range, t)) continue;
    const next = lookOf(t);
    if (!look) look = next;
    else if (look.c !== next.c || look.f !== next.f) return {};
  }
  return look ?? {};
}

function mergeLook(base: Look, patch: StylePatch): Look {
  const next: Look = { ...base };
  if (patch.color !== undefined) {
    const c = validColor(patch.color);
    if (c) next.c = c;
    else delete next.c;
  }
  if (patch.font !== undefined) {
    const f = validFont(patch.font);
    if (f) next.f = f;
    else delete next.f;
  }
  return next;
}

function dirty(root: HTMLElement): boolean {
  if (root.querySelector("b, i, u, p, font, ul, ol, li, span:not(.md-run)")) return true;
  return [...root.childNodes].some((n) => {
    if (n.nodeType === Node.TEXT_NODE) return (n.textContent ?? "").length > 0;
    return !(n instanceof HTMLElement && n.tagName === "DIV");
  });
}

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

export function NoteBody({ body, placeholder, onChange, onLook, handle }: Props) {
  const surface = useRef<HTMLDivElement>(null);
  const bodyRef = useRef(body);
  const onChangeRef = useRef(onChange);
  const onLookRef = useRef(onLook);
  const skip = useRef(false);
  const pending = useRef<Look | null>(null);
  const armed = useRef<HTMLElement | null>(null);
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
    const range = caretIn(root);
    if (!range) root.focus();
    if (patch.clear) {
      pending.current = null;
      armed.current = null;
      if (range && !range.collapsed) restyle(root, range, { clear: true });
      dropEmptyRuns(root, null);
      emit();
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
      report(lookAt(root));
      window.setTimeout(() => { mute.current = Math.max(0, mute.current - 1); }, 0);
      return;
    }
    const base = pending.current ?? (range ? lookAt(root) : {});
    const next = mergeLook(base, patch);
    arm(next);
    emit();
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
      if (mute.current) return;
      const el = surface.current;
      if (!el) return;
      const sel = window.getSelection();
      if (!sel?.anchorNode || !el.contains(sel.anchorNode)) return;
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

  handle.current = { focus: focusEnd, insertTodo, apply };

  return <Surface placeholder={placeholder} surface={surface} />;
}
