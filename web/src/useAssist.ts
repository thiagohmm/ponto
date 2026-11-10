import { useEffect, useState } from "react";
import { api } from "./api";
import type { AssistStatus } from "./types";
import { useCompany } from "./Company";

/** Soft assist availability for the active company session. */
export function useAssist() {
  const { active } = useCompany();
  const [status, setStatus] = useState<AssistStatus | null>(null);

  useEffect(() => {
    if (!active?.id) {
      setStatus(null);
      return;
    }
    let cancelled = false;
    api.assistStatus()
      .then((s) => { if (!cancelled) setStatus(s); })
      .catch(() => { if (!cancelled) setStatus({ available: false, model: "", categories: [] }); });
    return () => { cancelled = true; };
  }, [active?.id]);

  return {
    available: Boolean(status?.available),
    model: status?.model ?? "",
    status,
  };
}
