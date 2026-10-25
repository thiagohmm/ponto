import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import type { DayInfo, Entry } from "../types";
import { ApiError } from "../types";

function todayIso() {
  const d = new Date();
  return [
    d.getFullYear(),
    String(d.getMonth() + 1).padStart(2, "0"),
    String(d.getDate()).padStart(2, "0"),
  ].join("-");
}

export function FormPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const editing = Boolean(id);
  const [dia, setDia] = useState(todayIso());
  const [tin, setTin] = useState("");
  const [tout, setTout] = useState("");
  const [just, setJust] = useState("");
  const [day, setDay] = useState<DayInfo | null>(null);
  const [closeOpen, setCloseOpen] = useState(true);
  const [confirmMidnight, setConfirmMidnight] = useState(false);
  const [confirmOverlap, setConfirmOverlap] = useState(false);
  const [need, setNeed] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!id) return;
    api.entry(Number(id)).then((e: Entry) => {
      setDia(e.diaIso);
      setTin(e.tin);
      setTout(e.tout);
      setJust(e.just);
    }).catch((e: unknown) => setErr(e instanceof Error ? e.message : "não achou"));
  }, [id]);

  useEffect(() => {
    if (!dia) return;
    api.day(dia).then((d) => {
      setDay(d);
      if (!editing && !tin && d.lastOut) setTin(d.lastOut);
    }).catch(() => setDay(null));
  }, [dia, editing]);

  async function save() {
    setErr("");
    setNeed("");
    setBusy(true);
    const body = { dia, tin, tout, just, closeOpen, confirmMidnight, confirmOverlap };
    try {
      if (editing) await api.update(Number(id), body);
      else await api.create(body);
      nav("/");
    } catch (e) {
      if (e instanceof ApiError && e.need) {
        setNeed(e.need);
        setErr(e.message + " — confirme abaixo e salve de novo");
        if (e.need === "midnight") setConfirmMidnight(true);
        if (e.need === "overlap") setConfirmOverlap(true);
      } else {
        setErr(e instanceof Error ? e.message : "não salvou");
      }
    } finally {
      setBusy(false);
    }
  }

  const open = day?.open[0];

  return (
    <div className="card">
      <h2>{editing ? "Editar #" + id : "Novo ponto"}</h2>
      {day?.holiday && <p className="warn">{day.dia} {day.d3} · {day.holiday}</p>}
      {err && <p className="err">{err}</p>}

      <div className="form">
        <label>Dia
          <input type="date" value={dia} onChange={(e) => setDia(e.target.value)} />
        </label>
        <label>Entrada
          <input placeholder="07:30 · now" value={tin} onChange={(e) => setTin(e.target.value)} />
        </label>
        <label>Saída
          <input placeholder="12:00 · now" value={tout} onChange={(e) => setTout(e.target.value)} />
        </label>
        <label className="span2">Justificativa
          <textarea rows={3} value={just} onChange={(e) => setJust(e.target.value)} />
        </label>
      </div>

      {!editing && open && (
        <label className="row" style={{ marginTop: 12 }}>
          <input type="checkbox" checked={closeOpen} onChange={(e) => setCloseOpen(e.target.checked)} />
          Fechar o aberto #{open.id} (in {open.tin})
        </label>
      )}

      {(need === "midnight" || confirmMidnight) && (
        <label className="row"><input type="checkbox" checked={confirmMidnight} onChange={(e) => setConfirmMidnight(e.target.checked)} /> Confirmar virada de meia-noite</label>
      )}
      {(need === "overlap" || confirmOverlap) && (
        <label className="row"><input type="checkbox" checked={confirmOverlap} onChange={(e) => setConfirmOverlap(e.target.checked)} /> Confirmar sobreposição</label>
      )}

      {day && day.entries.length > 0 && (
        <p className="muted" style={{ marginTop: 16 }}>
          Já neste dia: {day.entries.map((e) => "#" + e.id + " " + (e.tin || "—") + "-" + (e.tout || "—")).join(" · ")}
        </p>
      )}

      <div className="row" style={{ marginTop: 18 }}>
        <button className="btn primary" disabled={busy} onClick={() => void save()}>Salvar</button>
        <Link className="btn" to="/">Cancelar</Link>
      </div>
    </div>
  );
}
