package ponto.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.Bad;
import ponto.Conflict;
import ponto.NotFound;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KanbanTest {
    @TempDir Path dir;

    private Service open(String name) {
        return new Service(Db.open(dir.resolve(name).toString()));
    }

    @Test void freshCompanyGetsDefaultColumns() {
        try (Db db = Db.open(dir.resolve("seed.db").toString())) {
            Service s = new Service(db);
            List<KanbanColumn> cols = s.kanban().columns();
            assertEquals(List.of("A fazer", "Em andamento", "Feito"),
                    cols.stream().map(KanbanColumn::name).toList());
            assertEquals(List.of(0, 1, 2), cols.stream().map(KanbanColumn::position).toList());
        }
    }

    @Test void columnAndCardCrudScopedByCompany() {
        try (Db db = Db.open(dir.resolve("scope.db").toString())) {
            Service root = new Service(db);
            int a = root.companyId();
            int b = root.companies().create("Outra", "", "").id();
            Service sa = root.forCompany(a);
            Service sb = root.forCompany(b);

            KanbanColumn col = sa.kanban().createColumn("Backlog");
            KanbanCard card = sa.kanban().createCard("Tarefa", "detalhe", col.id());
            assertEquals(1, sa.kanban().cards().size());
            assertEquals(0, sb.kanban().cards().size());
            assertThrows(NotFound.class, () -> sb.kanban().byId(card.id()));
            assertThrows(NotFound.class, () -> sb.kanban().deleteColumn(col.id()));

            sa.kanban().updateColumn(col.id(), "Fila", null);
            assertEquals("Fila", sa.kanban().columns().stream().filter(c -> c.id() == col.id()).findFirst().orElseThrow().name());

            sa.kanban().deleteCard(card.id());
            assertTrue(sa.kanban().cards().isEmpty());
            sa.kanban().deleteColumn(col.id());
            assertTrue(sa.kanban().columns().stream().noneMatch(c -> c.id() == col.id()));
        }
    }

    @Test void moveBetweenColumnsAndRevisionConflict() {
        try (Db db = Db.open(dir.resolve("move.db").toString())) {
            Service s = new Service(db);
            List<KanbanColumn> cols = s.kanban().columns();
            int todo = cols.get(0).id();
            int doing = cols.get(1).id();

            KanbanCard a = s.kanban().createCard("A", "", todo);
            KanbanCard b = s.kanban().createCard("B", "", todo);
            KanbanCard c = s.kanban().createCard("C", "", doing);

            KanbanCard moved = s.kanban().moveCard(a.id(), doing, 0);
            assertEquals(doing, moved.columnId());
            assertEquals(0, moved.position());
            assertEquals(1, s.kanban().byId(c.id()).position());
            assertEquals(0, s.kanban().byId(b.id()).position());

            KanbanCard next = s.kanban().updateCard(a.id(), "A2", "corpo", a.revision());
            assertEquals(a.revision() + 1, next.revision());
            assertThrows(Conflict.class, () -> s.kanban().updateCard(a.id(), "velho", "", a.revision()));
            assertEquals("A2", s.kanban().byId(a.id()).title());
        }
    }

    @Test void blockedCardStaysInProgressAndKeepsHistory() {
        try (Db db = Db.open(dir.resolve("block.db").toString())) {
            Service s = new Service(db);
            List<KanbanColumn> cols = s.kanban().columns();
            int todo = cols.get(0).id();
            int doing = cols.get(1).id();
            int done = cols.get(2).id();
            KanbanCard card = s.kanban().createCard("Parado", "", todo);
            assertThrows(Bad.class, () -> s.kanban().blockCard(card.id(), "tecnico", "sem ambiente"));

            s.kanban().moveCard(card.id(), doing, 0);
            assertThrows(Bad.class, () -> s.kanban().blockCard(card.id(), "nope", "motivo"));
            assertThrows(Bad.class, () -> s.kanban().blockCard(card.id(), "tecnico", "  "));

            KanbanCard blocked = s.kanban().blockCard(card.id(), "tecnico", "sem ambiente");
            assertTrue(blocked.blocked());
            assertEquals("Impedimento técnico", blocked.blocks().getFirst().kindLabel());
            assertEquals("sem ambiente", blocked.blocks().getFirst().reason());
            assertThrows(Bad.class, () -> s.kanban().blockCard(card.id(), "cliente", "outro"));
            assertThrows(Bad.class, () -> s.kanban().moveCard(card.id(), done, 0));
            assertEquals(doing, s.kanban().byId(card.id()).columnId());

            KanbanCard other = s.kanban().createCard("Outro", "", doing);
            KanbanCard reordered = s.kanban().moveCard(card.id(), doing, 1);
            assertEquals(1, reordered.position());
            assertEquals(0, s.kanban().byId(other.id()).position());

            KanbanCard open = s.kanban().unblockCard(card.id());
            assertFalse(open.blocked());
            assertEquals(1, open.blocks().size());
            assertFalse(open.blocks().getFirst().cleared().isEmpty());
            KanbanCard again = s.kanban().blockCard(card.id(), "cliente", "aguardando retorno");
            assertEquals(2, again.blocks().size());
            KanbanCard free = s.kanban().unblockCard(card.id());
            assertEquals(2, free.blocks().size());
            assertEquals(done, s.kanban().moveCard(card.id(), done, 0).columnId());
            assertEquals(2, s.kanban().byId(card.id()).blocks().size());
        }
    }

    @Test void deleteColumnCascadesCards() {
        try (Db db = Db.open(dir.resolve("cascade.db").toString())) {
            Service s = new Service(db);
            int col = s.kanban().createColumn("Temp").id();
            s.kanban().createCard("x", "", col);
            s.kanban().createCard("y", "", col);
            s.kanban().deleteColumn(col);
            assertTrue(s.kanban().cards().isEmpty());
            assertTrue(s.kanban().columns().stream().noneMatch(c -> c.id() == col));
        }
    }
}
