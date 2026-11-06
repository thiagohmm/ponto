export type FileKind = "image" | "pdf" | "text" | "none";

const IMAGE_EXT = new Set(["png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif"]);
const TEXT_EXT = new Set(["txt", "md", "csv", "json", "log", "xml"]);

function extOf(name: string): string {
  const i = name.lastIndexOf(".");
  if (i <= 0 || i === name.length - 1) return "";
  return name.slice(i + 1).toLowerCase();
}

/**
 * Decide o modo de pré-visualização. O MIME gravado manda; a extensão
 * só entra quando o MIME está vazio ou genérico (octet-stream).
 */
export function classify(name: string, ctype: string): FileKind {
  const mime = (ctype ?? "").trim().toLowerCase();
  if (mime) {
    if (mime.startsWith("image/")) return "image";
    if (mime === "application/pdf") return "pdf";
    if (mime.startsWith("text/") || mime === "application/json") return "text";
    if (mime !== "application/octet-stream") return "none";
  }
  const ext = extOf(name);
  if (IMAGE_EXT.has(ext)) return "image";
  if (ext === "pdf") return "pdf";
  if (TEXT_EXT.has(ext)) return "text";
  return "none";
}
