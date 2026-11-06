import type { Note } from "../types";
import { tagIds } from "./noteTree";

/** Lowercase and strip diacritics so "ação" matches "acao". */
export function normalize(s: string): string {
  return s.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
}

/** Everything searchable in a note: title, body, tag names and attachment names. */
export function noteHaystack(n: Note, tagName: (id: number) => string): string {
  return [n.title, n.body, ...tagIds(n).map(tagName), ...(n.fileNames ?? [])].join(" ");
}

/** True when every word of the query appears in the haystack (case/accent insensitive). */
export function noteMatches(query: string, haystack: string): boolean {
  const needle = query.trim();
  if (!needle) return true;
  const hay = normalize(haystack);
  return needle.split(/\s+/).every((w) => {
    const term = normalize(w);
    return term === "" || hay.includes(term);
  });
}
