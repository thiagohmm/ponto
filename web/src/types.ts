export type Entry = {
  id: number;
  dia: string;
  diaIso: string;
  d3: string;
  off: string;
  tin: string;
  tout: string;
  just: string;
  crea: string;
  dur: string;
  hours: string;
  open: boolean;
  closed: boolean;
  note: boolean;
};

export type EntryList = {
  total: number;
  shown: number;
  skip: number;
  mins: number;
  dur: string;
  hours: string;
  open: number;
  overtime: number;
  faltas: string[];
  normais: string[];
  entries: Entry[];
};

export type Company = {
  id: number;
  name: string;
  off: string;
  daily: string;
  crea: string;
};

export type CompanyList = {
  activeId: number;
  companies: Company[];
};

export type Home = {
  who: string;
  company: string;
  companyId: number;
  records: number;
  monthMins: number;
  monthLabel: string;
  monthHours: string;
  saldo: string;
  saldoMins: number;
  planLabel: string;
  bankNeg: boolean;
  open: number;
  openSince: string;
  db: string;
  min: string;
  max: string;
  from: string;
  to: string;
};

export type DayInfo = {
  dia: string;
  diaIso: string;
  d3: string;
  holiday: string;
  falta: boolean;
  normal: boolean;
  lastOut: string;
  open: Entry[];
  entries: Entry[];
};

export type DaySum = {
  dia: string;
  diaIso: string;
  d3: string;
  lines: number;
  mins: number;
  dur: string;
  hours: string;
};

export type Sum = {
  from: string;
  to: string;
  who: string;
  company?: string;
  total: string;
  hours: string;
  lines: number;
  open: number;
  saldo: string;
  saldoMins: number;
  faltas: number;
  plano: string;
  days: DaySum[];
};

export type BankDay = {
  dia: string;
  diaIso: string;
  d3: string;
  mins: number;
  dur: string;
  hours: string;
  falta: boolean;
  normal: boolean;
  kind: string;
};

export type MonthSaldo = {
  ym: string;
  saldo: string;
  saldoMins: number;
};

export type Bank = {
  ym: string;
  saldo: string;
  saldoMins: number;
  dailyMins: number;
  remaining: number;
  planMins: number;
  planLabel: string;
  neg: boolean;
  days: BankDay[];
  months: MonthSaldo[];
};

export type Config = {
  who: string;
  company: string;
  companyId: number;
  off: string;
  daily: string;
  dailyMins: number;
  db: string;
  records: number;
  from: string;
  to: string;
  java: string;
  sqlite: string;
  nextOff: string[];
  storage: boolean;
  pendingObjects: number;
  uploadMib: number;
};

export type NoteFile = {
  state: "ready" | "uploading" | "failed";
  id: number;
  noteId: number;
  name: string;
  size: number;
  ctype: string;
  crea: string;
};

export type KanbanCardFile = {
  state: "ready" | "uploading" | "failed";
  id: number;
  cardId: number;
  name: string;
  size: number;
  ctype: string;
  crea: string;
};

export type AttachFile = {
  state: "ready" | "uploading" | "failed";
  id: number;
  name: string;
  size: number;
  ctype: string;
  crea: string;
};

export type Note = {
  revision: number;
  id: number;
  title: string;
  body: string;
  crea: string;
  upd: string;
  folderId: number | null;
  tagIds: number[];
  fileNames?: string[];
};

export type Folder = {
  id: number;
  parent: number | null;
  name: string;
  crea: string;
};

export type Tag = {
  id: number;
  name: string;
  color: string;
  crea: string;
};

export type NoteList = {
  total: number;
  notes: Note[];
  folders: Folder[];
  tags: Tag[];
};

export type KanbanColumn = {
  id: number;
  name: string;
  position: number;
  crea: string;
};

export type KanbanBlock = {
  id: number;
  cardId: number;
  kind: string;
  kindLabel: string;
  reason: string;
  crea: string;
  cleared: string;
};

export type KanbanCard = {
  id: number;
  columnId: number;
  title: string;
  body: string;
  done: string;
  position: number;
  revision: number;
  crea: string;
  upd: string;
  blocked: boolean;
  blocks: KanbanBlock[];
};

export const KANBAN_BLOCK_KINDS = [
  { id: "dependencia", label: "Dependência" },
  { id: "cliente", label: "Aguardando cliente" },
  { id: "tecnico", label: "Impedimento técnico" },
  { id: "informacao", label: "Falta de informação" },
  { id: "outro", label: "Outro" },
] as const;

export type KanbanBoard = {
  columns: KanbanColumn[];
  cards: KanbanCard[];
};

export type AssistCategory = {
  code: string;
  label: string;
  synonyms: string[];
  phrases: string[];
};

export type AssistStatus = {
  available: boolean;
  model: string;
  categories: AssistCategory[];
};

export type AssistNoteTags = { tagIds: number[] };
export type AssistJust = { category: string; phrases: string[] };

export class ApiError extends Error {
  status: number;
  need?: string;
  constructor(message: string, status: number, need?: string) {
    super(message);
    this.status = status;
    this.need = need;
  }
}
