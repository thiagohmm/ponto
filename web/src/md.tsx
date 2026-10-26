import type { ReactNode } from "react";

const INLINE = /(\[\[(?:[^\]|\n]+)(?:\|[^\]\n]+)?\]\]|\*\*[^*]+?\*\*|`[^`]+`|\*[^*\n]+?\*)/g;

function inline(text: string, onWiki: (title: string) => void, key: string): ReactNode[] {
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

export function excerpt(body: string): string {
  const line = body.split("\n").map((s) => s.trim()).find(Boolean) ?? "";
  return line.length > 72 ? line.slice(0, 71) + "…" : line;
}
