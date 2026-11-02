// máscara de hora NN:NN para os campos de batida (entrada/saída).

/**
 * Formata progressivamente em `NN:NN`: só dígitos, no máximo 4, dois-pontos
 * depois da dezena da hora (`07` → `07:`, `073` → `07:3`).
 *
 * A hora nunca aparece maior que 23: quando o par de horas estoura (`83`,
 * `29`...), vira hora de um dígito complementada com 0 (`8` → `08:`,
 * `29` → `02:9…`) — digitar `89:99` termina `08:99` (e o campo fica `bad`;
 * o Salvar continua barrando minuto >59).
 *
 * A máscara é ciente da deleção: passe o valor anterior do campo. Quando a
 * string `raw` encolheu (backspace), o `:` não é re-inserido — senão apagar
 * `14:45` travava em `14:`. Inserção continua formatando; colar `8h30` cai
 * no mesmo caminho.
 */
export function maskTime(raw: string, prev = ""): string {
  let d = raw.replace(/\D/g, "").slice(0, 4);
  if (!d) return "";
  if (d.length >= 2 && Number(d.slice(0, 2)) > 23) d = ("0" + d).slice(0, 4);
  else if (Number(d[0]) > 2) d = ("0" + d).slice(0, 4);
  if (raw.length < prev.length) return d.length > 2 ? d.slice(0, 2) + ":" + d.slice(2) : d;
  let out = d.slice(0, 2);
  if (d.length > 2) out += ":" + d.slice(2);
  else if (d.length === 2) out += ":";
  return out;
}

/** Curto motivo de rejeição ("") quando válido: em branco vale (sem batida). */
export function timeOk(v: string): string {
  if (!v) return "";
  if (!/^\d{2}:\d{2}$/.test(v)) return "incompleta (máscara NN:NN)";
  if (Number(v.slice(0, 2)) > 23) return "com hora maior que 23";
  if (Number(v.slice(3)) > 59) return "com minuto maior que 59";
  return "";
}

/** A hora do relógio local como "HH:MM" (o botão `agora`). */
export function nowHm(): string {
  const d = new Date();
  return String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0");
}
