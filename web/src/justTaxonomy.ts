/** Soft justification taxonomy — mirror of ponto.model.JustCategory (UX only). */

export type JustCategoryCode =
  | "atestado"
  | "home_office"
  | "cliente"
  | "reuniao"
  | "pessoal"
  | "deslocamento"
  | "outro";

export type JustCategory = {
  code: JustCategoryCode;
  label: string;
  synonyms: string[];
  phrases: string[];
};

export const JUST_CATEGORIES: JustCategory[] = [
  {
    code: "atestado",
    label: "Atestado",
    synonyms: ["atestado", "medico", "médico", "consulta", "cid", "doente", "saude", "saúde"],
    phrases: ["Atestado médico", "Consulta médica", "Afastamento por saúde"],
  },
  {
    code: "home_office",
    label: "Home office",
    synonyms: ["home office", "homeoffice", "remoto", "teletrabalho", "trabalho remoto"],
    phrases: ["Home office", "Trabalho remoto"],
  },
  {
    code: "cliente",
    label: "Cliente",
    synonyms: ["cliente", "visita", "externo", "reunião com cliente", "reuniao com cliente"],
    phrases: ["Visita a cliente", "Atendimento a cliente"],
  },
  {
    code: "reuniao",
    label: "Reunião",
    synonyms: ["reuniao", "reunião", "meeting", "call", "alinhamento"],
    phrases: ["Reunião interna", "Reunião de alinhamento"],
  },
  {
    code: "pessoal",
    label: "Pessoal",
    synonyms: ["pessoal", "particular", "assunto pessoal", "banco de horas"],
    phrases: ["Assunto pessoal", "Saída particular"],
  },
  {
    code: "deslocamento",
    label: "Deslocamento",
    synonyms: ["deslocamento", "trajeto", "transito", "trânsito", "viagem", "transporte"],
    phrases: ["Deslocamento", "Viagem a trabalho"],
  },
  {
    code: "outro",
    label: "Outro",
    synonyms: ["outro", "diversos"],
    phrases: ["Outro motivo"],
  },
];

export function justCategory(code: string | null | undefined): JustCategory | undefined {
  return JUST_CATEGORIES.find((c) => c.code === code);
}

/** True when the justification text matches any synonym of the category. */
export function matchesJustCategory(just: string, code: JustCategoryCode): boolean {
  const cat = justCategory(code);
  if (!cat) return false;
  const n = just.toLowerCase();
  return cat.synonyms.some((s) => s && n.includes(s.toLowerCase()));
}
