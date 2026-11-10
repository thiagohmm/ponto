package ponto.model;

import ponto.Bad;

import java.util.Locale;

/** Why a card is stuck in Em andamento. The code is the stored value. */
public enum KanbanBlockKind {
    DEPENDENCIA("dependencia", "Dependência"),
    CLIENTE("cliente", "Aguardando cliente"),
    TECNICO("tecnico", "Impedimento técnico"),
    INFORMACAO("informacao", "Falta de informação"),
    OUTRO("outro", "Outro");

    private final String code;
    private final String label;

    KanbanBlockKind(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() { return code; }
    public String label() { return label; }

    public static KanbanBlockKind parse(String raw) {
        String n = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (KanbanBlockKind k : values()) {
            if (k.code.equals(n) || k.label.toLowerCase(Locale.ROOT).equals(n)) return k;
        }
        throw new Bad("tipo de bloqueio inválido");
    }

    public static String labelOf(String code) {
        for (KanbanBlockKind k : values()) if (k.code.equals(code)) return k.label;
        return code == null ? "" : code;
    }
}
