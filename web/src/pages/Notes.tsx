import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type KeyboardEvent as ReactKey, type ReactNode } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import { excerpt, NotePreview, todoStats, toggleTodoLine } from "../md";
import type { Folder, Note } from "../types";

const TODO_PREFIX = "- [ ] ";

function fileName(n: Note): string {
  return n.title.trim() || "Sem título";
}

function Ico({ path }: { path: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d={path} />
    </svg>
  );
}

function folderOf(n: Note): number | null {
  return n.folderId && n.folderId > 0 ? n.folderId : null;
}

function kids(folders: Folder[], parent: number | null): Folder[] {
  return folders
    .filter((f) => (f.parent && f.parent > 0 ? f.parent : null) === parent)
    .sort((a, b) => a.name.localeCompare(b.name, "pt"));
}

function pathOf(folders: Folder[], id: number | null): string {
  const byId = new Map(folders.map((f) => [f.id, f]));
  const parts: string[] = [];
  let cur = id;
  while (cur) {
    const f = byId.get(cur);
    if (!f) break;
    parts.unshift(f.name);
    cur = f.parent && f.parent > 0 ? f.parent : null;
  }
  return parts.join(" / ");
}

function isInside(folders: Folder[], id: number, dest: number | null): boolean {
  if (dest == null) return false;
  if (id === dest) return true;
  const byId = new Map(folders.map((f) => [f.id, f]));
  let cur: number | null = dest;
  while (cur) {
    if (cur === id) return true;
    const f = byId.get(cur);
    cur = f && f.parent && f.parent > 0 ? f.parent : null;
  }
  return false;
}

