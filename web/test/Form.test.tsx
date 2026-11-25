import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { FormPage } from "../src/pages/Form";
import { api } from "../src/api";
import { ApiError, type DayInfo } from "../src/types";
vi.mock("../src/api", () => ({ api: { day: vi.fn(), create: vi.fn(), kanbanActivity: vi.fn() } }));
beforeEach(() => {
  vi.mocked(api.kanbanActivity).mockResolvedValue({ dia: "", titles: [] });
});
afterEach(cleanup);
const day = (dia: string, holiday: string, lastOut = "") => ({ dia, holiday, lastOut, open: [], entries: [] }) as unknown as DayInfo;
it("ignores an old date response after the date changes", async () => {
  let old!: (value: DayInfo) => void;
  vi.mocked(api.day).mockImplementationOnce(() => new Promise(resolve => { old = resolve; }))
    .mockResolvedValue(day("2026-05-21", "feriado mais recente"));
  render(<MemoryRouter><FormPage /></MemoryRouter>);
  fireEvent.change(screen.getByLabelText("Dia"), { target: { value: "2026-05-21" } });
  await screen.findByText(/feriado mais recente/);
  await act(async () => old(day("2026-05-20", "resposta antiga")));
  expect(screen.queryByText(/resposta antiga/)).toBeNull();
});
it("suggests last out + 1h as the entrada on a day that already has a closed punch", async () => {
  vi.mocked(api.day).mockResolvedValue(day("2026-05-20", "", "12:00"));
  render(<MemoryRouter><FormPage /></MemoryRouter>);
  const entrada = await screen.findByPlaceholderText("07:30");
  await waitFor(() => expect((entrada as HTMLInputElement).value).toBe("13:00"));
});

it("wraps the suggested entrada to 00:xx when the last out is past 23:00", async () => {
  vi.mocked(api.day).mockResolvedValue(day("2026-05-20", "", "23:15"));
  render(<MemoryRouter><FormPage /></MemoryRouter>);
  const entrada = await screen.findByPlaceholderText("07:30");
  await waitFor(() => expect((entrada as HTMLInputElement).value).toBe("00:15"));
});

it("disables Gerar justificativa when the day has no kanban activity", async () => {
  vi.mocked(api.day).mockResolvedValue(day("2026-05-20", ""));
  vi.mocked(api.kanbanActivity).mockResolvedValue({ dia: "2026-05-20", titles: [] });
  render(<MemoryRouter><FormPage /></MemoryRouter>);
  const btn = await screen.findByRole("button", { name: "Gerar justificativa" }) as HTMLButtonElement;
  await waitFor(() => expect(btn.disabled).toBe(true));
});

it("fills the justificativa with the day's kanban titles without duplicating", async () => {
  vi.mocked(api.day).mockResolvedValue(day("2026-05-20", ""));
  vi.mocked(api.kanbanActivity).mockResolvedValue({
    dia: "2026-05-20", titles: ["Update versão java", "Corrigir bug X"],
  });
  render(<MemoryRouter><FormPage /></MemoryRouter>);
  const btn = await screen.findByRole("button", { name: "Gerar justificativa" }) as HTMLButtonElement;
  await waitFor(() => expect(btn.disabled).toBe(false));
  const box = screen.getByLabelText(/Justificativa/) as HTMLTextAreaElement;
  fireEvent.change(box, { target: { value: "update versão java" } });
  fireEvent.click(btn);
  expect(box.value).toBe("update versão java\nCorrigir bug X");
  fireEvent.click(btn);
  expect(box.value).toBe("update versão java\nCorrigir bug X");
});

it("reloads kanban titles when the day changes", async () => {
  vi.mocked(api.day).mockResolvedValue(day("2026-05-21", ""));
  vi.mocked(api.kanbanActivity).mockImplementation(async (dia: string) =>
    dia === "2026-05-21"
      ? { dia, titles: ["Tarefa do dia 21"] }
      : { dia, titles: [] });
  render(<MemoryRouter><FormPage /></MemoryRouter>);
  fireEvent.change(screen.getByLabelText("Dia"), { target: { value: "2026-05-21" } });
  const btn = await screen.findByRole("button", { name: "Gerar justificativa" }) as HTMLButtonElement;
  await waitFor(() => expect(btn.disabled).toBe(false));
  fireEvent.click(btn);
  expect((screen.getByLabelText(/Justificativa/) as HTMLTextAreaElement).value).toBe("Tarefa do dia 21");
  fireEvent.change(screen.getByLabelText("Dia"), { target: { value: "2026-05-20" } });
  await waitFor(() => expect(btn.disabled).toBe(true));
});

it("requires a new midnight confirmation when the times change", async () => {
  vi.mocked(api.day).mockResolvedValue(day("2026-05-20", ""));
  vi.mocked(api.create).mockRejectedValue(new ApiError("cruza meia-noite", 409, "midnight"));
  render(<MemoryRouter><FormPage /></MemoryRouter>);
  fireEvent.change(screen.getByPlaceholderText("07:30"), { target: { value: "2200" } });
  fireEvent.change(screen.getByPlaceholderText("12:00"), { target: { value: "0200" } });
  fireEvent.click(screen.getByRole("button", { name: "Salvar registro" }));
  const confirmation = await screen.findByLabelText("Confirmar virada de meia-noite") as HTMLInputElement;
  expect(confirmation.checked).toBe(true);
  fireEvent.change(screen.getByPlaceholderText("12:00"), { target: { value: "0300" } });
  expect(screen.queryByLabelText("Confirmar virada de meia-noite")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Salvar registro" }));
  await waitFor(() => expect(api.create).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.create).mock.calls[1][0].confirmMidnight).toBe(false);
});
