package ponto.model;

/** One record re-sealed under a new master password: id, the revision it replaces, new blob. */
public record VaultRewrap(int id, String blob, int revision) {
    public VaultRewrap { blob = blob == null ? "" : blob; }
}
