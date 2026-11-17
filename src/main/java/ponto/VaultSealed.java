package ponto;

/**
 * The vault is locked — the only case this server can actually detect.
 *
 * A wrong master password is <em>not</em> an error here: the client decrypts locally,
 * so the server never learns anything about the key and cannot tell a bad guess from
 * a good one. What it can tell is that the vault was never sealed: with no salt and
 * no verifier in {@code vault_meta}, there is nothing to derive a key from and nothing
 * to check it against, so records saved now could never be opened again.
 *
 * HTTP answers 423 Locked with a {@code need} that names the next step, which is the
 * shape the SPA already speaks (it branches on {@code need} for midnight/overlap).
 */
public final class VaultSealed extends Problema {

    public final String need;

    public VaultSealed(String need, String message) { super(message); this.need = need; }

    /** The vault has never been created: seal it with a master password first. */
    public static VaultSealed neverCreated() {
        return new VaultSealed("create", "Creae the cofre first: sel the master password.");
    }
}
