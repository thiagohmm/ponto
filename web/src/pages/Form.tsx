import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import { maskTime, nowHm, timeOk } from "../mask";
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
  const tinRef = useRef<HTMLInputElement>(null);

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

  async function save(outro = false) {
    setErr("");
    setNeed("");
    const badIn = timeOk(tin), badOut = timeOk(tout);
    if (badIn || badOut) {
      setErr("entrada" + (badIn ? " " + badIn : "") + (badIn && badOut ? " · " : "")
        + "saída" + (badOut ? " " + badOut : "") + " — corrija antes de salvar");
      return;
    }
    setBusy(true);
    const body = { dia, tin, tout, just, closeOpen, confirmMidnight, confirmOverlap };
    try {
      if (editing) {
        await api.update(Number(id), body);
        nav("/");
        return;
      }
      await api.create(body);
      if (!outro) {
        nav("/");
        return;
      }
      // cadastro contínuo: o mesmo dia, o par limpo, o foco de volta na entrada
      setTin("");
      setTout("");
      setJust("");
      setNeed("");
      setConfirmMidnight(false);
      setConfirmOverlap(false);
      const d = await api.day(dia);
      setDay(d);
      setCloseOpen(d.open.length > 0);
      if (d.lastOut) setTin(d.lastOut);
      tinRef.current?.focus();
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
          <div className="row">
            <input ref={tinRef} className={"hora" + (timeOk(tin) ? " bad" : "")} placeholder="07:30" value={tin}
              inputMode="numeric" autoComplete="off"
              onChange={(e) => setTin(maskTime(e.target.value, tin))} />
            <button className="btn" type="button" tabIndex={-1} title="usar a hora do relógio"
              onClick={() => setTin(nowHm())}>agora</button>
          </div>
        </label>
        <label>Saída
          <div className="row">
            <input className={"hora" + (timeOk(tout) ? " bad" : "")} placeholder="12:00" value={tout}
              inputMode="numeric" autoComplete="off"
              onChange={(e) => setTout(maskTime(e.target.value, tout))} />
            <button className="btn" type="button" tabIndex={-1} title="usar a hora do relógio"
              onClick={() => setTout(nowHm())}>agora</button>
          </div>
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
        <button className="btn primary" disabled={busy} onClick={() => void save(false)}>Salvar</button>
        {!editing && (
          <button className="btn" disabled={busy}
            title="salva o par e continua neste dia para lançar o próximo"
            onClick={() => void save(true)}>Lançar outra</button>
        )}
        <Link className="btn" to="/">Cancelar</Link>
      </div>
    </div>
  );
}
