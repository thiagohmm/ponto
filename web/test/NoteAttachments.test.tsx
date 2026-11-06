import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { NoteAttachments } from "../src/pages/NoteAttachments";
import { api } from "../src/api";
import type { NoteFile } from "../src/types";

vi.mock("../src/api", () => ({
  api: {
    fileDownloadUrl: (noteId: number, fileId: number) =>
      "/api/notes/" + noteId + "/files/" + fileId + "/download?companyId=1",
    fileBytes: vi.fn(),
  },
}));

const createObjectURL = vi.fn(() => "blob:test-123");
const revokeObjectURL = vi.fn();

beforeEach(() => {
  URL.createObjectURL = createObjectURL;
  URL.revokeObjectURL = revokeObjectURL;
  createObjectURL.mockClear();
  revokeObjectURL.mockClear();
});

afterEach(() => {
  cleanup();
  vi.mocked(api.fileBytes).mockReset();
});

const file = (over: Partial<NoteFile> = {}): NoteFile => ({
  state: "ready",
  id: 7,
  noteId: 5,
  name: "relatorio.pdf",
  size: 1234,
  ctype: "application/pdf",
  crea: "2026-11-05 10:00",
  ...over,
});

const props = (files: NoteFile[]) => ({
  currentId: 5,
  files,
  uploading: false,
  dropFiles: false,
  fileRef: { current: null },
  upload: vi.fn(),
  removeFile: vi.fn(),
});

it("opens the preview in an overlay instead of downloading", async () => {
  vi.mocked(api.fileBytes).mockResolvedValue(new Blob(["%PDF"], { type: "application/pdf" }));
  const { container } = render(<NoteAttachments {...props([file()])} />);
  const name = screen.getByRole("button", { name: "relatorio.pdf" });
  expect(name.getAttribute("download")).toBeNull();
  fireEvent.click(name);
  expect(await screen.findByRole("dialog")).toBeTruthy();
  await waitFor(() => expect(container.querySelector("iframe")).not.toBeNull());
  const links = screen.getAllByRole("link", { name: "baixar relatorio.pdf" });
  expect(links).toHaveLength(2);
  for (const l of links) expect(l.getAttribute("download")).not.toBeNull();
});

it("keeps an explicit download link that does not open the preview", () => {
  render(<NoteAttachments {...props([file()])} />);
  const dl = screen.getByRole("link", { name: "baixar relatorio.pdf" });
  expect(dl.getAttribute("download")).not.toBeNull();
  fireEvent.click(dl);
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(api.fileBytes).not.toHaveBeenCalled();
});

it("shows a not-previewable message without fetching bytes", async () => {
  render(<NoteAttachments {...props([file({ name: "planilha.xlsx", ctype: "application/vnd.ms-excel" })])} />);
  fireEvent.click(screen.getByRole("button", { name: "planilha.xlsx" }));
  expect(await screen.findByText(/não é possível visualizar/i)).toBeTruthy();
  expect(api.fileBytes).not.toHaveBeenCalled();
});

it("does not fetch a pending file and hides its download link", async () => {
  render(<NoteAttachments {...props([file({ state: "uploading" })])} />);
  fireEvent.click(screen.getByRole("button", { name: "relatorio.pdf" }));
  expect(await screen.findByText(/ainda não está disponível/i)).toBeTruthy();
  expect(api.fileBytes).not.toHaveBeenCalled();
  expect(screen.queryByRole("link", { name: "baixar relatorio.pdf" })).toBeNull();
});

it("renders text files in the preview", async () => {
  vi.mocked(api.fileBytes).mockResolvedValue(new Blob(["conteúdo do texto"], { type: "text/plain" }));
  render(<NoteAttachments {...props([file({ name: "nota.txt", ctype: "text/plain" })])} />);
  fireEvent.click(screen.getByRole("button", { name: "nota.txt" }));
  expect(await screen.findByText("conteúdo do texto")).toBeTruthy();
});

it("renders images in the preview", async () => {
  vi.mocked(api.fileBytes).mockResolvedValue(new Blob(["img"], { type: "image/png" }));
  render(<NoteAttachments {...props([file({ name: "foto.png", ctype: "image/png" })])} />);
  fireEvent.click(screen.getByRole("button", { name: "foto.png" }));
  const img = await screen.findByAltText("foto.png");
  expect(img.getAttribute("src")).toBe("blob:test-123");
});

it("closes with Escape and revokes the object url", async () => {
  vi.mocked(api.fileBytes).mockResolvedValue(new Blob(["img"], { type: "image/png" }));
  render(<NoteAttachments {...props([file({ name: "foto.png", ctype: "image/png" })])} />);
  fireEvent.click(screen.getByRole("button", { name: "foto.png" }));
  await screen.findByAltText("foto.png");
  fireEvent.keyDown(window, { key: "Escape" });
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(revokeObjectURL).toHaveBeenCalled();
});
