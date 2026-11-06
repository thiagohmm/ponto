import { useEffect, useState, type RefObject } from "react";
import { api } from "../api";
import type { NoteFile } from "../types";
import { FilePreview } from "./FilePreview";

type Props = {
  currentId: number; files: NoteFile[]; uploading: boolean; dropFiles: boolean;
  fileRef: RefObject<HTMLInputElement | null>;
  upload: (files: FileList | File[]) => Promise<void>;
  removeFile: (file: NoteFile) => Promise<void>;
};
export function NoteAttachments({ currentId, files, uploading, dropFiles, fileRef, upload, removeFile }: Props) {
  const [previewId, setPreviewId] = useState<number | null>(null);
  const preview = previewId != null ? files.find((f) => f.id === previewId) ?? null : null;

  useEffect(() => { setPreviewId(null); }, [currentId]);

  return (
            <div className={("vault-attach" + (dropFiles ? " drop" : "")).trim()}>
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
                disabled={uploading}
                onClick={() => fileRef.current?.click()}
                title="anexar arquivo à nota"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M21 12.5l-8.5 8.5a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 1 1 5.6 5.6L11 17.2a2 2 0 1 1-2.8-2.8L16.7 5.9" />
                </svg>
                {uploading ? "anexando…" : "anexar"}
              </button>
              {files.length === 0 && !uploading && (
                <span className="muted vault-attach-hint">arraste arquivos aqui para anexar</span>
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
                    <a className="vault-attach-dl" href={api.fileDownloadUrl(currentId, f.id)} download
                      title={"baixar " + f.name} aria-label={"baixar " + f.name}>
                      <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path d="M12 3v12M7 10l5 5 5-5M4 21h16" />
                      </svg>
                    </a>
                  )}
                  <button type="button" title="apagar arquivo" onClick={() => void removeFile(f)}>×</button>
                </span>
              ))}
              {preview && <FilePreview noteId={currentId} file={preview} onClose={() => setPreviewId(null)} />}
            </div>
  );
}

function fmtSize(n: number): string {
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1).replace(".", ",") + " KB";
  return (n / (1024 * 1024)).toFixed(1).replace(".", ",") + " MB";
}
