import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";

export type ConfirmOpts = {
  title: string;
  message?: string;
  ok?: string;
  cancel?: string;
  tone?: "accent" | "ok" | "stamp";
};

type Pending = {
  opts: ConfirmOpts;
  resolve: (ok: boolean) => void;
};

const ConfirmCtx = createContext<(opts: ConfirmOpts) => Promise<boolean>>(async () => true);

export function useConfirm(): (opts: ConfirmOpts) => Promise<boolean> {
  return useContext(ConfirmCtx);
}

function DialogWindow({ pending, done, okRef }: { pending: Pending; done: (ok: boolean) => void; okRef: React.RefObject<HTMLButtonElement | null> }) {
  const opts = pending.opts;
  const tone = opts.tone ?? "accent";
  return (
    <div className="overlay" role="presentation" onClick={() => done(false)}>
      <div className="dialog" role="dialog" aria-modal="true"
        aria-label={opts.title} onClick={(e) => e.stopPropagation()}>
        <h3>{opts.title}</h3>
        {opts.message && <p>{opts.message}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn" onClick={() => done(false)}>
            {opts.cancel ?? "Cancelar"}
          </button>
          <button type="button" ref={okRef}
            className={"btn" + (tone === "stamp" ? " danger" : tone === "ok" ? " ok" : " primary")}
            onClick={() => done(true)}>
            {opts.ok ?? "Confirmar"}
          </button>
        </div>
      </div>
    </div>
  );
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);
  const okRef = useRef<HTMLButtonElement>(null);

  const confirm = useCallback((opts: ConfirmOpts) => new Promise<boolean>((resolve) => {
    setPending({ opts, resolve });
  }), []);

  const done = useCallback((ok: boolean) => {
    pending?.resolve(ok);
    setPending(null);
  }, [pending]);

  useEffect(() => {
    if (!pending) return;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    okRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") done(false);
      else if (e.key === "Tab") {
        const buttons = Array.from(document.querySelectorAll<HTMLButtonElement>(".dialog button"));
        const first = buttons[0], last = buttons.at(-1);
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus(); }
      }
    };
    window.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
      previous?.focus();
    };
  }, [pending, done]);

  return (
    <ConfirmCtx.Provider value={confirm}>
      {children}
      {pending && <DialogWindow pending={pending} done={done} okRef={okRef} />}
    </ConfirmCtx.Provider>
  );
}
