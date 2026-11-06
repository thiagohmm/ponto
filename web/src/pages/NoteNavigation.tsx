import { useRef, type CSSProperties, type DragEvent, type ReactNode, type Dispatch, type SetStateAction } from "react";
import { excerpt, todoStats } from "../md";
import type { Folder, Note, Tag } from "../types";
import { fileName, folderOf, tagIds, kids, pathOf } from "./noteTree";
type Setter<T> = Dispatch<SetStateAction<T>>;
type Props = {
  notes: Note[]; folders: Folder[]; tags: Tag[]; visible: Note[]; currentId: number;
  q: string; setQ: Setter<string>; here: number | null; setHere: Setter<number | null>;
  tagHere: number | null; setTagHere: Setter<number | null>;
  open: Record<number, boolean>; setOpen: Setter<Record<number, boolean>>;
  dropOn: number | string | null; setDropOn: Setter<number | string | null>;
  actions: {
    openNote: (id: number) => Promise<unknown>;
    create: (title?: string, body?: string) => Promise<unknown>;
    addFolder: () => Promise<unknown>; addTag: () => Promise<unknown>;
    renameFolder: (f: Folder) => Promise<unknown>; removeFolder: (f: Folder) => Promise<unknown>;
    renameTag: (t: Tag) => Promise<unknown>; removeTag: (t: Tag) => Promise<unknown>;
    dropOnTag: (raw: string, tag: number) => Promise<unknown>;
    dropItem: (raw: string, destination: number | null) => Promise<unknown>;
  };
};

const TAG_PATH = "M12.59 2.59A2 2 0 0 0 11.17 2H4a2 2 0 0 0-2 2v7.17a2 2 0 0 0 .59 1.42l8.7 8.7a2.43 2.43 0 0 0 3.42 0l6.58-6.58a2.43 2.43 0 0 0 0-3.42zM8.7 7.5a1.2 1.2 0 1 1-2.4 0 1.2 1.2 0 0 1 2.4 0z";

const TODO_PREFIX = "- [ ] ";

function Ico({ path }: { path: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d={path} />
    </svg>
  );
}

