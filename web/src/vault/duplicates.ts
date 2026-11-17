/**
 * Duplicate detection — the two kinds that actually bite, and nothing else.
 *
 * **Same target** (one application + one user, twice): two rows for Gmail/ana means the
 * next person who opens the list cannot tell which password is current. They try the old
 * one, get locked out, and the lockout is what costs money.
 *
 * **Reused password** (one password, several targets): this is the one worth shouting
 * about. When site A leaks a dumped list, attackers try that same string on every other
 * account they know you have. A vault full of "the same password everywhere" is a vault
 * that unlocks itself the moment any single site is breached — and no amount of entropy
 * in the password fixes it.
 *
 * Matching is on trimmed, case-folded text, because "Gmail" and "gmail " are the same
 * account and `indexOf` on the raw string would miss it. The password comparison is
 * exact and case-sensitive on purpose: "Pass" and "pass" are different secrets, and
 * calling them duplicates would tell the user to change something that is not wrong.
 */

import type { VaultOpen } from "./model";

export type Duplicates = {
  /** Same application and user in two or more rows. */
  sameTarget: { key: string; items: VaultOpen[] }[];
  /** One password protecting two or more targets — the dangerous one. */
  reusedPassword: { pass: string; items: VaultOpen[] }[];
};

function targetKey(app: string, user: string): string {
  return `${app.trim().toLocaleLowerCase("en")}|${user.trim().toLocaleLowerCase("en")}`;
}

export function duplicates(items: VaultOpen[]): Duplicates {
  const targets = new Map<string, VaultOpen[]>();
  const passwords = new Map<string, VaultOpen[]>();

  for (const item of items) {
    const key = targetKey(item.plain.app, item.plain.user);
    if (item.plain.app.trim() || item.plain.user.trim()) push(targets, key, item);
    if (item.plain.pass) push(passwords, item.plain.pass, item);
  }

  const groups = (map: Map<string, VaultOpen[]>): { key: string; items: VaultOpen[] }[] =>
    [...map.entries()]
      .filter(([, rows]) => rows.length > 1)
      .map(([key, rows]) => ({ key, items: rows }))
      .sort((a, b) => b.items.length - a.items.length || a.key.localeCompare(b.key, "pt-BR"));

  return {
    sameTarget: groups(targets),
    reusedPassword: groups(passwords).map((g) => ({ pass: g.key, items: g.items })),
  };
}

function push<K>(map: Map<K, VaultOpen[]>, key: K, item: VaultOpen): void {
  const rows = map.get(key);
  if (rows) rows.push(item);
  else map.set(key, [item]);
}

/** The sentence the list shows: nothing found is the common and correct answer. */
export function duplicateCount(d: Duplicates): number {
  return d.sameTarget.length + d.reusedPassword.length;
}

export function duplicateSummary(d: Duplicates): string {
  if (!duplicateCount(d)) return "";
  const parts: string[] = [];
  if (d.reusedPassword.length) parts.push(`${d.reusedPassword.length} password${d.reusedPassword.length > 1 ? "s" : ""} reused across targets`);
  if (d.sameTarget.length) parts.push(`${d.sameTarget.length} target${d.sameTarget.length > 1 ? "s" : ""} listed twice`);
  return parts.join(" · ");
}
