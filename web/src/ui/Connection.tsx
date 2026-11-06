import { useEffect, useState } from "react";
import { useLeaveGuard } from "./Unsaved";

export function Connection() {
  const [online, setOnline] = useState(navigator.onLine);
  const [waiting, setWaiting] = useState<ServiceWorker | null>(null);
  const canLeave = useLeaveGuard();
  useEffect(() => {
    const update = () => setOnline(navigator.onLine);
    const available = (event: Event) => setWaiting((event as CustomEvent<ServiceWorker>).detail);
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    window.addEventListener("ponto-update", available);
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
      window.removeEventListener("ponto-update", available);
    };
  }, []);
  return <>
    {!online && <p role="status" className="warn">Sem conexão. Suas alterações ainda não foram enviadas.
      <button className="btn" onClick={async () => { if (await canLeave()) location.reload(); }}>Tentar novamente</button></p>}
    {waiting && <p role="status" className="warn">Uma atualização está disponível.
      <button className="btn" onClick={async () => {
        if (!(await canLeave())) return;
        navigator.serviceWorker.addEventListener("controllerchange", () => location.reload(), { once: true });
        waiting.postMessage({ type: "ACTIVATE" });
      }}>Atualizar</button></p>}
  </>;
}
