import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import { nowHm } from "../mask";
import { useCompany } from "../Company";
import type { Bank, DayInfo, Entry, Home } from "../types";

function mesPickerAtual() {
  const d = new Date();
  return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0");
}

function lastDiaIso(ym: string) {
  const [y, m] = ym.split("-").map(Number);
  const last = new Date(y, m, 0).getDate();
  return ym + "-" + String(last).padStart(2, "0");
}

function mesFromDia(dia: string) {
  const m = /^(\d{4}-\d{2})/.exec(dia);
  return m ? m[1] : "";
}

export function useHomeData() {
  const { active } = useCompany();
  const request = useRef(0);
  const [loading, setLoading] = useState(true);
  const [home, setHome] = useState<Home | null>(null);
  const [rows, setRows] = useState<Entry[]>([]);
  const [total, setTotal] = useState(0);
  const [filterDur, setFilterDur] = useState("0:00");
  const [filterHours, setFilterHours] = useState("0.00");
  const [filterOpen, setFilterOpen] = useState(0);
  const [filterOt, setFilterOt] = useState(0);
  const [faltas, setFaltas] = useState<Set<string>>(new Set());
  const [normais, setNormais] = useState<Set<string>>(new Set());
  const [bank, setBank] = useState<Bank | null>(null);
  const [todayDay, setTodayDay] = useState<DayInfo | null>(null);
  const [clock, setClock] = useState(nowHm);
  const [q, setQ] = useState("");
  const [dia, setDia] = useState("");
  const [de, setDe] = useState(mesPickerAtual);
  const [ate, setAte] = useState(mesPickerAtual);
  const [err, setErr] = useState("");

  async function load(nextQ = q, nextDia = dia, nextDe = de, nextAte = ate) {
    const current = ++request.current;
    setLoading(true);
    setErr("");
    const useRange = !nextDia && nextDe && nextAte;
    const deV = useRange && nextAte < nextDe ? nextAte : nextDe;
    const ateV = useRange && nextAte < nextDe ? nextDe : nextAte;
    const hoje = (() => {
      const d = new Date();
      return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, "0"), String(d.getDate()).padStart(2, "0")].join("-");
    })();
    try {
      const [h, list, bk, day] = await Promise.all([
        api.home(),
        useRange
          ? api.entriesRange(deV + "-01", lastDiaIso(ateV), 500, nextQ)
          : api.entries({ q: nextQ, dia: nextDia, n: 500 }),
        api.bank(useRange ? ateV : (mesFromDia(nextDia) || undefined)),
        api.day(hoje),
      ]);
      if (current !== request.current) return;
      setHome(h);
      setRows(list.entries);
      setTotal(list.total);
      setFilterDur(list.dur);
      setFilterHours(list.hours);
      setFilterOpen(list.open);
      setFilterOt(list.overtime);
      setFaltas(new Set(list.faltas));
      setNormais(new Set(list.normais));
      setBank(bk);
      setTodayDay(day);
      setClock(nowHm());
    } catch (e) {
      if (current === request.current) setErr(e instanceof Error ? e.message : "Não foi possível carregar os registros.");
    } finally {
      if (current === request.current) setLoading(false);
    }
  }

  useEffect(() => { void load(); }, [active?.id]);

  useEffect(() => {
    const tick = window.setInterval(() => setClock(nowHm()), 30_000);
    const onFocus = () => setClock(nowHm());
    window.addEventListener("focus", onFocus);
    return () => {
      window.clearInterval(tick);
      window.removeEventListener("focus", onFocus);
    };
  }, []);

  return {
    loading, home, rows, setRows, total, filterDur, filterHours, filterOpen, filterOt,
    faltas, setFaltas, normais, setNormais, bank, setBank, todayDay, setTodayDay,
    clock, setClock, q, setQ, dia, setDia, de, setDe, ate, setAte, err, setErr, load,
  };
}
