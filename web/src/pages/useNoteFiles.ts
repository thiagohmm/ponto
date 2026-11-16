import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import { useConfirm } from "../ui/Confirm";
import type { NoteFile } from "../types";

/** Upload / list / remove attachments for the note currently being edited. */
export function useNoteFiles(opts: {
  currentId: number;
  boundId: { current: number };
  setErr: (v: string) => void;
}) {
  const confirm = useConfirm();
  const mounted = useRef(true);
  const [files, setFiles] = useState<NoteFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [dropFiles, setDropFiles] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const fileDrag = useRef(0);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  async function loadFiles(noteId: number) {
    try {
      const r = await api.files(noteId);
      if (opts.boundId.current === noteId) setFiles(r.files ?? []);
    } catch (e) {
      if (opts.boundId.current === noteId) opts.setErr(e instanceof Error ? e.message : "não listou arquivos");
    }
  }

  async function upload(list: FileList | File[]) {
    if (!opts.currentId) return;
    setUploading(true);
    opts.setErr("");
    try {
      for (const f of Array.from(list)) {
        if (!mounted.current) break;
        const nf = await api.uploadFile(opts.currentId, f);
        if (nf.noteId === opts.currentId) setFiles((x) => [...x, nf]);
      }
    } catch (e) {
      opts.setErr(e instanceof Error ? e.message : "não anexou");
    } finally {
      setUploading(false);
      if (mounted.current) await loadFiles(opts.currentId);
    }
  }

  async function removeFile(f: NoteFile) {
    if (!opts.currentId || !(await confirm({
      title: "Apagar arquivo",
      message: "O arquivo “" + f.name + "” sai da nota e do armazenamento. Sem volta.",
      ok: "Apagar",
      tone: "stamp",
    }))) return;
    opts.setErr("");
    try {
      await api.removeFile(opts.currentId, f.id);
      setFiles((x) => x.filter((y) => y.id !== f.id));
    } catch (e) {
      opts.setErr(e instanceof Error ? e.message : "não apagou");
    }
  }

  function clearFiles() { setFiles([]); }

  return {
    files, setFiles, uploading, dropFiles, setDropFiles, fileRef, fileDrag,
    loadFiles, upload, removeFile, clearFiles, mounted,
  };
}
