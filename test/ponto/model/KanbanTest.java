package ponto.model;

import ponto.app.Service;

import ponto.infra.KanbanStore;

import ponto.infra.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ponto.Bad;
import ponto.Conflict;
import ponto.NotFound;
import ponto.TestDb;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KanbanTest {
    @TempDir Path dir;

    private Service open(String name) {
        return new Service(TestDb.open(dir));
    }

    @Test void freshCompanyGetsDefaultColumns() {
        try (Db db = TestDb.open(dir)) {
            Service s = new Service(db);
            List<KanbanColumn> cols = s.kanbanColumns();
            assertEquals(List.of("A fazer", "Em andamento", "Feito"),
                    cols.stream().map(KanbanColumn::name).toList());
            assertEquals(List.of(0, 1, 2), cols.stream().map(KanbanColumn::position).toList());
        }
    }

    @Test void columnAndCardCrudScopedByCompany() {
        try (Db db = TestDb.open(dir)) {
            Service root = new Service(db);
            int a = root.companyId();
            int b = root.createCompany("Outra", "", "").id();
            Service sa = root.forCompany(a);
            Service sb = root.forCompany(b);

            KanbanColumn col = sa.createKanbanColumn("Backlog");
            KanbanCard card = sa.createKanbanCard("Tarefa", "detalhe", col.id());
            assertEquals(1, sa.kanbanCards().size());
            assertEquals(0, sb.kanbanCards().size());
            assertThrows(NotFound.class, () -> sb.kanbanCardById(card.id()));
            assertThrows(NotFound.class, () -> sb.deleteKanbanColumn(col.id()));

            sa.updateKanbanColumn(col.id(), "Fila", null);
            assertEquals("Fila", sa.kanbanColumns().stream().filter(c -> c.id() == col.id()).findFirst().orElseThrow().name());

            sa.deleteKanbanCard(card.id());
            assertTrue(sa.kanbanCards().isEmpty());
            sa.deleteKanbanColumn(col.id());
            assertTrue(sa.kanbanColumns().stream().noneMatch(c -> c.id() == col.id()));
        }
    }

    @Test void moveBetweenColumnsAndRevisionConflict() {
        try (Db db = TestDb.open(dir)) {
            Service s = new Service(db);
            List<KanbanColumn> cols = s.kanbanColumns();
            int todo = cols.get(0).id();
            int doing = cols.get(1).id();

            KanbanCard a = s.createKanbanCard("A", "", todo);
            KanbanCard b = s.createKanbanCard("B", "", todo);
            KanbanCard c = s.createKanbanCard("C", "", doing);

            KanbanCard moved = s.moveKanbanCard(a.id(), doing, 0);
            assertEquals(doing, moved.columnId());
            assertEquals(0, moved.position());
            assertEquals(1, s.kanbanCardById(c.id()).position());
            assertEquals(0, s.kanbanCardById(b.id()).position());

            KanbanCard next = s.updateKanbanCard(a.id(), "A2", "corpo", "feito X", a.revision());
            assertEquals(a.revision() + 1, next.revision());
            assertEquals("feito X", next.done());
            assertThrows(Conflict.class, () -> s.updateKanbanCard(a.id(), "velho", "", "", a.revision()));
            assertEquals("A2", s.kanbanCardById(a.id()).title());
        }
    }

    @Test void blockedCardCannotBeDoneButCanMoveAndKeepsHistory() {
        try (Db db = TestDb.open(dir)) {
            Service s = new Service(db);
            List<KanbanColumn> cols = s.kanbanColumns();
            int todo = cols.get(0).id();
            int doing = cols.get(1).id();
            int done = cols.get(2).id();

            KanbanCard doneCard = s.createKanbanCard("Concluído", "", done);
            assertThrows(Bad.class, () -> s.blockKanbanCard(doneCard.id(), "tecnico", "não pode em Feito"));

            KanbanCard card = s.createKanbanCard("Parado", "", todo);
            assertThrows(Bad.class, () -> s.blockKanbanCard(card.id(), "nope", "motivo"));
            assertThrows(Bad.class, () -> s.blockKanbanCard(card.id(), "tecnico", "  "));

            KanbanCard blocked = s.blockKanbanCard(card.id(), "tecnico", "sem ambiente");
            assertTrue(blocked.blocked());
            assertEquals("Impedimento técnico", blocked.blocks().getFirst().kindLabel());
            assertEquals("sem ambiente", blocked.blocks().getFirst().reason());
            assertThrows(Bad.class, () -> s.blockKanbanCard(card.id(), "cliente", "outro"));
            assertThrows(Bad.class, () -> s.moveKanbanCard(card.id(), done, 0));
            assertEquals(todo, s.kanbanCardById(card.id()).columnId());

            KanbanCard moved = s.moveKanbanCard(card.id(), doing, 0);
            assertEquals(doing, moved.columnId());

            KanbanCard other = s.createKanbanCard("Outro", "", doing);
            KanbanCard reordered = s.moveKanbanCard(card.id(), doing, 1);
            assertEquals(1, reordered.position());
            assertEquals(0, s.kanbanCardById(other.id()).position());

            KanbanCard open = s.unblockKanbanCard(card.id());
            assertFalse(open.blocked());
            assertEquals(1, open.blocks().size());
            assertFalse(open.blocks().getFirst().cleared().isEmpty());
            KanbanCard again = s.blockKanbanCard(card.id(), "cliente", "aguardando retorno");
            assertEquals(2, again.blocks().size());
            KanbanCard free = s.unblockKanbanCard(card.id());
            assertEquals(2, free.blocks().size());
            assertEquals(done, s.moveKanbanCard(card.id(), done, 0).columnId());
            assertEquals(2, s.kanbanCardById(card.id()).blocks().size());
        }
    }

    @Test void deleteColumnCascadesCards() {
        try (Db db = TestDb.open(dir)) {
            Service s = new Service(db);
            int col = s.createKanbanColumn("Temp").id();
            s.createKanbanCard("x", "", col);
            s.createKanbanCard("y", "", col);
            s.deleteKanbanColumn(col);
            assertTrue(s.kanbanCards().isEmpty());
            assertTrue(s.kanbanColumns().stream().noneMatch(c -> c.id() == col));
        }
    }

    @Test void defaultColumnsCannotBeDeleted() {
        try (Db db = TestDb.open(dir)) {
            Service s = new Service(db);
            for (KanbanColumn col : s.kanbanColumns()) {
                assertTrue(KanbanStore.protectedColumn(col.name()));
                assertThrows(Bad.class, () -> s.deleteKanbanColumn(col.id()));
            }
            assertEquals(List.of("A fazer", "Em andamento", "Feito"),
                    s.kanbanColumns().stream().map(KanbanColumn::name).toList());
            KanbanColumn custom = s.createKanbanColumn("Extra");
            s.deleteKanbanColumn(custom.id());
            assertTrue(s.kanbanColumns().stream().noneMatch(c -> c.id() == custom.id()));
        }
    }

    @Test void doneFieldPersistsAndDefaultsEmpty() {
        try (Db db = TestDb.open(dir)) {
            Service s = new Service(db);
            int todo = s.kanbanColumns().getFirst().id();
            KanbanCard card = s.createKanbanCard("T", "desc", todo);
            assertEquals("", card.done());
            KanbanCard next = s.updateKanbanCard(card.id(), "T", "desc", "implementei X", card.revision());
            assertEquals("implementei X", next.done());
            assertEquals("implementei X", s.kanbanCardById(card.id()).done());
        }
    }

    @Test void reorderColumnsByPosition() {
        try (Db db = TestDb.open(dir)) {
            Service s = new Service(db);
            KanbanColumn extra = s.createKanbanColumn("Extra");
            List<KanbanColumn> before = s.kanbanColumns();
            assertEquals(List.of("A fazer", "Em andamento", "Feito", "Extra"),
                    before.stream().map(KanbanColumn::name).toList());

            s.updateKanbanColumn(extra.id(), null, 1);
            assertEquals(List.of("A fazer", "Extra", "Em andamento", "Feito"),
                    s.kanbanColumns().stream().map(KanbanColumn::name).toList());
            assertEquals(List.of(0, 1, 2, 3),
                    s.kanbanColumns().stream().map(KanbanColumn::position).toList());

            int doing = s.kanbanColumns().stream()
                    .filter(c -> c.name().equals("Em andamento")).findFirst().orElseThrow().id();
            s.updateKanbanColumn(doing, null, 0);
            assertEquals(List.of("Em andamento", "A fazer", "Extra", "Feito"),
                    s.kanbanColumns().stream().map(KanbanColumn::name).toList());
        }
    }
}