export function NotesPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const currentId = id ? Number(id) : 0;

  const [notes, setNotes] = useState<Note[]>([]);
  const [folders, setFolders] = useState<Folder[]>([]);
  const [q, setQ] = useState("");
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [savedTitle, setSavedTitle] = useState("");
  const [savedBody, setSavedBody] = useState("");
  const [preview, setPreview] = useState(false);
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const [here, setHere] = useState<number | null>(null);
  const [open, setOpen] = useState<Record<number, boolean>>({});
  const [dropOn, setDropOn] = useState<number | "root" | null>(null);
  const titleRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const boundId = useRef(0);
  const saved = useRef({ title: "", body: "" });
  const draft = useRef({ title: "", body: "" });
  const notesRef = useRef<Note[]>([]);
  const foldersRef = useRef<Folder[]>([]);
  const dragged = useRef(false);

  draft.current = { title, body };
  notesRef.current = notes;
  foldersRef.current = folders;

  const dirty = currentId > 0 && currentId === boundId.current
    && (title !== savedTitle || body !== savedBody);

  const visible = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return notes;
    return notes.filter((n) =>
      n.title.toLowerCase().includes(needle) || n.body.toLowerCase().includes(needle));
  }, [notes, q]);
  const openTodos = useMemo(
    () => visible.reduce((n, x) => n + todoStats(x.body).open, 0),
    [visible],
  );
  const searching = Boolean(q.trim());

  function apply(n: Note) {
    boundId.current = n.id;
    saved.current = { title: n.title, body: n.body };
    setTitle(n.title);
    setBody(n.body);
    setSavedTitle(n.title);
    setSavedBody(n.body);
  }

  async function refresh() {
    setErr("");
    try {
      const r = await api.notes();
      setNotes(r.notes);
      setFolders(r.folders ?? []);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "erro");
    }
  }

  useEffect(() => { void refresh(); }, []);

  const persist = useCallback(async () => {
    const noteId = boundId.current;
    if (!noteId) return;
    const titleNow = draft.current.title;
    const bodyNow = draft.current.body;
    if (titleNow === saved.current.title && bodyNow === saved.current.body) return;
    setBusy(true);
    setErr("");
    try {
      const n = await api.updateNote(noteId, { title: titleNow, body: bodyNow });
      setNotes((list) => [n, ...list.filter((x) => x.id !== n.id)]);
      if (boundId.current === noteId) {
        saved.current = { title: n.title, body: n.body };
        setSavedTitle(n.title);
        setSavedBody(n.body);
        setMsg("salvo");
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não salvou");
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    if (!currentId) {
      boundId.current = 0;
      setTitle("");
      setBody("");
      setSavedTitle("");
      setSavedBody("");
      setPreview(false);
      return () => { void persist(); };
    }
    let cancelled = false;
    const local = notesRef.current.find((x) => x.id === currentId);
    const load = local ? Promise.resolve(local) : api.note(currentId);
    load.then((n) => { if (!cancelled) apply(n); }).catch((e: unknown) => {
      if (cancelled) return;
      setErr(e instanceof Error ? e.message : "não achou");
      nav("/notas");
    });
    return () => { cancelled = true; void persist(); };
  }, [currentId, persist, nav]);

  useEffect(() => {
    if (!dirty || !currentId) return;
    const t = window.setTimeout(() => { void persist(); }, 900);
    return () => window.clearTimeout(t);
  }, [title, body, dirty, currentId, persist]);

  useEffect(() => {
    if (!msg) return;
    const t = window.setTimeout(() => setMsg(""), 1600);
    return () => window.clearTimeout(t);
  }, [msg]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "s") {
        e.preventDefault();
        void persist();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [persist]);

  async function openNote(next: number) {
    if (boundId.current && (draft.current.title !== saved.current.title
        || draft.current.body !== saved.current.body))
      await persist();
    nav("/notas/" + next);
  }

  async function create(presetTitle = "", presetBody = "") {
    if (boundId.current && (draft.current.title !== saved.current.title
        || draft.current.body !== saved.current.body))
      await persist();
    setErr("");
    try {
      const n = await api.createNote({ title: presetTitle, body: presetBody, folderId: here });
      setNotes((list) => [n, ...list]);
      nav("/notas/" + n.id);
      setTimeout(() => {
        if (presetBody) bodyRef.current?.focus();
        else titleRef.current?.focus();
      }, 0);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não criou");
    }
  }

  function insertTodo() {
    const el = bodyRef.current;
    const start = el?.selectionStart ?? body.length;
    const end = el?.selectionEnd ?? start;
    const before = body.slice(0, start);
    const after = body.slice(end);
    const nl = before.length === 0 || before.endsWith("\n") ? "" : "\n";
    setBody(before + nl + TODO_PREFIX + after);
    setPreview(false);
    requestAnimationFrame(() => {
      const pos = before.length + nl.length + TODO_PREFIX.length;
      el?.focus();
      el?.setSelectionRange(pos, pos);
    });
  }

  function onBodyKey(e: ReactKey<HTMLTextAreaElement>) {
    if (e.key !== "Enter" || e.shiftKey) return;
    const el = e.currentTarget;
    const pos = el.selectionStart;
    const lineStart = body.lastIndexOf("\n", pos - 1) + 1;
    const line = body.slice(lineStart, pos);
    const m = /^( *)[-*] \[([ xX])\](.*)$/.exec(line);
    if (!m) return;
    e.preventDefault();
    if (m[3].trim() === "") {
      const next = body.slice(0, lineStart) + body.slice(pos);
      setBody(next);
      requestAnimationFrame(() => el.setSelectionRange(lineStart, lineStart));
      return;
    }
    const insert = "\n" + m[1] + TODO_PREFIX;
    const next = body.slice(0, pos) + insert + body.slice(el.selectionEnd);
    setBody(next);
    requestAnimationFrame(() => {
      const p = pos + insert.length;
      el.setSelectionRange(p, p);
    });
  }

  async function openWiki(name: string) {
    const hit = notesRef.current.find((n) => n.title.toLowerCase() === name.toLowerCase());
    if (hit) { await openNote(hit.id); return; }
    await create(name);
  }

  async function remove() {
    if (!currentId || !confirm("apagar esta nota?")) return;
    boundId.current = 0;
    setErr("");
    try {
      await api.removeNote(currentId);
      setNotes((list) => list.filter((x) => x.id !== currentId));
      nav("/notas");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não apagou");
    }
  }

  async function addFolder() {
    const name = window.prompt("nome da pasta", "Pasta");
    if (name == null) return;
    setErr("");
    try {
      const f = await api.createFolder({ name, parent: here });
      setFolders((list) => [...list, f]);
      setOpen((o) => ({ ...o, [f.id]: true }));
      setHere(f.id);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não criou pasta");
    }
  }

  async function renameFolder(f: Folder) {
    const name = window.prompt("renomear pasta", f.name);
    if (name == null || name.trim() === f.name) return;
    try {
      const next = await api.updateFolder(f.id, { name: name.trim(), parent: f.parent });
      setFolders((list) => list.map((x) => x.id === next.id ? next : x));
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não renomeou");
    }
  }

  async function removeFolder(f: Folder) {
    if (!confirm("apagar a pasta “" + f.name + "”? as notas sobem um nível.")) return;
    try {
      await api.removeFolder(f.id);
      const parent = f.parent && f.parent > 0 ? f.parent : null;
      setFolders((list) => list
        .filter((x) => x.id !== f.id)
        .map((x) => x.parent === f.id ? { ...x, parent } : x));
      setNotes((list) => list.map((n) => folderOf(n) === f.id ? { ...n, folderId: parent } : n));
      if (here === f.id) setHere(parent);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não apagou pasta");
    }
  }

  async function dropItem(raw: string, dest: number | null) {
    setDropOn(null);
    if (raw.startsWith("note:")) {
      const noteId = Number(raw.slice(5));
      const n = notesRef.current.find((x) => x.id === noteId);
      if (!n || folderOf(n) === dest) return;
      try {
        const next = await api.moveNote(noteId, dest);
        setNotes((list) => list.map((x) => x.id === next.id ? next : x));
      } catch (e) {
        setErr(e instanceof Error ? e.message : "não moveu");
      }
      return;
    }
    if (raw.startsWith("folder:")) {
      const folderId = Number(raw.slice(7));
      if (isInside(foldersRef.current, folderId, dest)) return;
      const f = foldersRef.current.find((x) => x.id === folderId);
      if (!f || (f.parent && f.parent > 0 ? f.parent : null) === dest) return;
      try {
        const next = await api.updateFolder(folderId, { parent: dest });
        setFolders((list) => list.map((x) => x.id === next.id ? next : x));
        if (dest) setOpen((o) => ({ ...o, [dest]: true }));
      } catch (e) {
        setErr(e instanceof Error ? e.message : "não moveu pasta");
      }
    }
  }

  function dragPayload(e: DragEvent, payload: string) {
    dragged.current = true;
    e.dataTransfer.setData("text/plain", payload);
    e.dataTransfer.effectAllowed = "move";
  }

  function allowDrop(e: DragEvent, target: number | "root") {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    setDropOn(target);
  }

  function noteRow(n: Note, depth = 0) {
    const stats = todoStats(n.body);
    return (
      <button
        key={n.id}
        type="button"
        draggable
        className={"vault-file" + (n.id === currentId ? " active" : "")}
        style={{ paddingLeft: 12 + depth * 14 }}
        onDragStart={(e) => dragPayload(e, "note:" + n.id)}
        onDragEnd={() => { setDropOn(null); setTimeout(() => { dragged.current = false; }, 0); }}
        onClick={() => { if (dragged.current) return; void openNote(n.id); }}
      >
        <span className="vault-file-ico" aria-hidden="true" />
        <span className="vault-file-text">
          <b>{fileName(n)}</b>
          <i>
            {searching && pathOf(folders, folderOf(n)) ? pathOf(folders, folderOf(n)) + " · " : ""}
            {stats.total > 0 ? stats.done + "/" + stats.total + " · " : ""}
            {excerpt(n.body) || n.upd}
          </i>
        </span>
      </button>
    );
  }

  function folderBlock(parent: number | null, depth: number): ReactNode {
    const list = kids(folders, parent);
    const files = searching ? [] : visible.filter((n) => folderOf(n) === parent);
    return (
      <>
        {list.map((f) => {
          const expanded = open[f.id] !== false;
          const count = notes.filter((n) => folderOf(n) === f.id).length;
          return (
            <div key={"f" + f.id} className="vault-branch">
              <div
                className={"vault-folder" + (here === f.id ? " active" : "") + (dropOn === f.id ? " drop" : "")}
                style={{ paddingLeft: 10 + depth * 14 }}
                draggable
                onDragStart={(e) => dragPayload(e, "folder:" + f.id)}
                onDragEnd={() => { setDropOn(null); dragged.current = false; }}
                onDragOver={(e) => allowDrop(e, f.id)}
                onDragLeave={() => setDropOn((d) => d === f.id ? null : d)}
                onDrop={(e) => { e.preventDefault(); void dropItem(e.dataTransfer.getData("text/plain"), f.id); }}
              >
                <button type="button" className={"vault-chevron" + (expanded ? " open" : "")} onClick={() => setOpen((o) => ({ ...o, [f.id]: !expanded }))} title={expanded ? "recolher" : "abrir"}>
                  <Ico path="M9 6l6 6-6 6" />
                </button>
                <span className="vault-folder-ico" aria-hidden="true" />
                <button type="button" className="vault-folder-name" onClick={() => { setHere(f.id); setOpen((o) => ({ ...o, [f.id]: true })); }}>
                  {f.name}
                  {count > 0 && <small>{count}</small>}
                </button>
                <button type="button" className="vault-mini" onClick={() => void renameFolder(f)} title="renomear">
                  <Ico path="M4 16.5 15.5 5l3.5 3.5L7.5 20H4z" />
                </button>
                <button type="button" className="vault-mini danger" onClick={() => void removeFolder(f)} title="apagar pasta">
                  <Ico path="M5 7h14M9 7V5h6v2M8 7l1 12h6l1-12" />
                </button>
              </div>
              {expanded && (
                <div className="vault-nest">
                  {folderBlock(f.id, depth + 1)}
                </div>
              )}
            </div>
          );
        })}
        {files.map((n) => noteRow(n, depth))}
      </>
    );
  }

  return (
    <div className={"vault" + (currentId ? " editing" : "")}>
      <aside className="vault-side">
        <div className="vault-search">
          <input
            placeholder="buscar notas"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            autoFocus={!currentId}
          />
          <div className="vault-actions">
            <button type="button" className="vault-icon" title="Nova nota" onClick={() => void create()}>
              <Ico path="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M12 11v6M9 14h6" />
            </button>
            <button type="button" className="vault-icon" title="Lista de tarefas" onClick={() => void create("Tarefas", TODO_PREFIX)}>
              <Ico path="M5 6h3v3H5zM5 13.5 7 15.5 11 11.5M12 7.5h8M12 14.5h8" />
            </button>
            <button type="button" className="vault-icon" title="Nova pasta" onClick={() => void addFolder()}>
              <Ico path="M3 8h6l2 2h10v9H3z" />
            </button>
          </div>
        </div>
        <div
          className={"vault-root" + (here == null ? " active" : "") + (dropOn === "root" ? " drop" : "")}
          onClick={() => setHere(null)}
          onDragOver={(e) => allowDrop(e, "root")}
          onDragLeave={() => setDropOn((d) => d === "root" ? null : d)}
          onDrop={(e) => { e.preventDefault(); void dropItem(e.dataTransfer.getData("text/plain"), null); }}
        >
          <span className="vault-folder-ico" aria-hidden="true" />
          Notas
        </div>
        <div className="vault-files">
          {searching ? visible.map(noteRow) : folderBlock(null, 0)}
          {visible.length === 0 && (searching || (notes.length === 0 && folders.length === 0)) && (
            <p className="muted vault-empty">
              {searching ? "nada para “" + q + "”" : "nenhuma nota ainda"}
            </p>
          )}
        </div>
        <p className="muted vault-count">
          {visible.length} nota(s){openTodos > 0 ? " · " + openTodos + " aberta(s)" : ""}
          {here ? " · " + pathOf(folders, here) : ""}
        </p>
      </aside>

      <section className="vault-editor">
        {err && <p className="err">{err}</p>}
        {!currentId && (
          <div className="vault-empty-main">
            <p className="vault-kicker">Notas</p>
            <h2>Nenhuma nota selecionada</h2>
            <p className="muted">
              arraste notas para as pastas. markdown, <code>[[ligações]]</code>
              e tarefas <code>- [ ]</code>.
            </p>
            <div className="row">
              <button className="btn primary" onClick={() => void create()}>Escrever</button>
              <button className="btn" onClick={() => void create("Tarefas", TODO_PREFIX)}>Tarefas</button>
              <button className="btn" onClick={() => void addFolder()}>Pasta</button>
            </div>
          </div>
        )}
        {currentId > 0 && (
          <>
            <div className="vault-bar">
              <button className="btn vault-back" onClick={() => nav("/notas")}>notas</button>
              <span className={"vault-status" + (dirty ? " dirty" : "")}>
                {busy ? "salvando…" : dirty ? "não salvo" : msg || "salvo"}
              </span>
              <div className="vault-seg">
                <button type="button" className={!preview ? "on" : ""} onClick={() => setPreview(false)}>editar</button>
                <button type="button" className={preview ? "on" : ""} onClick={() => setPreview(true)}>ler</button>
              </div>
              <button className="btn" onClick={insertTodo} title="inserir tarefa">Todo</button>
              <button className="btn" disabled={busy || !dirty} onClick={() => void persist()}>Salvar</button>
              <button className="btn danger" disabled={busy} onClick={() => void remove()}>apagar</button>
            </div>
            <input
              ref={titleRef}
              className="vault-title"
              placeholder="Sem título"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
            {preview ? (
              <div className="vault-preview">
                <NotePreview
                  body={body}
                  onWiki={(t) => void openWiki(t)}
                  onToggle={(line) => setBody((b) => toggleTodoLine(b, line))}
                />
              </div>
            ) : (
              <textarea
                ref={bodyRef}
                className="vault-body"
                placeholder={"# ideia\n\n- [ ] uma tarefa\n\nmarkdown · **negrito** · `código` · [[outra nota]]"}
                value={body}
                onChange={(e) => setBody(e.target.value)}
                onKeyDown={onBodyKey}
                spellCheck
              />
            )}
          </>
        )}
      </section>
    </div>
  );
}
