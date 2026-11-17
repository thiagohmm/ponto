package ponto.app;

import ponto.infra.VaultStore;
import ponto.model.VaultDraft;
import ponto.model.VaultItem;
import ponto.model.VaultMeta;
import ponto.model.VaultRewrap;

import java.util.List;

/**
 * Application API for the password vault.
 *
 * The vault is not company-scoped: one master password opens every company's
 * credentials, and each record carries the company tag it belongs to. This facade
 * therefore moves sealed blobs around and never sees plaintext — the cryptography
 * lives in the client ({@code web/src/crypto.ts}) and in the terminal menu.
 */
public final class VaultApp {
    private final VaultStore store;

    VaultApp(VaultStore store) { this.store = store; }

    VaultStore store() { return store; }

    /** The public parameters: salt, iterations and the verifier blob. */
    public VaultMeta meta() { return store.meta(); }

    /** First time only: seal the vault around a master password. */
    public VaultMeta create(String kdf, String salt, int iterations, String check) {
        return store.create(kdf, salt, iterations, check);
    }

    /**
     * Refuse record writes before the vault exists.
     *
     * A client that skipped the handshake would store blobs against no verifier, and
     * the unlock test would have nothing to check against — the user would appear to
     * have saved credentials that could never be opened again. The store stays free of
     * this policy on purpose: it is the app that knows a vault must be sealed first.
     */
    private void mustSeal() {
        if (!store.meta().sealed()) throw ponto.VaultSealed.neverCreated();
    }

    /** Every credential, or only one company's when {@code companyFilter} is set. */
    public List<VaultItem> items(Integer companyFilter) { return store.list(companyFilter); }

    public VaultItem byId(int id) { return store.byId(id); }

    public VaultItem create(int companyId, String blob) {
        mustSeal();
        return store.create(companyId, blob);
    }

    public VaultItem update(int id, String blob, int revision) { return store.update(id, blob, revision); }

    public void delete(int id) { store.delete(id); }

    /** New master password: every blob is re-sealed by the client, then stored at once. */
    public VaultMeta rekey(String kdf, String salt, int iterations, String check, List<VaultRewrap> items) {
        mustSeal();
        return store.rekey(kdf, salt, iterations, check, items);
    }

    /** Insert many records in one transaction (an imported file). */
    public List<VaultItem> createAll(List<VaultDraft> drafts) {
        if (!drafts.isEmpty()) mustSeal();
        return store.createAll(drafts);
    }

    public int count() { return store.count(); }
    public int countCompany(int companyId) { return store.countCompany(companyId); }

    /** Forget one company's credentials (the tag is the only thing a wipe can use). */
    public int deleteCompany(int companyId) {
        int n = 0;
        for (int id : store.idsOf(companyId)) { store.delete(id); n++; }
        return n;
    }
}
