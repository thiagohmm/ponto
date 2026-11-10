package ponto.model;

import java.util.List;

/** One card on a company's kanban board. Independent of markdown notes. */
public record KanbanCard(int id, int columnId, String title, String body, String done, int position,
                         int revision, String crea, String upd, List<KanbanBlock> blocks) {
    public KanbanCard {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        done = done == null ? "" : done;
    }

    public boolean blocked() { return blocks.stream().anyMatch(KanbanBlock::open); }

    KanbanCard withBlocks(List<KanbanBlock> next) {
        return new KanbanCard(id, columnId, title, body, done, position, revision, crea, upd, next);
    }
}
