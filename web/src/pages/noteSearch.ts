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

/** Pre-split normalized search terms for fast matching across many notes. */
export function queryTerms(query: string): string[] {
  const needle = query.trim();
  if (!needle) return [];
  return needle.split(/\s+/).map(normalize).filter(Boolean);
}

/** Fast match against a haystack already normalized with {@link normalize}. */
export function noteMatchesNormalized(terms: string[], normalizedHaystack: string): boolean {
  if (terms.length === 0) return true;
  return terms.every((term) => normalizedHaystack.includes(term));
}

/** True when every word of the query appears in the haystack (case/accent insensitive). */
export function noteMatches(query: string, haystack: string): boolean {
  const terms = queryTerms(query);
  if (terms.length === 0) return true;
  return noteMatchesNormalized(terms, normalize(haystack));
}
