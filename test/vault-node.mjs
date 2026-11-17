// Node mirror of ponto's vault envelope, used by the Java tests as the "browser".
//
// WebCrypto derives PBKDF2-SHA256 over the UTF-8 *bytes* of the master password and
// seals with AES-256-GCM, writing base64(iv12 ‖ ciphertext ‖ tag16). Node's
// crypto.pbkdf2Sync and createCipheriv produce the same bytes, so this file is the
// third implementation of the design: if the two ever drift, the vault is already
// half-broken and no user-level test would show it.
//
//   node test/vault-node.mjs key    <master> <salt-b64> <iterations>      -> key-b64
//   node test/vault-node.mjs enc    <key-b64> <aad> <plain>               -> blob-b64
//   node test/vault-node.mjs dec    <key-b64> <aad> <blob-b64>            -> plain
//   node test/vault-node.mjs filewr <key-b64> <salt-b64> <iters> <rows>  -> backup file
//   node test/vault-node.mjs filerd <passphrase> <file>                    -> rows (plaintext)
//
// The last two are the backup file, in the shape `web/src/vault/file.ts` writes: one
// outer seal over the whole list, so a trimmed or edited file refuses to open rather
// than importing fewer records quietly. `count` is the outer AAD, which is why an
// edited count breaks the tag instead of being accepted.
import crypto from 'node:crypto';

const FORMAT = 'ponto-cofre/1';
const KDF = 'PBKDF2-SHA256';

const un = (s) => Buffer.from(s, 'base64');
const aadExport = (n) => `ponto:vault:export:${n}`;
const aadItem = (id) => `ponto:vault:item:${id}`;

const enc = (keyB64, plain, aad) => {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', un(keyB64), iv);
  c.setAAD(Buffer.from(aad, 'utf8'));
  const body = Buffer.concat([c.update(Buffer.from(plain, 'utf8')), c.final(), c.getAuthTag()]);
  return Buffer.concat([iv, body]).toString('base64');
};

const dec = (keyB64, blob, aad) => {
  const all = un(blob);
  if (all.length < 29) throw new Error('cofre: record truncado');
  const d = crypto.createDecipheriv('aes-256-gcm', un(keyB64), all.subarray(0, 12));
  d.setAAD(Buffer.from(aad, 'utf8'));
  d.setAuthTag(all.subarray(all.length - 16));
  return Buffer.concat([d.update(all.subarray(12, all.length - 16)), d.final()]).toString('utf8');
};

const argv = process.argv.slice(2);
const [op] = argv;

if (op === 'key') {
  const [, master, salt, iters] = argv;
  console.log(crypto.pbkdf2Sync(Buffer.from(master, 'utf8'), un(salt), Number(iters), 32, 'sha256').toString('base64'));
} else if (op === 'enc') {
  const [, key, aad, plain] = argv;
  console.log(enc(key, plain, aad));
} else if (op === 'dec') {
  const [, key, aad, blob] = argv;
  console.log(dec(key, blob, aad));
} else if (op === 'filewr') {
  // rows: [{companyId,app,user,pass,desc,revision}] — sealed here with the browser's rules
  const [, key, salt, iters, rowsJson] = argv;
  const rows = JSON.parse(rowsJson);
  const sealed = rows.map((r) => {
    const plain = JSON.stringify({ app: r.app ?? '', user: r.user ?? '', pass: r.pass, desc: r.desc ?? '', company: r.company ?? r.companyId });
    // `tag` / `company` may disagree on purpose: the mirror is the browser, and a
    // browser with a bug is exactly the file that must be refused rather than imported
    return { companyId: r.companyId, revision: r.revision ?? 1, blob: enc(key, plain, aadItem(r.companyId)) };
  });
  const list = JSON.stringify(sealed);
  console.log(JSON.stringify({
    format: FORMAT,
    app: 'ponto',
    exportedAt: new Date().toISOString(),
    kdf: KDF,
    salt,
    iterations: Number(iters),
    count: sealed.length,
    sealed: enc(key, list, aadExport(sealed.length)),
  }));
} else if (op === 'filerd') {
  const [, passphrase, text] = argv;
  const f = JSON.parse(text);
  if (f.format !== FORMAT) throw new Error(`cofre: backup format "${f.format}"`);
  if (f.kdf !== KDF) throw new Error(`cofre: backup kdf "${f.kdf}"`);
  if (typeof f.salt !== 'string' || f.salt === '') throw new Error('cofre: backup has no salt');
  if (typeof f.iterations !== 'number') throw new Error('cofre: backup has no iterations');
  if (typeof f.count !== 'number') throw new Error('cofre: backup has no count');
  const key = crypto.pbkdf2Sync(Buffer.from(passphrase, 'utf8'), un(f.salt), f.iterations, 32, 'sha256').toString('base64');
  const rows = JSON.parse(dec(key, f.sealed, aadExport(f.count)));
  if (!Array.isArray(rows)) throw new Error('cofre: the backup list is not a list');
  if (rows.length !== f.count) throw new Error(`cofre: count ${f.count} holds ${rows.length}`);
  const out = rows.map((r) => {
    const p = JSON.parse(dec(key, r.blob, aadItem(r.companyId)));
    if (p.company !== r.companyId) throw new Error(`cofre: record company ${p.company} is not its tag ${r.companyId}`);
    return { companyId: r.companyId, revision: r.revision ?? 1, app: p.app, user: p.user, pass: p.pass, desc: p.desc };
  });
  console.log(JSON.stringify(out));
} else {
  process.exit(2);
}
