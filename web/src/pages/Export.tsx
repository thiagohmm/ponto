import { useEffect, useState } from "react";
import { api } from "../api";
import type { Home, Sum } from "../types";

const KINDS = [
  { kind: "pdf", label: "PDF", hint: "relatório A4, detalhe + resumo" },
  { kind: "xls", label: "Excel", hint: "arquivo .xls (duas abas)" },
  { kind: "csv", label: "CSV", hint: "UTF-8, separado por ;" },
] as const;

export function ExportPage() {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [sum, setSum] = useState<Sum | null>(null);
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState<string>("");

  useEffect(() => {
    api.home().then((h: Home) => {
      setFrom(h.min || new Date().toISOString().slice(0, 10));
      setTo(h.max || new Date().toISOString().slice(0, 10));
    }).catch((e: unknown) => setErr(e instanceof Error ? e.message : "erro"));
  }, []);

  useEffect(() => {
    if (!from || !to) return;
    let ignore = false;
    setSum(null); setErr("");
    api.sum(from, to).then(value => { if (!ignore) setSum(value); })
      .catch((e: unknown) => { if (!ignore) setErr(e instanceof Error ? e.message : "Não foi possível carregar o período."); });
    return () => { ignore = true; };
  }, [from, to]);

  async function download(kind: string) {
    if (!from || !to || from > to || busy) return;
    setErr("");
    setMsg("");
    setBusy(kind);
    try {
      const r = await fetch(api.exportUrl(kind, from, to));
      if (!r.ok) {
        const body = await r.json().catch(() => ({ error: r.statusText })) as { error?: string };
        throw new Error(body.error || r.statusText);
      }
      const blob = await r.blob();
      const match = /filename="([^"]+)"/.exec(r.headers.get("Content-Disposition") ?? "");
      const name = match?.[1] ?? `ponto.${kind}`;
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = name;
      a.click();
      URL.revokeObjectURL(url);
      setMsg(name + " baixado");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não exportou");
    } finally {
      setBusy("");
    }
  }

  return (
    <div className="card">
      <h2>Exportar</h2>
      <p className="muted">Selecione o período e escolha o formato para baixar seu relatório.</p>
      <div className="toolbar">
        <label>Data inicial<input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} /></label>
        <label>Data final<input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} /></label>
      </div>
      {err && <p role="alert" className="err">{err}</p>}
      {msg && <p role="status" className="ok">{msg}</p>}
      {sum && (
        <p className="muted">{sum.from} — {sum.to} · {sum.lines} linhas · {sum.total} ({sum.hours} h)</p>
      )}
      <div className="export-grid">
        {KINDS.map((k) => (
          <button key={k.kind} className="btn primary" disabled={!from || !to || from > to || Boolean(busy)}
            onClick={() => void download(k.kind)}>
            <strong>{busy === k.kind ? "gerando…" : k.label}</strong>
            <span>{k.hint}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
