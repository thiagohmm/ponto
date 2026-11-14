import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api } from "../api";
import { useCompany } from "../Company";
import type { Diario } from "../types";
import { ApiError } from "../types";

function todayIso(): string {
  const d = new Date();
  return [
    d.getFullYear(),
    String(d.getMonth() + 1).padStart(2, "0"),
    String(d.getDate()).padStart(2, "0"),
  ].join("-");
}

function hm(mins: number): string {
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h + ":" + (m < 10 ? "0" : "") + m;
}

const KIND_LABEL: Record<string, string> = {
  ponto: "Batida",
  nota: "Nota",
  kanban: "Kanban",
  bloqueio: "Bloqueio",
};

const COUNT_COLORS = ["#007aff", "#34c759", "#ff9f0a"];

export function DiarioPage() {
  const { active } = useCompany();
  const request = useRef(0);
  const [dia, setDia] = useState(todayIso);
  const [data, setData] = useState<Diario | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");

  async function load(next = dia) {
    if (!next) return;
    const current = ++request.current;
    setLoading(true);
    setErr("");
    try {
      const r = await api.diario(next);
      if (current !== request.current) return;
      setData(r);
    } catch (e) {
      if (current === request.current) setErr(e instanceof Error ? e.message : "não carregou o diário");
    } finally {
      if (current === request.current) setLoading(false);
    }
  }

  useEffect(() => { void load(dia); }, [active?.id, dia]);

  async function gerar() {
    if (busy || !dia) return;
    setBusy(true);
    setErr("");
    setMsg("");
    try {
      const dig = await api.gerarResumoDiario(dia);
      setMsg("Resumo salvo" + (dig.model ? " · " + dig.model : "") + (dig.upd ? " · " + dig.upd : ""));
      await load(dia);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : e instanceof Error ? e.message : "não gerou o resumo");
    } finally {
      setBusy(false);
    }
  }

  const countBars = useMemo(() => {
    if (!data) return [];
    const c = data.charts.counts;
    return [
      { name: "Ponto", value: c.ponto },
      { name: "Notas", value: c.notas },
      { name: "Kanban", value: c.kanban },
    ];
  }, [data]);

  const summaryParts = useMemo(() => {
    const raw = data?.digest?.summary ?? "";
    if (!raw) return { para: "", bullets: [] as string[] };
    const lines = raw.split("\n");
    const bullets: string[] = [];
    const para: string[] = [];
    for (const line of lines) {
      const t = line.trim();
      if (t.startsWith("- ")) bullets.push(t.slice(2));
      else if (t) para.push(t);
    }
    return { para: para.join(" "), bullets };
  }, [data]);

  return (
    <>
      <div className="toolbar">
        <label>Dia
          <input type="date" value={dia} onChange={(e) => { if (e.target.value) setDia(e.target.value); }} />
        </label>
        <button className="btn" disabled={loading} onClick={() => void load()}>Atualizar</button>
        <button className="btn primary push" disabled={busy || loading || !dia} onClick={() => void gerar()}>
          {busy ? "Gerando…" : data?.digest ? "Regenerar resumo" : "Gerar resumo"}
        </button>
      </div>

      {loading && <p role="status" className="muted">Carregando diário…</p>}
      {err && <p role="alert" className="err">{err}</p>}
      {msg && <p className="ok" role="status">{msg}</p>}

      {data && !loading && (
        <>
          <p className="muted">
            {data.dia} · {data.d3}
            {data.holiday ? " · " + data.holiday : ""}
            {data.falta ? " · falta" : ""}
            {data.normal ? " · normal" : ""}
            {" · "}{hm(data.charts.dayMins)} batidos
            {data.charts.dailyMins > 0 ? " / " + hm(data.charts.dailyMins) + " jornada" : ""}
          </p>

          <div className="diario-grid">
            <section className="card">
              <h2>Resumo</h2>
              {data.digest ? (
                <>
                  <p className="diario-summary">{summaryParts.para || data.digest.summary}</p>
                  {summaryParts.bullets.length > 0 && (
                    <ul className="diario-bullets">
                      {summaryParts.bullets.map((b) => <li key={b}>{b}</li>)}
                    </ul>
                  )}
                  <p className="muted">
                    salvo {data.digest.upd || data.digest.crea}
                    {data.digest.model ? " · " + data.digest.model : ""}
                  </p>
                </>
              ) : (
                <p className="muted">Ainda sem resumo neste dia. Gere para gravar no banco.</p>
              )}
            </section>

            <section className="card">
              <h2>Atividade</h2>
              <ResponsiveContainer width="100%" height={180}>
                <BarChart data={countBars} margin={{ top: 8, right: 8, bottom: 4, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="name" tick={{ fontSize: 11 }} tickLine={false} />
                  <YAxis allowDecimals={false} tick={{ fontSize: 11 }} width={28} tickLine={false} axisLine={false} />
                  <Tooltip contentStyle={{ background: "var(--card)", borderColor: "var(--line)", color: "var(--ink)" }} />
                  <Bar dataKey="value" isAnimationActive={false} minPointSize={3}>
                    {countBars.map((_, i) => <Cell key={i} fill={COUNT_COLORS[i % COUNT_COLORS.length]} />)}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </section>
          </div>

          <section className="card" style={{ marginTop: 16 }}>
            <h2>Minutos por batida</h2>
            {data.charts.pairs.length === 0 ? (
              <p className="muted">Nenhum par fechado neste dia.</p>
            ) : (
              <ResponsiveContainer width="100%" height={200}>
                <BarChart data={data.charts.pairs} margin={{ top: 8, right: 8, bottom: 4, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="label" tick={{ fontSize: 10 }} tickLine={false} />
                  <YAxis tick={{ fontSize: 11 }} width={36} tickLine={false} axisLine={false}
                    tickFormatter={(v: number) => Math.round(v / 60) + "h"} />
                  <Tooltip contentStyle={{ background: "var(--card)", borderColor: "var(--line)", color: "var(--ink)" }}
                    formatter={(v) => hm(Number(v))} />
                  <Bar dataKey="mins" fill="#007aff" isAnimationActive={false} minPointSize={3} />
                </BarChart>
              </ResponsiveContainer>
            )}
          </section>

          <section className="card" style={{ marginTop: 16 }}>
            <h2>Linha do tempo</h2>
            {data.timeline.length === 0 ? (
              <p className="muted">Nada registrado neste dia.</p>
            ) : (
              <ol className="diario-timeline">
                {data.timeline.map((item) => (
                  <li key={item.kind + "-" + item.id + "-" + item.at}>
                    <span className={"diario-kind k-" + item.kind}>{KIND_LABEL[item.kind] ?? item.kind}</span>
                    <div>
                      <b>{item.title || "—"}</b>
                      <span className="muted"> {item.at}</span>
                      {item.detail && <p className="muted">{item.detail}</p>}
                      {item.kind === "nota" && (
                        <Link className="btn" to={"/notas/" + item.id}>abrir nota</Link>
                      )}
                      {item.kind === "ponto" && (
                        <Link className="btn" to={"/edit/" + item.id}>editar</Link>
                      )}
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </>
      )}
    </>
  );
}
