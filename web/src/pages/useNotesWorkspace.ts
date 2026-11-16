import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import { useUnsavedChanges } from "../ui/Unsaved";
import type { Folder, Note, Tag } from "../types";

/** Load / save / revision / dirty tracking for the notes editor. */
export function useNotesWorkspace(currentId: number) {
  const nav = useNavigate();
  const [notes, setNotes] = useState<Note[]>([]);
  const [folders, setFolders] = useState<Folder[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [savedTitle, setSavedTitle] = useState("");
  const [savedBody, setSavedBody] = useState("");
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);

  const boundId = useRef(0);
  const revision = useRef(1);
  const saved = useRef({ title: "", body: "" });
  const draft = useRef({ title: "", body: "" });
  const notesRef = useRef<Note[]>([]);
  const foldersRef = useRef<Folder[]>([]);
  const saving = useRef<Promise<boolean> | null>(null);

  draft.current = { title, body };
  notesRef.current = notes;
  foldersRef.current = folders;

  const dirty = currentId > 0 && currentId === boundId.current
    && (title !== savedTitle || body !== savedBody);
  const markClean = useUnsavedChanges(dirty || busy);

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
        if (e instanceof Error && "status" in e && (e as { status?: number }).status === 409) setConflict(true);
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
      return;
    }
    let cancelled = false;
    api.note(currentId).then((n) => {
      if (cancelled) return;
      apply(n);
    }).catch((e: unknown) => {
      if (cancelled) return;
      setErr(e instanceof Error ? e.message : "não achou");
      nav("/notas");
    });
    return () => { cancelled = true; };
  }, [currentId, nav]);

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

  return {
    notes, setNotes, folders, setFolders, tags, setTags,
    title, setTitle, body, setBody, savedTitle, savedBody,
    err, setErr, msg, setMsg, busy, conflict, setConflict,
    boundId, revision, saved, draft, notesRef, foldersRef,
    dirty, markClean, persist, refresh, apply,
  };
}
