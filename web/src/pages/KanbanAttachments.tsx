import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import type { KanbanCardFile } from "../types";
import { FilePreview } from "./FilePreview";

type Props = {
  cardId: number;
  busy: boolean;
  onError: (message: string) => void;
};

export function KanbanAttachments({ cardId, busy, onError }: Props) {
  const [files, setFiles] = useState<KanbanCardFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [previewId, setPreviewId] = useState<number | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const preview = previewId != null ? files.find((f) => f.id === previewId) ?? null : null;

  async function reload() {
    const data = await api.kanbanFiles(cardId);
    setFiles(data.files);
  }

  useEffect(() => {
    setPreviewId(null);
    setFiles([]);
    reload().catch((e: unknown) => onError(e instanceof Error ? e.message : "não listou anexos"));
  }, [cardId]);

  async function upload(list: FileList | File[]) {
    const items = Array.from(list);
    if (items.length === 0) return;
    setUploading(true);
    try {
      for (const file of items) await api.uploadKanbanFile(cardId, file);
      await reload();
    } catch (e) {
      onError(e instanceof Error ? e.message : "não anexou");
    } finally {
      setUploading(false);
    }
  }

  async function removeFile(file: KanbanCardFile) {
    try {
      await api.removeKanbanFile(cardId, file.id);
      if (previewId === file.id) setPreviewId(null);
      await reload();
    } catch (e) {
      onError(e instanceof Error ? e.message : "não apagou o anexo");
    }
  }

  return (
    <div className="vault-attach kanban-attach">
      <input
        ref={fileRef}
        type="file"
        multiple
        hidden
        onChange={(e) => {
          if (e.target.files?.length) void upload(e.target.files);
          e.target.value = "";
        }}
      />
      <button
        type="button"
        className="btn vault-attach-btn"
        disabled={busy || uploading}
        onClick={() => fileRef.current?.click()}
        title="anexar arquivo ao cartão"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M21 12.5l-8.5 8.5a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 1 1 5.6 5.6L11 17.2a2 2 0 1 1-2.8-2.8L16.7 5.9" />
        </svg>
        {uploading ? "anexando…" : "anexar"}
      </button>
      {files.length === 0 && !uploading && (
        <span className="muted vault-attach-hint">anexe arquivos da tarefa</span>
      )}
      {files.map((f) => (
        <span key={f.id} className="vault-attach-chip" title={f.name + " · " + f.crea}>
          <button type="button" className="vault-attach-name"
            onClick={() => setPreviewId(f.id)} title={"visualizar " + f.name}>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M12 12v6M9.5 15.5 12 18l2.5-2.5" />
            </svg>
            <span>{f.name}</span>
          </button>
          <small>{fmtSize(f.size)}{f.state !== "ready" ? " · envio pendente" : ""}</small>
          {f.state === "ready" && (
            <a className="vault-attach-dl" href={api.kanbanFileDownloadUrl(cardId, f.id)} download
              title={"baixar " + f.name} aria-label={"baixar " + f.name}>
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 3v12M7 10l5 5 5-5M4 21h16" />
              </svg>
            </a>
          )}
          <button type="button" title="apagar arquivo" onClick={() => void removeFile(f)}>×</button>
        </span>
      ))}
      {preview && (
        <FilePreview
          file={preview}
          downloadUrl={api.kanbanFileDownloadUrl(cardId, preview.id)}
          loadBytes={() => api.kanbanFileBytes(cardId, preview.id)}
          onClose={() => setPreviewId(null)}
        />
      )}
    </div>
  );
}

function fmtSize(n: number): string {
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1).replace(".", ",") + " KB";
  return (n / (1024 * 1024)).toFixed(1).replace(".", ",") + " MB";
}
