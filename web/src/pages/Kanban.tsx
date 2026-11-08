import { useEffect, useRef, useState, type DragEvent } from "react";
import { api } from "../api";
import type { KanbanCard, KanbanColumn } from "../types";
import { ApiError } from "../types";
import { useConfirm } from "../ui/Confirm";

type Board = { columns: KanbanColumn[]; cards: KanbanCard[] };

export function KanbanPage() {
  const confirm = useConfirm();
  const [board, setBoard] = useState<Board>({ columns: [], cards: [] });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [dropOn, setDropOn] = useState<string | null>(null);
  const [editing, setEditing] = useState<KanbanCard | null>(null);
  const [draftTitle, setDraftTitle] = useState("");
  const [draftBody, setDraftBody] = useState("");
  const [renameId, setRenameId] = useState<number | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const dragged = useRef(false);

  async function reload() {
    const data = await api.kanban();
    setBoard(data);
  }

  useEffect(() => {
    reload().catch((e: unknown) => setErr(e instanceof Error ? e.message : "erro"));
  }, []);

  function cardsOf(columnId: number) {
    return board.cards
      .filter((c) => c.columnId === columnId)
      .sort((a, b) => a.position - b.position || a.id - b.id);
  }

  async function addColumn() {
    setBusy(true);
    setErr("");
    try {
      await api.createKanbanColumn({ name: "Nova coluna" });
      await reload();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não criou a coluna");
    } finally {
      setBusy(false);
    }
  }

  async function saveRename(col: KanbanColumn) {
    const name = renameValue.trim() || col.name;
    setRenameId(null);
    if (name === col.name) return;
    setBusy(true);
    setErr("");
    try {
      await api.updateKanbanColumn(col.id, { name });
      await reload();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não renomeou");
    } finally {
      setBusy(false);
    }
  }

  async function removeColumn(col: KanbanColumn) {
    const count = cardsOf(col.id).length;
    const ok = await confirm({
      title: "Apagar coluna?",
      message: count
        ? `A coluna “${col.name}” e ${count} cartão(ões) serão apagados.`
        : `A coluna “${col.name}” será apagada.`,
      ok: "Apagar",
      tone: "stamp",
    });
    if (!ok) return;
    setBusy(true);
    setErr("");
    try {
      await api.removeKanbanColumn(col.id);
      if (editing && editing.columnId === col.id) setEditing(null);
      await reload();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não apagou");
    } finally {
      setBusy(false);
    }
  }

  async function addCard(columnId: number) {
    setBusy(true);
    setErr("");
    try {
      const card = await api.createKanbanCard({ title: "Novo cartão", body: "", columnId });
      await reload();
      openEdit(card);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não criou o cartão");
    } finally {
      setBusy(false);
    }
  }

  function openEdit(card: KanbanCard) {
    setEditing(card);
    setDraftTitle(card.title);
    setDraftBody(card.body);
  }

  async function saveCard() {
    if (!editing) return;
    setBusy(true);
    setErr("");
    try {
      const next = await api.updateKanbanCard(editing.id, {
        title: draftTitle,
        body: draftBody,
        revision: editing.revision,
      });
      setEditing(next);
      setDraftTitle(next.title);
      setDraftBody(next.body);
      await reload();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setErr(e.message);
        try {
          const data = await api.kanban();
          setBoard(data);
          const fresh = data.cards.find((c) => c.id === editing.id);
          if (fresh) {
            setEditing(fresh);
            setDraftTitle(fresh.title);
            setDraftBody(fresh.body);
          }
        } catch { /* ignore */ }
      } else {
        setErr(e instanceof Error ? e.message : "não salvou");
      }
    } finally {
      setBusy(false);
    }
  }

  async function removeCard() {
    if (!editing) return;
    const ok = await confirm({
      title: "Apagar cartão?",
      message: `O cartão “${editing.title || "sem título"}” será apagado.`,
      ok: "Apagar",
      tone: "stamp",
    });
    if (!ok) return;
    setBusy(true);
    setErr("");
    try {
      await api.removeKanbanCard(editing.id);
      setEditing(null);
      await reload();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não apagou");
    } finally {
      setBusy(false);
    }
  }

  function dragPayload(e: DragEvent, cardId: number) {
    dragged.current = true;
    e.dataTransfer.setData("text/plain", "card:" + cardId);
    e.dataTransfer.effectAllowed = "move";
  }

  function allowDrop(e: DragEvent, key: string) {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    setDropOn(key);
  }

  async function dropCard(e: DragEvent, columnId: number, position: number) {
    e.preventDefault();
    setDropOn(null);
    const raw = e.dataTransfer.getData("text/plain");
    if (!raw.startsWith("card:")) return;
    const cardId = Number(raw.slice(5));
    if (!cardId) return;
    const card = board.cards.find((c) => c.id === cardId);
    if (!card) return;
    if (card.columnId === columnId && card.position === position) return;
    // When dropping after itself in the same column, adjust like list reorders.
    let pos = position;
    if (card.columnId === columnId && card.position < position) pos = Math.max(0, position - 1);

    const prev = board;
    setBoard((cur) => {
      const remaining = cur.cards.filter((c) => c.id !== cardId);
      const byColumn = (id: number) =>
        remaining
          .filter((c) => c.columnId === id)
          .sort((a, b) => a.position - b.position || a.id - b.id);
      const target = byColumn(columnId);
      target.splice(Math.max(0, Math.min(pos, target.length)), 0, { ...card, columnId, position: pos });
      const moved = target.map((c, i) => ({ ...c, columnId, position: i }));
      const oldCol =
        card.columnId === columnId
          ? []
          : byColumn(card.columnId).map((c, i) => ({ ...c, position: i }));
      const other = remaining.filter(
        (c) => c.columnId !== columnId && c.columnId !== card.columnId,
      );
      return { ...cur, cards: [...other, ...oldCol, ...moved] };
    });

    try {
      await api.moveKanbanCard(cardId, { columnId, position: pos });
      await reload();
    } catch (e) {
      setBoard(prev);
      setErr(e instanceof Error ? e.message : "não moveu");
    }
  }

  return (
    <div className="kanban">
      {err && <p className="err">{err}</p>}
      <div className="kanban-toolbar">
        <button type="button" className="btn primary" disabled={busy} onClick={addColumn}>+ Coluna</button>
      </div>
      <div className="kanban-board">
        {board.columns.map((col) => {
          const cards = cardsOf(col.id);
          return (
            <section
              key={col.id}
              className={"kanban-column" + (dropOn === "col:" + col.id ? " drop" : "")}
              onDragOver={(e) => allowDrop(e, "col:" + col.id)}
              onDragLeave={() => setDropOn(null)}
              onDrop={(e) => dropCard(e, col.id, cards.length)}
            >
              <header className="kanban-column-head">
                {renameId === col.id ? (
                  <input
                    className="kanban-rename"
                    value={renameValue}
                    autoFocus
                    onChange={(e) => setRenameValue(e.target.value)}
                    onBlur={() => saveRename(col)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") (e.target as HTMLInputElement).blur();
                      if (e.key === "Escape") setRenameId(null);
                    }}
                  />
                ) : (
                  <button
                    type="button"
                    className="kanban-column-title"
                    onClick={() => { setRenameId(col.id); setRenameValue(col.name); }}
                    title="Renomear coluna"
                  >
                    {col.name}
                    <small>{cards.length}</small>
                  </button>
                )}
                <div className="kanban-column-actions">
                  <button type="button" className="btn" disabled={busy} onClick={() => addCard(col.id)}>+ Cartão</button>
                  <button type="button" className="btn danger" disabled={busy} onClick={() => removeColumn(col)}>Apagar</button>
                </div>
              </header>
              <div className="kanban-cards">
                {cards.map((card, index) => (
                  <div key={card.id} className="kanban-slot">
                    <div
                      className={"kanban-drop" + (dropOn === "before:" + col.id + ":" + index ? " on" : "")}
                      onDragOver={(e) => { e.stopPropagation(); allowDrop(e, "before:" + col.id + ":" + index); }}
                      onDrop={(e) => { e.stopPropagation(); dropCard(e, col.id, index); }}
                    />
                    <article
                      className={"kanban-card" + (editing?.id === card.id ? " active" : "")}
                      draggable
                      onDragStart={(e) => dragPayload(e, card.id)}
                      onDragEnd={() => { setDropOn(null); dragged.current = false; }}
                      onClick={() => { if (!dragged.current) openEdit(card); dragged.current = false; }}
                    >
                      <strong>{card.title || "Sem título"}</strong>
                      {card.body.trim() ? <p>{card.body.trim().slice(0, 120)}</p> : null}
                    </article>
                  </div>
                ))}
                <div
                  className={"kanban-drop end" + (dropOn === "end:" + col.id ? " on" : "")}
                  onDragOver={(e) => { e.stopPropagation(); allowDrop(e, "end:" + col.id); }}
                  onDrop={(e) => { e.stopPropagation(); dropCard(e, col.id, cards.length); }}
                />
              </div>
            </section>
          );
        })}
      </div>

      {editing && (
        <div className="overlay" role="presentation" onClick={() => setEditing(null)}>
          <div className="dialog kanban-dialog" role="dialog" aria-modal="true" aria-label="Editar cartão" onClick={(e) => e.stopPropagation()}>
            <h3>Editar cartão</h3>
            <label className="field">
              Título
              <input value={draftTitle} onChange={(e) => setDraftTitle(e.target.value)} />
            </label>
            <label className="field">
              Descrição
              <textarea rows={8} value={draftBody} onChange={(e) => setDraftBody(e.target.value)} />
            </label>
            <div className="dialog-actions">
              <button type="button" className="btn danger" disabled={busy} onClick={removeCard}>Apagar</button>
              <span className="push" />
              <button type="button" className="btn" onClick={() => setEditing(null)}>Fechar</button>
              <button type="button" className="btn primary" disabled={busy} onClick={saveCard}>Salvar</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
