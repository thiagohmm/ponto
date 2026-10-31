export type Entry = {
  id: number;
  dia: string;
  diaIso: string;
  d3: string;
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
  entries: Entry[];
};

export type Home = {
  who: string;
  records: number;
  monthMins: number;
  monthLabel: string;
  monthHours: string;
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
  total: string;
  hours: string;
  lines: number;
  open: number;
  days: DaySum[];
};

export type Config = {
  who: string;
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
};

export type Note = {
  id: number;
  title: string;
  body: string;
  crea: string;
  upd: string;
  folderId: number | null;
  tagIds: number[];
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

export class ApiError extends Error {
  status: number;
  need?: string;
  constructor(message: string, status: number, need?: string) {
    super(message);
    this.status = status;
    this.need = need;
  }
}
