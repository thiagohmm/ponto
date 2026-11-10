import { useEffect, useRef, useState, type DragEvent } from "react";
import { api } from "../api";
import { KANBAN_BLOCK_KINDS, type KanbanCard, type KanbanColumn } from "../types";
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
  const [blockKind, setBlockKind] = useState<string>(KANBAN_BLOCK_KINDS[0].id);
  const [blockReason, setBlockReason] = useState("");
  const dragged = useRef(false);
  const dragging = useRef<number | null>(null);

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

  function inProgress(name: string) {
    return name.trim().toLocaleLowerCase("pt-BR").replace(/\s+/g, " ") === "em andamento";
  }

  function openEdit(card: KanbanCard) {
    setEditing(card);
    setDraftTitle(card.title);
    setDraftBody(card.body);
    setBlockKind(KANBAN_BLOCK_KINDS[0].id);
    setBlockReason("");
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

  async function blockCard() {
    if (!editing) return;
    const reason = blockReason.trim();
    if (!reason) {
      setErr("informe o motivo do bloqueio");
      return;
    }
    setBusy(true);
    setErr("");
    try {
      const next = await api.blockKanbanCard(editing.id, { kind: blockKind, reason });
      setEditing(next);
      setBlockReason("");
      await reload();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não bloqueou");
    } finally {
      setBusy(false);
    }
  }

  async function unblockCard() {
    if (!editing) return;
    const ok = await confirm({
      title: "Desbloquear cartão?",
      message: "O histórico de bloqueio permanece. Depois disso o cartão pode sair de Em andamento.",
      ok: "Desbloquear",
    });
    if (!ok) return;
    setBusy(true);
    setErr("");
    try {
      const next = await api.unblockKanbanCard(editing.id);
      setEditing(next);
      await reload();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "não desbloqueou");
    } finally {
      setBusy(false);
    }
  }

  function dragPayload(e: DragEvent, cardId: number) {
    dragged.current = true;
    dragging.current = cardId;
    e.dataTransfer.setData("text/plain", "card:" + cardId);
    e.dataTransfer.effectAllowed = "move";
  }

  function allowDrop(e: DragEvent, key: string, columnId: number) {
    const card = board.cards.find((c) => c.id === dragging.current);
    if (card?.blocked && card.columnId !== columnId) {
      e.dataTransfer.dropEffect = "none";
      return;
    }
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
    if (card.blocked && card.columnId !== columnId) {
      setErr("cartão bloqueado não pode sair de Em andamento");
      return;
    }
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
              onDragOver={(e) => allowDrop(e, "col:" + col.id, col.id)}
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
                      onDragOver={(e) => { e.stopPropagation(); allowDrop(e, "before:" + col.id + ":" + index, col.id); }}
                      onDrop={(e) => { e.stopPropagation(); dropCard(e, col.id, index); }}
                    />
                    <article
                      className={"kanban-card" + (editing?.id === card.id ? " active" : "") + (card.blocked ? " blocked" : "")}
                      draggable
                      onDragStart={(e) => dragPayload(e, card.id)}
                      onDragEnd={() => { setDropOn(null); dragged.current = false; dragging.current = null; }}
                      onClick={() => { if (!dragged.current) openEdit(card); dragged.current = false; }}
                    >
                      <strong>{card.title || "Sem título"}</strong>
                      {card.body.trim() ? <p>{card.body.trim().slice(0, 120)}</p> : null}
                      {card.blocked && card.blocks.filter((b) => !b.cleared).map((b) => (
                        <p key={b.id} className="kanban-block">{b.kindLabel}: {b.reason}</p>
                      ))}
                    </article>
                  </div>
                ))}
                <div
                  className={"kanban-drop end" + (dropOn === "end:" + col.id ? " on" : "")}
                  onDragOver={(e) => { e.stopPropagation(); allowDrop(e, "end:" + col.id, col.id); }}
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
            <BlockPanel
              card={editing}
              doing={inProgress(board.columns.find((c) => c.id === editing.columnId)?.name ?? "")}
              kind={blockKind}
              reason={blockReason}
              busy={busy}
              onKind={setBlockKind}
              onReason={setBlockReason}
              onBlock={blockCard}
              onUnblock={unblockCard}
            />
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

function BlockPanel({ card, doing, kind, reason, busy, onKind, onReason, onBlock, onUnblock }: {
  card: KanbanCard;
  doing: boolean;
  kind: string;
  reason: string;
  busy: boolean;
  onKind: (kind: string) => void;
  onReason: (reason: string) => void;
  onBlock: () => void;
  onUnblock: () => void;
}) {
  const open = card.blocks.find((b) => !b.cleared);
  if (!doing && card.blocks.length === 0) return null;
  return (
    <div className="kanban-history">
      {open && (
        <p className="kanban-block">Bloqueado — {open.kindLabel}: {open.reason}. Não sai de Em andamento.</p>
      )}
      {doing && !open && (
        <>
          <label className="field">
            Tipo de bloqueio
            <select value={kind} onChange={(e) => onKind(e.target.value)}>
              {KANBAN_BLOCK_KINDS.map((item) => (
                <option key={item.id} value={item.id}>{item.label}</option>
              ))}
            </select>
          </label>
          <label className="field">
            Motivo
            <textarea rows={3} value={reason} onChange={(e) => onReason(e.target.value)} placeholder="Por que o cartão está parado" />
          </label>
          <button type="button" className="btn danger" disabled={busy || !reason.trim()} onClick={onBlock}>Bloquear</button>
        </>
      )}
      {open && (
        <button type="button" className="btn" disabled={busy} onClick={onUnblock}>Desbloquear</button>
      )}
      {card.blocks.length > 0 && (
        <>
          <h4>Histórico de bloqueios</h4>
          <ul>
            {card.blocks.map((b) => (
              <li key={b.id}>
                {b.kindLabel}: {b.reason}
                <small>{b.cleared ? `${b.crea} – ${b.cleared}` : `${b.crea} · bloqueado`}</small>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
