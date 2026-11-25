import { afterEach, expect, it } from "vitest";
import { KANBAN_CACHE_TTL_DAYS, pruneExpired, readKanbanTitles, writeKanbanTitles } from "../src/kanbanCache";

const DAY_MS = 24 * 60 * 60 * 1000;
const NOW = Date.parse("2027-11-25T12:00:00Z");

afterEach(() => localStorage.clear());

it("round-trips titles per company and day", () => {
  writeKanbanTitles(1, "2027-11-25", ["Update versão java"], NOW);
  expect(readKanbanTitles(1, "2027-11-25", NOW)).toEqual(["Update versão java"]);
  expect(readKanbanTitles(2, "2027-11-25", NOW)).toBeNull();
  expect(readKanbanTitles(1, "2027-11-24", NOW)).toBeNull();
});

it("expires entries after 5 days and removes them on read", () => {
  writeKanbanTitles(1, "2027-11-20", ["Antiga"], NOW);
  const later = NOW + KANBAN_CACHE_TTL_DAYS * DAY_MS + 1;
  expect(readKanbanTitles(1, "2027-11-20", later)).toBeNull();
  expect(localStorage.getItem("ponto-kanban-just:1:2027-11-20")).toBeNull();
});

it("keeps entries exactly at the 5-day boundary", () => {
  writeKanbanTitles(1, "2027-11-20", ["No limite"], NOW);
  expect(readKanbanTitles(1, "2027-11-20", NOW + KANBAN_CACHE_TTL_DAYS * DAY_MS)).toEqual(["No limite"]);
});

it("writing sweeps expired entries of any company or day", () => {
  writeKanbanTitles(1, "2027-11-10", ["Velha"], NOW - 10 * DAY_MS);
  writeKanbanTitles(2, "2027-11-11", ["Velha 2"], NOW - 9 * DAY_MS);
  writeKanbanTitles(1, "2027-11-25", ["Nova"], NOW);
  expect(localStorage.getItem("ponto-kanban-just:1:2027-11-10")).toBeNull();
  expect(localStorage.getItem("ponto-kanban-just:2:2027-11-11")).toBeNull();
  expect(readKanbanTitles(1, "2027-11-25", NOW)).toEqual(["Nova"]);
});

it("pruneExpired drops broken entries but keeps fresh ones", () => {
  localStorage.setItem("ponto-kanban-just:9:2027-11-25", "{not json");
  writeKanbanTitles(1, "2027-11-25", ["Ok"], NOW);
  pruneExpired(NOW);
  expect(localStorage.getItem("ponto-kanban-just:9:2027-11-25")).toBeNull();
  expect(readKanbanTitles(1, "2027-11-25", NOW)).toEqual(["Ok"]);
});

it("ignores broken or hand-edited values", () => {
  localStorage.setItem("ponto-kanban-just:1:2027-11-25", "{not json");
  expect(readKanbanTitles(1, "2027-11-25", NOW)).toBeNull();
  localStorage.setItem("ponto-kanban-just:1:2027-11-25", JSON.stringify({ titles: "x", at: NOW }));
  expect(readKanbanTitles(1, "2027-11-25", NOW)).toBeNull();
  localStorage.setItem("ponto-kanban-just:1:2027-11-25", JSON.stringify({ titles: ["ok", 42], at: NOW }));
  expect(readKanbanTitles(1, "2027-11-25", NOW)).toEqual(["ok"]);
});

it("survives localStorage being unavailable", () => {
  const boom = () => { throw new Error("private mode"); };
  const proto = Object.getPrototypeOf(localStorage) as Storage;
  const get = proto.getItem, set = proto.setItem;
  proto.getItem = boom; proto.setItem = boom;
  try {
    expect(() => writeKanbanTitles(1, "2027-11-25", ["x"], NOW)).not.toThrow();
    expect(readKanbanTitles(1, "2027-11-25", NOW)).toBeNull();
  } finally {
    proto.getItem = get; proto.setItem = set;
  }
});