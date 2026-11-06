import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { FormPage } from "../src/pages/Form";
import { api } from "../src/api";
import { ApiError, type DayInfo } from "../src/types";
vi.mock("../src/api", () => ({ api: { day: vi.fn(), create: vi.fn() } }));
afterEach(cleanup);
const day = (dia: string, holiday: string) => ({ dia, holiday, lastOut: "", open: [], entries: [] }) as unknown as DayInfo;
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
