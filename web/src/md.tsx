import type { CSSProperties, ReactNode } from "react";

export const NOTE_COLORS: { id: string; label: string; value: string }[] = [
  { id: "ink", label: "preto", value: "#1d1d1f" },
  { id: "red", label: "vermelho", value: "#ff3b30" },
  { id: "orange", label: "laranja", value: "#ff9f0a" },
  { id: "green", label: "verde", value: "#34c759" },
  { id: "blue", label: "azul", value: "#007aff" },
  { id: "purple", label: "roxo", value: "#af52de" },
  { id: "pink", label: "rosa", value: "#ff2d55" },
  { id: "gray", label: "cinza", value: "#6e6e73" },
];

export const NOTE_FONTS: { id: string; label: string; family: string }[] = [
  { id: "serif", label: "Serif", family: "Georgia, \"Iowan Old Style\", Palatino, serif" },
  { id: "mono", label: "Mono", family: "ui-monospace, SFMono-Regular, Menlo, monospace" },
  { id: "avenir", label: "Avenir", family: "\"Avenir Next\", Avenir, \"Segoe UI\", sans-serif" },
  { id: "palatino", label: "Palatino", family: "Palatino, \"Palatino Linotype\", \"Book Antiqua\", serif" },
];

const COLOR_IDS = new Set(NOTE_COLORS.map((c) => c.id));
const FONT_IDS = new Set(NOTE_FONTS.map((f) => f.id));

export type Mark = { c?: string; f?: string; text: string };

export function colorOf(id?: string): string {
  return NOTE_COLORS.find((c) => c.id === id)?.value ?? "";
}

export function fontOf(id?: string): string {
  return NOTE_FONTS.find((f) => f.id === id)?.family ?? "";
}

export function runStyle(c?: string, f?: string): CSSProperties {
  const style: CSSProperties = {};
  const color = colorOf(c);
  const family = fontOf(f);
  if (color) style.color = color;
  if (family) style.fontFamily = family;
  return style;
}

export function validColor(id?: string): string | undefined {
  return id && COLOR_IDS.has(id) ? id : undefined;
}

export function validFont(id?: string): string | undefined {
  return id && FONT_IDS.has(id) ? id : undefined;
}

/** `}` and `\` inside a styled run are escaped so the closer `}}` stays unambiguous. */
export function escapeRun(text: string): string {
  return text.replace(/\\/g, "\\\\").replace(/\}/g, "\\}");
}

function parseAttrs(attr: string): { c?: string; f?: string } | null {
  const parts = attr.trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return null;
  let c: string | undefined;
  let f: string | undefined;
  for (const p of parts) {
    const m = /^(c|f)=([a-z]+)$/.exec(p);
    if (!m) return null;
    if (m[1] === "c") c = validColor(m[2]);
    else f = validFont(m[2]);
  }
  return { c, f };
}

function tryMark(line: string, i: number): { mark: Mark; next: number } | null {
  if (!line.startsWith("{{", i)) return null;
  let j = i + 2;
  while (j < line.length && line[j] !== "}") j++;
  if (j >= line.length) return null;
  const attrs = parseAttrs(line.slice(i + 2, j));
  if (!attrs) return null;
  j++;
  let text = "";
  while (j < line.length) {
    if (line[j] === "\\" && j + 1 < line.length) {
      text += line[j + 1];
      j += 2;
      continue;
    }
    if (line.startsWith("}}", j)) return { mark: { ...attrs, text }, next: j + 2 };
    text += line[j];
    j++;
  }
  return null;
}

export function scanLine(line: string): Mark[] {
  const out: Mark[] = [];
  let plain = "";
  const flush = () => {
    if (!plain) return;
    out.push({ text: plain });
    plain = "";
  };
  for (let i = 0; i < line.length;) {
    const hit = tryMark(line, i);
    if (hit) {
      flush();
      if (hit.mark.text) out.push(hit.mark);
      i = hit.next;
      continue;
    }
    plain += line[i];
    i++;
  }
  flush();
  return out;
}

function mergeMarks(runs: Mark[]): Mark[] {
  const out: Mark[] = [];
  for (const run of runs) {
    if (!run.text) continue;
    const c = validColor(run.c);
    const f = validFont(run.f);
    const prev = out[out.length - 1];
    if (prev && prev.c === c && prev.f === f) prev.text += run.text;
    else out.push({ c, f, text: run.text });
  }
  return out;
}

/** Keep the todo prefix plain so `- [ ]` still toggles and counts. */
function peelTodo(runs: Mark[]): Mark[] {
  const text = runs.map((r) => r.text).join("");
  const m = /^(\s*[-*] \[[ xX]\] ?)/.exec(text);
  if (!m) return runs;
  const n = m[1].length;
  const out: Mark[] = [];
  let left = n;
  for (const run of runs) {
    if (left <= 0) {
      out.push(run);
      continue;
    }
    if (run.text.length <= left) {
      out.push({ text: run.text });
      left -= run.text.length;
      continue;
    }
    out.push({ text: run.text.slice(0, left) });
    out.push({ c: run.c, f: run.f, text: run.text.slice(left) });
    left = 0;
  }
  return mergeMarks(out);
}

