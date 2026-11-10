package ponto.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Soft taxonomy for punch-card justifications — UX only (filter / autocomplete).
 * Never feeds BankRules or saldo.
 */
public enum JustCategory {
    ATESTADO("atestado", "Atestado",
            List.of("atestado", "medico", "médico", "consulta", "cid", "doente", "saude", "saúde"),
            List.of("Atestado médico", "Consulta médica", "Afastamento por saúde")),
    HOME_OFFICE("home_office", "Home office",
            List.of("home office", "homeoffice", "remoto", "teletrabalho", "trabalho remoto"),
            List.of("Home office", "Trabalho remoto")),
    CLIENTE("cliente", "Cliente",
            List.of("cliente", "visita", "externo", "reunião com cliente", "reuniao com cliente"),
            List.of("Visita a cliente", "Atendimento a cliente")),
    REUNIAO("reuniao", "Reunião",
            List.of("reuniao", "reunião", "meeting", "call", "alinhamento"),
            List.of("Reunião interna", "Reunião de alinhamento")),
    PESSOAL("pessoal", "Pessoal",
            List.of("pessoal", "particular", "assunto pessoal", "banco de horas"),
            List.of("Assunto pessoal", "Saída particular")),
    DESLOCAMENTO("deslocamento", "Deslocamento",
            List.of("deslocamento", "trajeto", "transito", "trânsito", "viagem", "transporte"),
            List.of("Deslocamento", "Viagem a trabalho")),
    OUTRO("outro", "Outro",
            List.of("outro", "diversos"),
            List.of("Outro motivo"));

    private final String code;
    private final String label;
    private final List<String> synonyms;
    private final List<String> phrases;

    JustCategory(String code, String label, List<String> synonyms, List<String> phrases) {
        this.code = code;
        this.label = label;
        this.synonyms = List.copyOf(synonyms);
        this.phrases = List.copyOf(phrases);
    }

    public String code() { return code; }
    public String label() { return label; }
    public List<String> synonyms() { return synonyms; }
    public List<String> phrases() { return phrases; }

    public static Optional<JustCategory> ofCode(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String n = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.code.equals(n)).findFirst();
    }

    /** All categories as maps for /api/assist/status consumers. */
    public static List<java.util.Map<String, Object>> catalog() {
        return Arrays.stream(values()).map(c -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("code", c.code);
            m.put("label", c.label);
            m.put("synonyms", c.synonyms);
            m.put("phrases", c.phrases);
            return m;
        }).toList();
    }
}
