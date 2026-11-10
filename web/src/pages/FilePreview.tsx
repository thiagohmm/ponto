import { useEffect, useRef, useState, type ReactNode } from "react";
import type { AttachFile } from "../types";
import { classify } from "./fileKind";

type Props = {
  file: AttachFile;
  downloadUrl: string;
  loadBytes: () => Promise<Blob>;
  onClose: () => void;
};

type Media =
  | { phase: "unavailable" }
  | { phase: "unsupported" }
  | { phase: "tooBig" }
  | { phase: "loading" }
  | { phase: "error"; message: string }
  | { phase: "image"; url: string }
  | { phase: "pdf"; url: string }
  | { phase: "text"; text: string };

const TEXT_LIMIT = 1024 * 1024;

export function FilePreview({ file, downloadUrl, loadBytes, onClose }: Props) {
  const kind = classify(file.name, file.ctype);
  const [media, setMedia] = useState<Media>({ phase: "loading" });
  const closeRef = useRef<HTMLButtonElement>(null);
  const onCloseRef = useRef(onClose);
  const loadBytesRef = useRef(loadBytes);
  onCloseRef.current = onClose;
  loadBytesRef.current = loadBytes;

  useEffect(() => {
    let cancelled = false;
    let url: string | null = null;
    if (file.state !== "ready") { setMedia({ phase: "unavailable" }); return; }
    if (kind === "none") { setMedia({ phase: "unsupported" }); return; }
    if (kind === "text" && file.size > TEXT_LIMIT) { setMedia({ phase: "tooBig" }); return; }
    setMedia({ phase: "loading" });
    loadBytesRef.current().then(async (blob) => {
      if (cancelled) return;
      if (kind === "text") {
        const text = await blob.text();
        if (cancelled) return;
        setMedia({ phase: "text", text });
        return;
      }
      let out = blob;
      if (kind === "pdf" && (out.type === "" || out.type === "application/octet-stream"))
        out = new Blob([out], { type: "application/pdf" });
      url = URL.createObjectURL(out);
      if (cancelled) { URL.revokeObjectURL(url); return; }
      setMedia({ phase: kind === "pdf" ? "pdf" : "image", url });
    }).catch((e) => {
      if (cancelled) return;
      setMedia({ phase: "error", message: e instanceof Error ? e.message : "não carregou o arquivo" });
    });
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [file, kind]);

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCloseRef.current();
    };
    window.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
      previous?.focus();
    };
  }, []);

  let body: ReactNode;
  switch (media.phase) {
    case "unavailable": body = <p className="muted">Arquivo ainda não está disponível para visualizar.</p>; break;
    case "unsupported": body = <p className="muted">Não é possível visualizar este arquivo.</p>; break;
    case "tooBig": body = <p className="muted">Texto grande demais para visualizar. Use o botão de download.</p>; break;
    case "loading": body = <p className="muted">carregando…</p>; break;
    case "error": body = <p className="file-preview-error">{media.message}</p>; break;
    case "image": body = <img src={media.url} alt={file.name} />; break;
    case "pdf": body = <iframe src={media.url} title={file.name} />; break;
    case "text": body = <pre className="file-preview-text">{media.text}</pre>; break;
  }

  return (
    <div className="overlay" role="presentation" onClick={onClose}>
      <div className="file-preview" role="dialog" aria-modal="true"
        aria-label={"Pré-visualização de " + file.name} onClick={(e) => e.stopPropagation()}>
        <div className="file-preview-head">
          <div className="file-preview-title">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5" />
            </svg>
            <strong title={file.name}>{file.name}</strong>
            <small>{fmtSize(file.size)}</small>
          </div>
          <div className="file-preview-actions">
            {file.state === "ready" && (
              <a className="btn" href={downloadUrl} download
                aria-label={"baixar " + file.name}>
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M12 3v12M7 10l5 5 5-5M4 21h16" />
                </svg>
                baixar
              </a>
            )}
            <button type="button" ref={closeRef} className="file-preview-close"
              onClick={onClose} aria-label="fechar pré-visualização">×</button>
          </div>
        </div>
        <div className={"file-preview-body" + (media.phase === "text" ? " text" : media.phase === "pdf" ? " pdf" : "")}>{body}</div>
      </div>
    </div>
  );
}

function fmtSize(n: number): string {
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1).replace(".", ",") + " KB";
  return (n / (1024 * 1024)).toFixed(1).replace(".", ",") + " MB";
}
