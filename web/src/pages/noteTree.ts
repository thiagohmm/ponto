import type { Folder, Note } from "../types";

export function fileName(n: Note): string {
  return n.title.trim() || "Sem título";
}



export function folderOf(n: Note): number | null {
  return n.folderId && n.folderId > 0 ? n.folderId : null;
}

export function tagIds(n?: Pick<Note, "tagIds"> | null): number[] {
  return n?.tagIds ?? [];
}

export function kids(folders: Folder[], parent: number | null): Folder[] {
  return folders
    .filter((f) => (f.parent && f.parent > 0 ? f.parent : null) === parent)
    .sort((a, b) => a.name.localeCompare(b.name, "pt"));
}

export function pathOf(folders: Folder[], id: number | null): string {
  const byId = new Map(folders.map((f) => [f.id, f]));
  const parts: string[] = [];
  let cur = id;
  while (cur) {
    const f = byId.get(cur);
    if (!f) break;
    parts.unshift(f.name);
    cur = f.parent && f.parent > 0 ? f.parent : null;
  }
  return parts.join(" / ");
}

export function isInside(folders: Folder[], id: number, dest: number | null): boolean {
  if (dest == null) return false;
  if (id === dest) return true;
  const byId = new Map(folders.map((f) => [f.id, f]));
  let cur: number | null = dest;
  while (cur) {
    if (cur === id) return true;
    const f = byId.get(cur);
    cur = f && f.parent && f.parent > 0 ? f.parent : null;
  }
  return false;
}