function encodeRun(run: Mark): string {
  if (!run.c && !run.f) return run.text;
  const attrs = [run.c && "c=" + run.c, run.f && "f=" + run.f].filter(Boolean).join(" ");
  return "{{" + attrs + "}" + escapeRun(run.text) + "}}";
}

export function encodeLine(runs: Mark[]): string {
  return peelTodo(mergeMarks(runs)).map(encodeRun).join("");
}

const INLINE = /(\[\[(?:[^\]|\n]+)(?:\|[^\]\n]+)?\]\]|\*\*[^*]+?\*\*|`[^`]+`|\*[^*\n]+?\*)/g;

function mdBits(text: string, onWiki: (title: string) => void, key: string): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let n = 0;
  for (const m of text.matchAll(INLINE)) {
    const at = m.index ?? 0;
    if (at > last) out.push(text.slice(last, at));
    const tok = m[0];
    if (tok.startsWith("[[")) {
      const inner = tok.slice(2, -2);
      const pipe = inner.indexOf("|");
      const title = (pipe < 0 ? inner : inner.slice(0, pipe)).trim();
      const alias = (pipe < 0 ? title : inner.slice(pipe + 1)).trim();
      out.push(
        <button key={key + n} type="button" className="wiki" onClick={() => onWiki(title)}>
          {alias}
        </button>,
      );
    } else if (tok.startsWith("**")) {
      out.push(<strong key={key + n}>{tok.slice(2, -2)}</strong>);
    } else if (tok.startsWith("`")) {
      out.push(<code key={key + n}>{tok.slice(1, -1)}</code>);
    } else {
      out.push(<em key={key + n}>{tok.slice(1, -1)}</em>);
    }
    last = at + tok.length;
    n++;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

function inline(text: string, onWiki: (title: string) => void, key: string): ReactNode[] {
  const out: ReactNode[] = [];
  scanLine(text).forEach((run, i) => {
    const bits = mdBits(run.text, onWiki, key + "r" + i);
    if (run.c || run.f) {
      out.push(
        <span key={key + "s" + i} className="md-run" style={runStyle(run.c, run.f)}>
          {bits}
        </span>,
      );
    } else {
      out.push(...bits);
    }
  });
  return out;
}

const TODO_LINE = /^(\s*)[-*] \[([ xX])\](.*)$/;

export function todoStats(body: string): { open: number; done: number; total: number } {
  let open = 0;
  let done = 0;
  for (const line of body.split("\n")) {
    const m = TODO_LINE.exec(line);
    if (!m) continue;
    if (m[2] === " ") open++;
    else done++;
  }
  return { open, done, total: open + done };
}

export function toggleTodoLine(body: string, line: number): string {
  const lines = body.split("\n");
  const m = TODO_LINE.exec(lines[line] ?? "");
  if (!m) return body;
  const mark = m[2] === " " ? "x" : " ";
  lines[line] = m[1] + "- [" + mark + "]" + m[3];
  return lines.join("\n");
}

export function NotePreview({
  body, onWiki, onToggle,
}: {
  body: string;
  onWiki: (title: string) => void;
  onToggle?: (line: number) => void;
}) {
  if (!body.trim()) return <p className="muted">nota vazia</p>;
  return (
    <div className="note-preview">
      {body.split("\n").map((line, i) => {
        const todo = TODO_LINE.exec(line);
        if (todo) {
          const done = todo[2] !== " ";
          const text = todo[3].replace(/^\s/, "");
          return (
            <label key={i} className={"md-todo" + (done ? " done" : "")}>
              <input type="checkbox" checked={done} onChange={() => onToggle?.(i)} />
              <span>{text ? inline(text, onWiki, "td" + i) : <span className="muted">tarefa</span>}</span>
            </label>
          );
        }
        if (line.startsWith("### ")) return <h3 key={i}>{inline(line.slice(4), onWiki, "h3" + i)}</h3>;
        if (line.startsWith("## ")) return <h2 key={i}>{inline(line.slice(3), onWiki, "h2" + i)}</h2>;
        if (line.startsWith("# ")) return <h1 key={i}>{inline(line.slice(2), onWiki, "h1" + i)}</h1>;
        if (/^---+$/.test(line.trim())) return <hr key={i} />;
        if (/^[-*] /.test(line)) return <div key={i} className="md-li">{inline(line.slice(2), onWiki, "li" + i)}</div>;
        if (!line.trim()) return <div key={i} className="md-gap" />;
        return <p key={i}>{inline(line, onWiki, "p" + i)}</p>;
      })}
    </div>
  );
}

export function visibleLine(line: string): string {
  return scanLine(line).map((r) => r.text).join("");
}

export function excerpt(body: string): string {
  const line = body.split("\n").map((s) => s.trim()).find(Boolean) ?? "";
  const visible = visibleLine(line).trim();
  return visible.length > 72 ? visible.slice(0, 71) + "…" : visible;
}
