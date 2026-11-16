import { encodeLine, runStyle, scanLine, validColor, validFont, type Mark } from "./md";
export type Look = { c?: string; f?: string };
export type StylePatch = { color?: string; font?: string; clear?: boolean };

export const ZWSP = "\u200b";
export const TODO_PREFIX = "- [ ] ";

export function clean(text: string): string {
  return text.replaceAll(ZWSP, "");
}

export function paint(span: HTMLElement) {
  const style = runStyle(validColor(span.dataset.c), validFont(span.dataset.f));
  span.style.color = style.color ?? "";
  span.style.fontFamily = style.fontFamily ?? "";
}

export function makeRun(text: string, look: Look): HTMLSpanElement {
  const span = document.createElement("span");
  span.className = "md-run";
  if (look.c) span.dataset.c = look.c;
  if (look.f) span.dataset.f = look.f;
  paint(span);
  span.textContent = text;
  return span;
}

export function renderLine(line: string): HTMLDivElement {
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

export function renderEditor(root: HTMLElement, body: string) {
  const frag = document.createDocumentFragment();
  for (const line of body.split("\n")) frag.appendChild(renderLine(line));
  root.replaceChildren(frag);
  markPlaceholder(root);
}

export function domRuns(el: HTMLElement): Mark[] {
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

export function serializeEditor(root: HTMLElement): string {
  const blocks = [...root.children].filter((n) => n.tagName === "DIV");
  if (!blocks.length) return encodeLine(domRuns(root));
  return blocks.map((b) => encodeLine(domRuns(b as HTMLElement))).join("\n");
}

export function linesOf(root: HTMLElement): HTMLElement[] {
  return [...root.children].filter((n) => n.tagName === "DIV") as HTMLElement[];
}

export function textLen(node: Node): number {
  if (node.nodeType === Node.TEXT_NODE) return (node.textContent ?? "").length;
  if (node.nodeType !== Node.ELEMENT_NODE) return 0;
  if ((node as HTMLElement).tagName === "BR") return 0;
  let n = 0;
  node.childNodes.forEach((k) => { n += textLen(k); });
  return n;
}

export function posOf(root: HTMLElement, container: Node, offset: number): number {
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

export function pointAt(root: HTMLElement, pos: number): { node: Node; offset: number } {
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

export function selectSpan(root: HTMLElement, start: number, end: number) {
  const a = pointAt(root, Math.min(start, end));
  const b = pointAt(root, Math.max(start, end));
  const range = document.createRange();
  range.setStart(a.node, a.offset);
  range.setEnd(b.node, b.offset);
  const sel = window.getSelection();
  sel?.removeAllRanges();
  sel?.addRange(range);
}

export function markPlaceholder(root: HTMLElement) {
  const text = serializeEditor(root);
  const first = root.firstElementChild as HTMLElement | null;
  if (!first) return;
  if (!text) first.dataset.placeholder = root.dataset.placeholder ?? "";
  else delete first.dataset.placeholder;
}

export function closestRun(node: Node | null): HTMLElement | null {
  const el = node?.nodeType === Node.ELEMENT_NODE ? node as HTMLElement : node?.parentElement;
  const run = el?.closest(".md-run");
  return run instanceof HTMLElement ? run : null;
}

export function lineOf(root: HTMLElement, node: Node | null): HTMLElement | null {
  let n: Node | null = node;
  if (n === root) return linesOf(root)[0] ?? null;
  while (n && n.parentNode !== root) n = n.parentNode;
  if (n instanceof HTMLElement && n.tagName === "DIV") return n;
  return null;
}

export function caretIn(root: HTMLElement): Range | null {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0 || !sel.anchorNode || !root.contains(sel.anchorNode)) return null;
  return sel.getRangeAt(0);
}

export function linePoint(line: HTMLElement, range: Range): { text: string; caret: number } {
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

export function placeIn(node: Node, offset: number) {
  const range = document.createRange();
  range.setStart(node, offset);
  range.collapse(true);
  const sel = window.getSelection();
  sel?.removeAllRanges();
  sel?.addRange(range);
}

export function unwrap(span: HTMLElement) {
  const parent = span.parentNode;
  if (!parent) return;
  while (span.firstChild) parent.insertBefore(span.firstChild, span);
  span.remove();
}

export function sameLook(el: HTMLElement, look: Look): boolean {
  return validColor(el.dataset.c) === look.c && validFont(el.dataset.f) === look.f;
}

export function covered(root: HTMLElement, range: Range, text: Text): boolean {
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

export function aloneInRun(text: Text): HTMLElement | null {
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

export function applyToText(text: Text, patch: StylePatch) {
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

export function restyle(root: HTMLElement, range: Range, patch: StylePatch) {
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

export function splitRunAtCaret(run: HTMLElement, range: Range) {
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

export function indexAfter(node: Node): number {
  const parent = node.parentNode;
  if (!parent) return 0;
  return [...parent.childNodes].indexOf(node as ChildNode) + 1;
}

export function dropEmptyRuns(root: HTMLElement, keep: HTMLElement | null) {
  root.querySelectorAll(".md-run").forEach((el) => {
    if (el === keep) return;
    if (!clean(el.textContent ?? "")) el.remove();
  });
  for (const line of linesOf(root)) {
    if (!line.textContent && !line.querySelector("br")) line.appendChild(document.createElement("br"));
  }
}

export function lookOf(text: Text): Look {
  const run = text.parentElement?.classList.contains("md-run") ? text.parentElement : null;
  return { c: validColor(run?.dataset.c), f: validFont(run?.dataset.f) };
}

export function lookAt(root: HTMLElement): Look {
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

export function mergeLook(base: Look, patch: StylePatch): Look {
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

export function dirty(root: HTMLElement): boolean {
  if (root.querySelector("b, i, u, p, font, ul, ol, li, span:not(.md-run)")) return true;
  return [...root.childNodes].some((n) => {
    if (n.nodeType === Node.TEXT_NODE) return (n.textContent ?? "").length > 0;
    return !(n instanceof HTMLElement && n.tagName === "DIV");
  });
}
