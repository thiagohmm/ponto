import { expect, it } from "vitest";
import { classify } from "../src/pages/fileKind";

it("classifies by the stored mime type", () => {
  expect(classify("foto.png", "image/png")).toBe("image");
  expect(classify("foto.SVG", "image/svg+xml")).toBe("image");
  expect(classify("doc.pdf", "application/pdf")).toBe("pdf");
  expect(classify("nota.txt", "text/plain")).toBe("text");
  expect(classify("dados.json", "application/json")).toBe("text");
  expect(classify("planilha.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).toBe("none");
  expect(classify("doc.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")).toBe("none");
  expect(classify("pacote.zip", "application/zip")).toBe("none");
});

it("falls back to the extension only when the mime is blank or generic", () => {
  expect(classify("foto.png", "")).toBe("image");
  expect(classify("doc.pdf", "application/octet-stream")).toBe("pdf");
  expect(classify("lista.csv", "")).toBe("text");
  expect(classify("sem-extensao", "")).toBe("none");
  expect(classify(".oculto", "")).toBe("none");
  expect(classify("final.", "application/octet-stream")).toBe("none");
});

it("lets a known mime win over the extension", () => {
  expect(classify("enganoso.pdf", "image/png")).toBe("image");
  expect(classify("binario.png", "application/zip")).toBe("none");
});
