import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useUnsavedChanges } from "../ui/Unsaved";
import { api } from "../api";
import { justCategory } from "../justTaxonomy";
import { maskTime, nowHm, timeOk } from "../mask";
import { useAssist } from "../useAssist";
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
  const { available: assistOn } = useAssist();
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
  const [loaded, setLoaded] = useState(!editing);
  const [justCat, setJustCat] = useState("");
  const [justPhrases, setJustPhrases] = useState<string[]>([]);
  const tinRef = useRef<HTMLInputElement>(null);
  const justReq = useRef(0);
  const [initial, setInitial] = useState(() => JSON.stringify([todayIso(), "", "", ""]));
  const markClean = useUnsavedChanges(JSON.stringify([dia, tin, tout, just]) !== initial || busy);

  function changeTimes(field: "dia" | "tin" | "tout", value: string) {
    setConfirmMidnight(false); setConfirmOverlap(false); setNeed("");
    if (field === "dia") setDia(value);
    else if (field === "tin") setTin(value);
    else setTout(value);
  }

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    setLoaded(false);
    api.entry(Number(id)).then((e: Entry) => {
      if (cancelled) return;
      setInitial(JSON.stringify([e.diaIso, e.tin, e.tout, e.just]));
      setDia(e.diaIso);
      setTin(e.tin);
      setTout(e.tout);
      setJust(e.just);
      setLoaded(true);
    }).catch((e: unknown) => { if (!cancelled) setErr(e instanceof Error ? e.message : "não achou"); });
    return () => { cancelled = true; };
  }, [id]);

  useEffect(() => {
    if (!dia) return;
    let cancelled = false;
    setDay(null);
    api.day(dia).then((d) => {
      if (cancelled) return;
      setDay(d);
      if (!editing && d.lastOut) setTin(current => current || d.lastOut);
    }).catch(() => { if (!cancelled) setDay(null); });
    return () => { cancelled = true; };
  }, [dia, editing]);

  useEffect(() => {
    if (!assistOn) {
      setJustCat("");
      setJustPhrases([]);
      return;
    }
    const text = just.trim();
    if (text.length < 3) {
      setJustCat("");
      setJustPhrases([]);
      return;
    }
    const current = ++justReq.current;
    const t = window.setTimeout(() => {
      api.assistJust({ text }).then((r) => {
        if (current !== justReq.current) return;
        setJustCat(r.category || "");
        setJustPhrases(r.phrases ?? []);
      }).catch(() => {
        if (current !== justReq.current) return;
        setJustCat("");
        setJustPhrases([]);
      });
    }, 400);
    return () => { window.clearTimeout(t); };
  }, [just, assistOn]);

  async function save(outro = false) {
    if (busy || !loaded) return;
    setErr("");
    setNeed("");
    const badIn = timeOk(tin), badOut = timeOk(tout);
    if (badIn || badOut) {
      setErr([badIn && "entrada " + badIn, badOut && "saída " + badOut].filter(Boolean).join(" · ") + " — corrija antes de salvar");
      return;
    }
    setBusy(true);
    const body = { dia, tin, tout, just, closeOpen, confirmMidnight, confirmOverlap };
    try {
      if (editing) {
        await api.update(Number(id), body);
        markClean();
        nav("/");
        return;
      }
      await api.create(body);
      if (!outro) {
        markClean();
        nav("/");
        return;
      }
      // cadastro contínuo: o mesmo dia, o par limpo, o foco de volta na entrada
      setTin("");
      setTout("");
      setJust("");
      setJustCat("");
      setJustPhrases([]);
      setNeed("");
      setConfirmMidnight(false);
      setConfirmOverlap(false);
      setInitial(JSON.stringify([dia, "", "", ""]));
      try {
        const d = await api.day(dia);
        setDay(d); setCloseOpen(d.open.length > 0);
        if (d.lastOut) { setTin(d.lastOut); setInitial(JSON.stringify([dia, d.lastOut, "", ""])); }
      } catch { setErr("Registro salvo. Não foi possível atualizar os horários do dia; confira a conexão antes do próximo lançamento."); }
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
      <h2>{editing ? "Editar #" + id : "Dados do registro"}</h2>
      {day?.holiday && <p className="warn">{day.dia} {day.d3} · {day.holiday}</p>}
      {err && <p role="alert" className="err">{err}</p>}

      <div className="form">
        <label>Dia
          <input type="date" value={dia} onChange={(e) => changeTimes("dia", e.target.value)} />
        </label>
        <label>Entrada
          <div className="row">
            <input ref={tinRef} className={"hora" + (timeOk(tin) ? " bad" : "")} placeholder="07:30" value={tin}
              inputMode="numeric" autoComplete="off"
              aria-invalid={Boolean(timeOk(tin))} aria-describedby="entrada-erro" onChange={(e) => changeTimes("tin", maskTime(e.target.value, tin))} />
            <button className="btn" type="button" title="usar a hora do relógio"
              onClick={() => changeTimes("tin", nowHm())}>Agora</button>
          </div><small id="entrada-erro" className="field-error">{timeOk(tin)}</small>
        </label>
        <label>Saída
          <div className="row">
            <input className={"hora" + (timeOk(tout) ? " bad" : "")} placeholder="12:00" value={tout}
              inputMode="numeric" autoComplete="off"
              aria-invalid={Boolean(timeOk(tout))} aria-describedby="saida-erro" onChange={(e) => changeTimes("tout", maskTime(e.target.value, tout))} />
            <button className="btn" type="button" title="usar a hora do relógio"
              onClick={() => changeTimes("tout", nowHm())}>Agora</button>
          </div><small id="saida-erro" className="field-error">{timeOk(tout)}</small>
        </label>
        <label className="span2">Justificativa
          <textarea rows={3} value={just} onChange={(e) => setJust(e.target.value)} />
          {assistOn && (justCat || justPhrases.length > 0) && (
            <div className="assist-just" aria-live="polite">
              {justCat && (
                <span className="assist-cat">{justCategory(justCat)?.label ?? justCat}</span>
              )}
              {justPhrases.map((p) => (
                <button
                  key={p}
                  type="button"
                  className="chip assist-phrase"
                  onClick={() => setJust(p)}
                  title="usar esta frase"
                >{p}</button>
              ))}
            </div>
          )}
        </label>
      </div>

      {!editing && open && (
        <label className="row" style={{ marginTop: 12 }}>
          <input type="checkbox" checked={closeOpen} onChange={(e) => { setCloseOpen(e.target.checked); setConfirmMidnight(false); setConfirmOverlap(false); setNeed(""); }} />
          Fechar o aberto #{open.id} (entrada {open.tin})
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
        <button className="btn primary" disabled={busy || !loaded} onClick={() => void save(false)}>{busy ? "Salvando…" : "Salvar registro"}</button>
        {!editing && (
          <button className="btn" disabled={busy || !loaded}
            title="salva o par e continua neste dia para lançar o próximo"
            onClick={() => void save(true)}>Salvar e adicionar outro</button>
        )}
        <Link className="btn" to="/">Cancelar</Link>
      </div>
    </div>
  );
}
