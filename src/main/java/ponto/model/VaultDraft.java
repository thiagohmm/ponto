package ponto.model;

/** A record to insert: a company tag and a sealed blob. */
public record VaultDraft(int companyId, String blob) {
    public VaultDraft { blob = blob == null ? "" : blob; }
}
