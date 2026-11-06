import { expect, it } from "vitest";
import { normalize, noteHaystack, noteMatches } from "../src/pages/noteSearch";
import type { Note } from "../src/types";

const note = (over: Partial<Note> = {}): Note => ({
  revision: 1, id: 1, title: "", body: "", crea: "", upd: "",
  folderId: null, tagIds: [], fileNames: [], ...over,
});

it("normalizes case and accents", () => {
  expect(normalize("Ação")).toBe("acao");
  expect(normalize("PONTOS")).toBe("pontos");
});

it("builds the haystack from title, body, tags and attachments", () => {
  const n = note({ title: "t", body: "b", tagIds: [7], fileNames: ["f.pdf"] });
  expect(noteHaystack(n, (id) => (id === 7 ? "tag" : ""))).toBe("t b tag f.pdf");
});

it("matches when every word appears, in any field, order-insensitive", () => {
  const n = note({ title: "ponto", body: "folha", tagIds: [1], fileNames: ["contrato.pdf"] });
  const hay = noteHaystack(n, () => "urgente");
  expect(noteMatches("ponto folha", hay)).toBe(true);
  expect(noteMatches("folha ponto", hay)).toBe(true);
  expect(noteMatches("contrato", hay)).toBe(true);
  expect(noteMatches("urgente", hay)).toBe(true);
  expect(noteMatches("reuniao", noteHaystack(note({ title: "reunião" }), () => ""))).toBe(true);
});

it("rejects a missing word and accepts an empty query", () => {
  const hay = noteHaystack(note({ title: "ponto", body: "folha" }), () => "");
  expect(noteMatches("ponto inexistente", hay)).toBe(false);
  expect(noteMatches("", hay)).toBe(true);
  expect(noteMatches("   ", hay)).toBe(true);
});
