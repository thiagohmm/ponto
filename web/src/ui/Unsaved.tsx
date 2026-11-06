import { createContext, useCallback, useContext, useEffect, useRef, type ReactNode } from "react";
import { useBlocker } from "react-router-dom";
import { useConfirm } from "./Confirm";

type Guard = {
  mark: (key: symbol, dirty: boolean) => void;
  canLeave: () => Promise<boolean>;
};
const Context = createContext<Guard>({ mark: () => {}, canLeave: async () => true });

export function UnsavedProvider({ children }: { children: ReactNode }) {
  const dirty = useRef(new Set<symbol>());
  const confirm = useConfirm();
  const mark = useCallback((key: symbol, value: boolean) => {
    if (value) dirty.current.add(key); else dirty.current.delete(key);
  }, []);
  const canLeave = useCallback(async () => {
    if (!dirty.current.size) return true;
    const leave = await confirm({ title: "Alterações não salvas", message: "Há alterações pendentes. Deseja descartá-las e sair?",
      ok: "Descartar e sair", cancel: "Continuar editando", tone: "stamp" });
    if (leave) dirty.current.clear();
    return leave;
  }, [confirm]);
  const blocker = useBlocker(() => dirty.current.size > 0);
  useEffect(() => {
    if (blocker.state !== "blocked") return;
    let active = true;
    void canLeave().then(leave => { if (active) { if (leave) blocker.proceed(); else blocker.reset(); } });
    return () => { active = false; };
  }, [blocker, canLeave]);
  useEffect(() => {
    const onUnload = (event: BeforeUnloadEvent) => {
      if (!dirty.current.size) return;
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", onUnload);
    return () => window.removeEventListener("beforeunload", onUnload);
  }, []);
  return <Context.Provider value={{ mark, canLeave }}>{children}</Context.Provider>;
}

export function useLeaveGuard() { return useContext(Context).canLeave; }

export function useUnsavedChanges(dirty: boolean) {
  const { mark } = useContext(Context);
  const key = useRef(Symbol());
  useEffect(() => { mark(key.current, dirty); return () => mark(key.current, false); }, [dirty, mark]);
  return useCallback(() => mark(key.current, false), [mark]);
}
