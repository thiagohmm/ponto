import { NoteNavigation } from "./NoteNavigation";
import { fileName, folderOf, tagIds, isInside } from "./noteTree";
import { noteHaystack, noteMatches } from "./noteSearch";
import { NoteAttachments } from "./NoteAttachments";
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useUnsavedChanges } from "../ui/Unsaved";
import { api } from "../api";
import { NoteBody, type Look, type NoteBodyHandle } from "../NoteBody";
import { NOTE_COLORS, NOTE_FONTS, NotePreview, toggleTodoLine } from "../md";
import { useConfirm } from "../ui/Confirm";
import { useAssist } from "../useAssist";
import type { Folder, Note, NoteFile, Tag } from "../types";

const TODO_PREFIX = "- [ ] ";

export function NotesPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const confirm = useConfirm();
  const { available: assistOn } = useAssist();
  const currentId = id ? Number(id) : 0;

  const [notes, setNotes] = useState<Note[]>([]);
  const [folders, setFolders] = useState<Folder[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
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
  const [tagHere, setTagHere] = useState<number | null>(null);
  const [open, setOpen] = useState<Record<number, boolean>>({});
  const [files, setFiles] = useState<NoteFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [dropFiles, setDropFiles] = useState(false);
  const [dropOn, setDropOn] = useState<number | string | null>(null);
  const [pickTag, setPickTag] = useState(false);
  const [suggestIds, setSuggestIds] = useState<number[]>([]);
  const [suggestBusy, setSuggestBusy] = useState(false);
  const titleRef = useRef<HTMLInputElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<NoteBodyHandle | null>(null);
  const lookNode = useRef<HTMLDivElement | null>(null);
  const [look, setLook] = useState<Look>({});
  const boundId = useRef(0);
  const fileDrag = useRef(0);
  const saved = useRef({ title: "", body: "" });
  const draft = useRef({ title: "", body: "" });
  const notesRef = useRef<Note[]>([]);
  const foldersRef = useRef<Folder[]>([]);
  const revision = useRef(1);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const saving = useRef<Promise<boolean> | null>(null);
  const [conflict, setConflict] = useState(false);

  draft.current = { title, body };
  notesRef.current = notes;
  foldersRef.current = folders;

  const dirty = currentId > 0 && currentId === boundId.current
    && (title !== savedTitle || body !== savedBody);
  const markClean = useUnsavedChanges(dirty || busy || uploading);

  const visible = useMemo(() => {
    const byTag = new Map(tags.map((t) => [t.id, t]));
    const tagName = (id: number) => byTag.get(id)?.name ?? "";
    return notes.filter((n) => {
      if (tagHere && !tagIds(n).includes(tagHere)) return false;
      return noteMatches(q, noteHaystack(n, tagName));
    });
  }, [notes, q, tagHere, tags]);
  const editingTags = tagIds(notes.find((n) => n.id === currentId));

  function apply(n: Note) {
    boundId.current = n.id;
    revision.current = n.revision;
    setConflict(false);
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
      setNotes(r.notes.map((n) => ({ ...n, tagIds: n.tagIds ?? [] })));
      setFolders(r.folders ?? []);
      setTags(r.tags ?? []);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "erro");
    }
  }

  useEffect(() => { void refresh(); }, []);
  useEffect(() => { setPickTag(false); setLook({}); setSuggestIds([]); }, [currentId]);

  const bindLook = useCallback((node: HTMLDivElement | null) => {
    const prev = lookNode.current;
    if (prev) prev.removeEventListener("pointerdown", keepSelection, true);
    lookNode.current = node;
    if (node) node.addEventListener("pointerdown", keepSelection, { capture: true, passive: false });
  }, []);

  function keepSelection(e: Event) {
    e.preventDefault();
    bodyRef.current?.holdSelection();
  }

  const persist = useCallback(async (): Promise<boolean> => {
    if (saving.current) {
      if (!(await saving.current)) return false;
    }
    const noteId = boundId.current;
    if (!noteId) return true;
    const { title: titleNow, body: bodyNow } = draft.current;
    if (titleNow === saved.current.title && bodyNow === saved.current.body) return true;
    const operation = (async () => {
      setBusy(true); setErr("");
      try {
        const n = await api.updateNote(noteId, { title: titleNow, body: bodyNow, revision: revision.current });
        setNotes(list => [n, ...list.filter(x => x.id !== n.id)]);
        if (boundId.current === noteId) {
          revision.current = n.revision;
          saved.current = { title: n.title, body: n.body };
          setSavedTitle(n.title); setSavedBody(n.body); setMsg("salvo");
          if (draft.current.title === n.title && draft.current.body === n.body) markClean();
        }
        return true;
      } catch (e) {
        setErr(e instanceof Error ? e.message : "não salvou");
        if (e instanceof Error && "status" in e && e.status === 409) setConflict(true);
        return false;
      } finally { setBusy(false); }
    })();
    saving.current = operation;
    try { return await operation; } finally { saving.current = null; }
  }, [markClean]);

  useEffect(() => {
    if (!currentId) {
      boundId.current = 0;
      setTitle("");
      setBody("");
      setSavedTitle("");
      setSavedBody("");
      setFiles([]);
      setPreview(false);
      return;
    }
    let cancelled = false;
    const load = api.note(currentId);
    load.then((n) => {
      if (cancelled) return;
      apply(n);
      void loadFiles(n.id);
    }).catch((e: unknown) => {
      if (cancelled) return;
      setErr(e instanceof Error ? e.message : "não achou");
      nav("/notas");
    });
    return () => { cancelled = true; };
  }, [currentId, persist, nav]);

  useEffect(() => {
    if (!dirty || !currentId || conflict) return;
    const t = window.setTimeout(() => { void persist(); }, 900);
    return () => window.clearTimeout(t);
  }, [title, body, dirty, currentId, persist, conflict]);

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
      if (!(await persist())) return;
    markClean();
    nav("/notas/" + next);
  }

  async function create(presetTitle = "", presetBody = "") {
    if (boundId.current && (draft.current.title !== saved.current.title
        || draft.current.body !== saved.current.body))
      if (!(await persist())) return;
    setErr("");
    try {
      const n = await api.createNote({
        title: presetTitle,
        body: presetBody,
        folderId: here,
        tagIds: tagHere ? [tagHere] : [],
      });
      setNotes((list) => [n, ...list]);
      markClean();
      nav("/notas/" + n.id);
      setTimeout(() => {
        if (presetBody) bodyRef.current?.focus();
        else titleRef.current?.focus();
      }, 0);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não criou");
    }
  }

  async function loadFiles(noteId: number) {
    try {
      const r = await api.files(noteId);
      if (boundId.current === noteId) setFiles(r.files ?? []);
    } catch (e) {
      if (boundId.current === noteId) setErr(e instanceof Error ? e.message : "não listou arquivos");
    }
  }

  async function upload(list: FileList | File[]) {
    if (!currentId) return;
    setUploading(true);
    setErr("");
    try {
      for (const f of Array.from(list)) {
        if (!mounted.current) break;
        const nf = await api.uploadFile(currentId, f);
        if (nf.noteId === currentId) setFiles((x) => [...x, nf]);
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não anexou");
    } finally {
      setUploading(false);
      if (mounted.current) await loadFiles(currentId);
    }
  }

  async function removeFile(f: NoteFile) {
    if (!currentId || !(await confirm({
      title: "Apagar arquivo",
      message: "O arquivo “" + f.name + "” sai da nota e do armazenamento. Sem volta.",
      ok: "Apagar",
      tone: "stamp",
    }))) return;
    setErr("");
    try {
      await api.removeFile(currentId, f.id);
      setFiles((x) => x.filter((y) => y.id !== f.id));
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não apagou");
    }
  }

  function insertTodo() {
    if (preview) {
      setPreview(false);
      setBody((b) => (b.length === 0 || b.endsWith("\n") ? b : b + "\n") + TODO_PREFIX);
      return;
    }
    bodyRef.current?.insertTodo();
  }

  async function openWiki(name: string) {
    const hit = notesRef.current.find((n) => n.title.toLowerCase() === name.toLowerCase());
    if (hit) { await openNote(hit.id); return; }
    await create(name);
  }

  async function remove() {
    const cur = notesRef.current.find((n) => n.id === currentId);
    if (!currentId || !(await confirm({
      title: "Apagar nota",
      message: "“" + (cur ? fileName(cur) : "esta nota") + "” sai do banco, com os anexos. Sem volta.",
      ok: "Apagar",
      tone: "stamp",
    }))) return;
    setErr("");
    try {
      await api.removeNote(currentId);
      boundId.current = 0;
      markClean();
      setNotes((list) => list.filter((x) => x.id !== currentId));
      nav("/notas");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não apagou");
    }
  }

  async function addTag(preset = "", select = true) {
    const name = window.prompt("nome da tag", preset || "Tag");
    if (name == null) return;
    setErr("");
    try {
      const t = await api.createTag({ name });
      setTags((list) => list.some((x) => x.id === t.id) ? list : [...list, t].sort((a, b) => a.name.localeCompare(b.name, "pt")));
      if (select) setTagHere(t.id);
      return t;
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não criou tag");
    }
  }

  async function renameTag(t: Tag) {
    const name = window.prompt("renomear tag", t.name);
    if (name == null || name.trim() === t.name) return;
    try {
      const next = await api.updateTag(t.id, { name: name.trim() });
      setTags((list) => list.map((x) => x.id === next.id ? next : x).sort((a, b) => a.name.localeCompare(b.name, "pt")));
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não renomeou");
    }
  }

  async function removeTag(t: Tag) {
    if (!(await confirm({
      title: "Apagar tag",
      message: "A tag “" + t.name + "” some de todas as notas. Sem volta.",
      ok: "Apagar",
      tone: "stamp",
    }))) return;
    try {
      await api.removeTag(t.id);
      setTags((list) => list.filter((x) => x.id !== t.id));
      setNotes((list) => list.map((n) => ({ ...n, tagIds: tagIds(n).filter((id) => id !== t.id) })));
      if (tagHere === t.id) setTagHere(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não apagou tag");
    }
  }

  async function putTags(noteId: number, ids: number[]) {
    try {
      const next = await api.tagNote(noteId, { tagIds: ids });
      setNotes((list) => list.map((x) => x.id === next.id ? next : x));
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não marcou");
    }
  }

  async function suggestTags() {
    if (!currentId || !assistOn || suggestBusy || tags.length === 0) return;
    setSuggestBusy(true);
    setErr("");
    try {
      const r = await api.assistNoteTags({
        title: draft.current.title || title,
        body: draft.current.body || body,
        tags: tags.map((t) => ({ id: t.id, name: t.name })),
      });
      const have = new Set(editingTags);
      setSuggestIds((r.tagIds ?? []).filter((id) => !have.has(id)));
    } catch {
      setSuggestIds([]);
    } finally {
      setSuggestBusy(false);
    }
  }

  async function acceptSuggestedTag(tagId: number) {
    if (!currentId) return;
    setSuggestIds((ids) => ids.filter((x) => x !== tagId));
    await putTags(currentId, [...editingTags, tagId]);
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
    if (!(await confirm({
      title: "Apagar pasta",
      message: "A pasta “" + f.name + "” sai da lista e as notas sobem um nível. Sem volta.",
      ok: "Apagar",
      tone: "stamp",
    }))) return;
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

  async function dropOnTag(raw: string, tagId: number) {
    setDropOn(null);
    if (!raw.startsWith("note:")) return;
    const noteId = Number(raw.slice(5));
    const n = notesRef.current.find((x) => x.id === noteId);
    if (!n || tagIds(n).includes(tagId)) return;
    try {
      const next = await api.tagNote(noteId, { tagId });
      setNotes((list) => list.map((x) => x.id === next.id ? next : x));
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não marcou");
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

  return (
    <div className={"vault" + (currentId ? " editing" : "")}>
      <NoteNavigation notes={notes} folders={folders} tags={tags} visible={visible} currentId={currentId}
        q={q} setQ={setQ} here={here} setHere={setHere} tagHere={tagHere} setTagHere={setTagHere}
        open={open} setOpen={setOpen} dropOn={dropOn} setDropOn={setDropOn}
        actions={{ openNote, create, addFolder, addTag, renameFolder, removeFolder, renameTag, removeTag, dropOnTag, dropItem }} />

      <section
        className="vault-editor"
        onDragOver={(e) => {
          if (Array.from(e.dataTransfer.types).includes("Files")) e.preventDefault();
        }}
        onDragEnter={(e) => {
          if (Array.from(e.dataTransfer.types).includes("Files")) {
            fileDrag.current++;
            setDropFiles(true);
          }
        }}
        onDragLeave={() => {
          fileDrag.current = Math.max(0, fileDrag.current - 1);
          if (fileDrag.current === 0) setDropFiles(false);
        }}
        onDrop={(e) => {
          if (!Array.from(e.dataTransfer.types).includes("Files")) return;
          e.preventDefault();
          fileDrag.current = 0;
          setDropFiles(false);
          if (e.dataTransfer.files.length) void upload(e.dataTransfer.files);
        }}
      >
        {err && <p role="alert" className="err">{err}</p>}
        {conflict && <div className="row">
          <button className="btn" onClick={() => {
            const url = URL.createObjectURL(new Blob([title + "\n\n" + body], { type: "text/plain;charset=utf-8" }));
            const link = document.createElement("a"); link.href = url; link.download = "rascunho-nota.txt"; link.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
          }}>Baixar meu rascunho</button>
          <button className="btn" onClick={async () => {
            if (!(await confirm({ title: "Reabrir nota salva", message: "O texto em edição será substituído pela versão salva. Baixe seu rascunho antes de continuar.", ok: "Reabrir" }))) return;
            try { apply(await api.note(currentId)); markClean(); setErr(""); } catch (e) { setErr(String(e)); }
          }}>Reabrir versão salva</button>
        </div>}
        {!currentId && (
          <div className="vault-empty-main">
            <p className="vault-kicker">Notas</p>
            <h2>Nenhuma nota selecionada</h2>
            <p className="muted">
              arraste notas para pastas ou tags. markdown, <code>[[ligações]]</code>
              e tarefas <code>- [ ]</code>.
            </p>
            <div className="row">
              <button className="btn primary" onClick={() => void create()}>Escrever</button>
              <button className="btn" onClick={() => void create("Tarefas", TODO_PREFIX)}>Tarefas</button>
              <button className="btn" onClick={() => void addFolder()}>Pasta</button>
              <button className="btn" onClick={() => void addTag()}>Tag</button>
            </div>
          </div>
        )}
        {currentId > 0 && (
          <>
            <div className="vault-bar">
              <button className="btn vault-back" onClick={() => nav("/notas")}>← Voltar às notas</button>
              <span role="status" aria-live="polite" className={"vault-status" + (dirty ? " dirty" : "")}>
                {busy ? "salvando…" : err && dirty ? "falha ao salvar" : dirty ? "não salvo" : msg || "salvo"}
              </span>
              <div className="vault-seg">
                <button type="button" className={!preview ? "on" : ""} onClick={() => setPreview(false)}>editar</button>
                <button type="button" className={preview ? "on" : ""} onClick={() => setPreview(true)}>ler</button>
              </div>
              {!preview && (
                <div className="vault-look" ref={bindLook}>
                  <div className="vault-swatches">
                    {NOTE_COLORS.map((c) => (
                      <button
                        key={c.id}
                        type="button"
                        className={"vault-swatch" + (look.c === c.id ? " on" : "")}
                        style={{ background: c.value }}
                        title={c.label}
                        aria-label={c.label}
                        onClick={() => bodyRef.current?.apply({ color: c.id })}
                      />
                    ))}
                  </div>
                  <div className="vault-fonts">
                    <button type="button" className={!look.f ? "on" : ""} onClick={() => bodyRef.current?.apply({ font: "" })}>Padrão</button>
                    {NOTE_FONTS.map((f) => (
                      <button
                        key={f.id}
                        type="button"
                        className={look.f === f.id ? "on" : ""}
                        style={{ fontFamily: f.family }}
                        onClick={() => bodyRef.current?.apply({ font: f.id })}
                      >
                        {f.label}
                      </button>
                    ))}
                  </div>
                  <button type="button" className="btn" onClick={() => bodyRef.current?.apply({ clear: true })}>limpar</button>
                </div>
              )}
              <button className="btn" onMouseDown={(e) => e.preventDefault()} onClick={insertTodo} title="inserir tarefa">Tarefa</button>
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
            <div className="vault-note-tags">
              {editingTags.map((id) => {
                const t = tags.find((x) => x.id === id);
                if (!t) return null;
                return (
                  <button
                    key={t.id}
                    type="button"
                    className="vault-chip on"
                    style={{ "--tag": t.color } as CSSProperties}
                    onClick={() => void putTags(currentId, editingTags.filter((x) => x !== t.id))}
                    title="tirar tag"
                  >
                    <span className="vault-tag-ico" aria-hidden="true" />
                    {t.name}
                    <span className="vault-chip-x">×</span>
                  </button>
                );
              })}
              {suggestIds.map((id) => {
                const t = tags.find((x) => x.id === id);
                if (!t) return null;
                return (
                  <button
                    key={"sug-" + t.id}
                    type="button"
                    className="vault-chip suggest"
                    style={{ "--tag": t.color } as CSSProperties}
                    onClick={() => void acceptSuggestedTag(t.id)}
                    title="aceitar sugestão"
                  >
                    <span className="vault-tag-ico" aria-hidden="true" />
                    {t.name}
                    <span className="vault-chip-x" onClick={(e) => {
                      e.stopPropagation();
                      setSuggestIds((ids) => ids.filter((x) => x !== t.id));
                    }} title="recusar">×</span>
                  </button>
                );
              })}
              <div className="vault-tag-add">
                <button type="button" className="vault-chip ghost" onClick={() => setPickTag((v) => !v)}>
                  <span className="vault-tag-ico" aria-hidden="true" />
                  tag
                </button>
                {assistOn && tags.length > 0 && (
                  <button
                    type="button"
                    className="vault-chip ghost"
                    disabled={suggestBusy}
                    onClick={() => void suggestTags()}
                    title="sugerir tags com o assistente local"
                  >
                    {suggestBusy ? "…" : "sugerir"}
                  </button>
                )}
                {pickTag && (
                  <div className="vault-tag-menu">
                    {tags.filter((t) => !editingTags.includes(t.id)).map((t) => (
                      <button
                        key={t.id}
                        type="button"
                        onClick={() => {
                          void putTags(currentId, [...editingTags, t.id]);
                          setPickTag(false);
                        }}
                      >
                        <span className="vault-pill" style={{ "--tag": t.color } as CSSProperties}>{t.name}</span>
                      </button>
                    ))}
                    <button type="button" onClick={() => {
                      setPickTag(false);
                      void addTag("", false).then((t) => {
                        if (t) void putTags(currentId, [...editingTags, t.id]);
                      });
                    }}>
                      nova tag
                    </button>
                  </div>
                )}
              </div>
            </div>
            <NoteAttachments currentId={currentId} files={files} uploading={uploading} dropFiles={dropFiles}
              fileRef={fileRef} upload={upload} removeFile={removeFile} />
            {preview ? (
              <div className="vault-preview">
                <NotePreview
                  body={body}
                  onWiki={(t) => void openWiki(t)}
                  onToggle={(line) => setBody((b) => toggleTodoLine(b, line))}
                />
              </div>
            ) : (
              <NoteBody
                key={currentId}
                body={body}
                placeholder={"# ideia\n\n- [ ] uma tarefa\n\nmarkdown · **negrito** · `código` · [[outra nota]]"}
                onChange={setBody}
                onLook={setLook}
                handle={bodyRef}
              />
            )}
          </>
        )}
      </section>
    </div>
  );
}
