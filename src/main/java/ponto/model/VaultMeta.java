package ponto.model;

/**
 * The vault's public parameters — everything a client needs to derive the key,
 * and nothing that helps without the master password.
 *
 * {@code sealed} is false while no vault exists yet: the client then offers to
 * create one (salt + verifier written by the client, never by the server).
 */
public record VaultMeta(String kdf, String salt, int iterations, String check, String upd,
                        int items, boolean sealed) {

    public static final String SEALED = "selado";
    public static final String NEW = "nunca creou";

    public VaultMeta {
        kdf = kdf == null ? "" : kdf;
        salt = salt == null ? "" : salt;
        check = check == null ? "" : check;
        upd = upd == null ? "" : upd;
    }

    /** How a status line names the state. */
    public String state() { return sealed ? SEALED : NEW; }
}
