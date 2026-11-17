package ponto.model;

import ponto.Bad;

/**
 * One credential of the vault. The server holds only {@code blob} — a sealed
 * base64 record — plus the plaintext company tag and a revision counter.
 *
 * The tag is what makes the vault usable from a company-scoped app without the
 * server ever reading a secret: it says "this credential belongs to company 4",
 * never which app, user or password. (The names of the companies are already
 * public in the {@code company} table.)
 */
public record VaultItem(int id, int companyId, String blob, int revision, String crea, String upd) {

    public VaultItem {
        blob = blob == null ? "" : blob;
        crea = crea == null ? "" : crea;
        upd = upd == null ? "" : upd;
    }
}