export function NoteNavigation({ notes, folders, tags, visible, currentId, q, setQ, here, setHere,
    tagHere, setTagHere, open, setOpen, dropOn, setDropOn, actions }: Props) {
  const { openNote, create, addFolder, addTag, renameFolder, removeFolder, renameTag, removeTag, dropOnTag, dropItem } = actions;
  const dragged = useRef(false);
  const searching = Boolean(q.trim());
  const filtering = searching || tagHere != null;
  const openTodos = visible.reduce((count, note) => count + todoStats(note.body).open, 0);
  function dragPayload(e: DragEvent, payload: string) {
    dragged.current = true;
    e.dataTransfer.setData("text/plain", payload);
    e.dataTransfer.effectAllowed = "move";
  }

  function allowDrop(e: DragEvent, target: number | "root" | string) {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    setDropOn(target);
  }

  function noteRow(n: Note, depth = 0) {
    const stats = todoStats(n.body);
    const mine = tagIds(n).map((id) => tags.find((t) => t.id === id)).filter((t): t is Tag => Boolean(t));
    return (
      <button
        key={n.id}
        type="button"
        draggable
        className={"vault-file" + (n.id === currentId ? " active" : "")}
        style={{ paddingLeft: 12 + depth * 14 }}
        onDragStart={(e) => dragPayload(e, "note:" + n.id)}
        onDragEnd={() => { setDropOn(null); setTimeout(() => { dragged.current = false; }, 0); }}
        onClick={() => { if (dragged.current) return; void openNote(n.id); }}
      >
        <span className="vault-file-ico" aria-hidden="true" />
        <span className="vault-file-text">
          <b>{fileName(n)}</b>
          {mine.length > 0 && (
            <span className="vault-file-tags">
              {mine.map((t) => (
                <span key={t.id} className="vault-pill" style={{ "--tag": t.color } as CSSProperties}>{t.name}</span>
              ))}
            </span>
          )}
          <i>
            {filtering && pathOf(folders, folderOf(n)) ? pathOf(folders, folderOf(n)) + " · " : ""}
            {stats.total > 0 ? stats.done + "/" + stats.total + " · " : ""}
            {excerpt(n.body) || n.upd}
          </i>
        </span>
      </button>
    );
  }

  function folderBlock(parent: number | null, depth: number): ReactNode {
    const list = kids(folders, parent);
        const files = filtering ? [] : visible.filter((n) => folderOf(n) === parent);
    return (
      <>
        {list.map((f) => {
          const expanded = open[f.id] !== false;
          const count = notes.filter((n) => folderOf(n) === f.id).length;
          return (
            <div key={"f" + f.id} className="vault-branch">
              <div
                className={"vault-folder" + (here === f.id ? " active" : "") + (dropOn === f.id ? " drop" : "")}
                style={{ paddingLeft: 10 + depth * 14 }}
                draggable
                onDragStart={(e) => dragPayload(e, "folder:" + f.id)}
                onDragEnd={() => { setDropOn(null); dragged.current = false; }}
                onDragOver={(e) => allowDrop(e, f.id)}
                onDragLeave={() => setDropOn((d) => d === f.id ? null : d)}
                onDrop={(e) => { e.preventDefault(); void dropItem(e.dataTransfer.getData("text/plain"), f.id); }}
              >
                <button type="button" className={"vault-chevron" + (expanded ? " open" : "")} onClick={() => setOpen((o) => ({ ...o, [f.id]: !expanded }))} title={expanded ? "recolher" : "abrir"}>
                  <Ico path="M9 6l6 6-6 6" />
                </button>
                <span className="vault-folder-ico" aria-hidden="true" />
                <button type="button" className="vault-folder-name" onClick={() => { setHere(f.id); setOpen((o) => ({ ...o, [f.id]: true })); }}>
                  {f.name}
                  {count > 0 && <small>{count}</small>}
                </button>
                <button type="button" className="vault-mini" onClick={() => void renameFolder(f)} title="renomear">
                  <Ico path="M4 16.5 15.5 5l3.5 3.5L7.5 20H4z" />
                </button>
                <button type="button" className="vault-mini danger" onClick={() => void removeFolder(f)} title="apagar pasta">
                  <Ico path="M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M6.5 7l1 12a2 2 0 0 0 2 1.8h5a2 2 0 0 0 2-1.8l1-12" />
                </button>
              </div>
              {expanded && (
                <div className="vault-nest">
                  {folderBlock(f.id, depth + 1)}
                </div>
              )}
            </div>
          );
        })}
        {files.map((n) => noteRow(n, depth))}
      </>
    );
  }

  return (
      <aside className="vault-side">
        <div className="vault-search">
          <input
            placeholder="buscar por palavra"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            autoFocus={!currentId}
          />
          <div className="vault-actions">
            <button type="button" className="vault-icon" title="Nova nota" onClick={() => void create()}>
              <Ico path="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9l-5-5zM14 3v5h5M12 11v6M9 14h6" />
            </button>
            <button type="button" className="vault-icon" title="Lista de tarefas" onClick={() => void create("Tarefas", TODO_PREFIX)}>
              <Ico path="M5 6h3v3H5zM5 13.5 7 15.5 11 11.5M12 7.5h8M12 14.5h8" />
            </button>
            <button type="button" className="vault-icon" title="Nova pasta" onClick={() => void addFolder()}>
              <Ico path="M4 4h5l2 3h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" />
            </button>
            <button type="button" className="vault-icon" title="Nova tag" onClick={() => void addTag()}>
              <Ico path={TAG_PATH} />
            </button>
          </div>
        </div>
        {tags.length > 0 && (
          <div className="vault-tagbar">
            {tags.map((t) => {
              const count = notes.filter((n) => tagIds(n).includes(t.id)).length;
              return (
                <div
                  key={t.id}
                  className={"vault-chip" + (tagHere === t.id ? " on" : "") + (dropOn === "t" + t.id ? " drop" : "")}
                  style={{ "--tag": t.color } as CSSProperties}
                  onClick={() => setTagHere((cur) => cur === t.id ? null : t.id)}
                  onDoubleClick={() => void renameTag(t)}
                  onDragOver={(e) => allowDrop(e, "t" + t.id)}
                  onDragLeave={() => setDropOn((d) => d === "t" + t.id ? null : d)}
                  onDrop={(e) => { e.preventDefault(); void dropOnTag(e.dataTransfer.getData("text/plain"), t.id); }}
                  title="arraste uma nota · clique para filtrar · duplo para renomear"
                >
                  <span className="vault-tag-ico" aria-hidden="true" />
                  {t.name}
                  {count > 0 && <small>{count}</small>}
                  <button type="button" className="vault-chip-x" title="apagar tag" onClick={(e) => { e.stopPropagation(); void removeTag(t); }}>×</button>
                </div>
              );
            })}
          </div>
        )}
        <div
          className={"vault-root" + (here == null ? " active" : "") + (dropOn === "root" ? " drop" : "")}
          onClick={() => setHere(null)}
          onDragOver={(e) => allowDrop(e, "root")}
          onDragLeave={() => setDropOn((d) => d === "root" ? null : d)}
          onDrop={(e) => { e.preventDefault(); void dropItem(e.dataTransfer.getData("text/plain"), null); }}
        >
          <span className="vault-root-ico" aria-hidden="true" />
          Notas
        </div>
        <div className="vault-files">
          {filtering ? visible.map(noteRow) : folderBlock(null, 0)}
          {visible.length === 0 && (filtering || (notes.length === 0 && folders.length === 0)) && (
            <p className="muted vault-empty">
              {searching ? "nada para “" + q + "”"
                : tagHere ? "nenhuma nota com esta tag"
                : "nenhuma nota ainda"}
            </p>
          )}
        </div>
        <p className="muted vault-count">
          {visible.length} nota(s){openTodos > 0 ? " · " + openTodos + " aberta(s)" : ""}
          {here ? " · " + pathOf(folders, here) : ""}
          {tagHere ? " · " + (tags.find((t) => t.id === tagHere)?.name ?? "tag") : ""}
        </p>
      </aside>
  );
}
