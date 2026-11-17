/**
 * The strength meter — why it is not zxcvbn, and what it can honestly say.
 *
 * The estimate is Shannon entropy over the character classes present, minus penalties for
 * the shapes that make a password much weaker than its entropy claims: repeated runs,
 * keyboard walks, and short common words. `Math.log2(poolSize) * length` is an *upper*
 * bound on a truly random password of that length, so the meter never overstates — it can
 * only be reduced by a penalty.
 *
 * What it deliberately does not do: claim to know the user's dictionary, their name, their
 * birthday, or the site's password database. Those need a corpus this app does not have,
 * and a number that pretends to have seen them is worse than a number that admits it hasn't.
 * A password like "Tr0y-P@ss-Word-9" scores high here and is still a bad idea, and the
 * UI says so: the strongest defence is not entropy, it is never reusing a password.
 */

export type Strength = {
  score: 0 | 1 | 2 | 3 | 4;
  bits: number;
  label: string;
  hint: string;
};

/** Lowercase, uppercase, digits, symbols, and anything outside ASCII. */
const POOLS = [
  { test: /[a-z]/, size: 26 },
  { test: /[A-Z]/, size: 26 },
  { test: /[0-9]/, size: 10 },
  { test: /[^A-Za-z0-9\s]/, size: 33 },
  { test: /\s/, size: 1 },
];
const NON_ASCII = /[^\x00-\x7f]/;

/** Keystroke walks, the shape a keyboard produces when someone slides their finger. */
const WALKS = ["qwerty", "qwertz", "azerty", "asdfgh", "zxcvbn", "uiop", "jkl;", "1234", "2345", "3456", "4567", "5678", "6789", "0123"];

/** Small and deliberate: these are the words people actually type into password fields. */
const WORDS = [
  "password", "passwort", "senha", "palavra", "welcome", "hello", "login", "admin",
  "qwerty", "letmein", "monkey", "dragon", "master", "abc123", "123456", "iloveyou",
  "ponto", "football", "sunshine", "princess", "diamond", "shadow", "superman",
];

export function poolSize(pass: string): number {
  let size = NON_ASCII.test(pass) ? 128 : 0;
  for (const pool of POOLS) if (pool.test.test(pass)) size += pool.size;
  return size;
}

/** Penalty in bits. 0 means "nothing about this shape makes it weaker than it claims". */
export function penalties(pass: string): { bits: number; why: string } {
  const lower = pass.toLowerCase();
  let bits = 0;
  let why = "";
  const take = (b: number, text: string) => {
    if (b > bits) { bits = b; why = text; }
  };

  // "aaaaaa" or "ababab": repeats carry almost no information.
  const runs = /(.)\1{2,}/.test(pass);
  if (runs) take(Math.min(16, pass.length * 1.5), "repeated characters");
  const pairs = /(?:([a-z0-9])([a-z0-9]))\1\2/.test(lower);
  if (pairs) take(12, "repeated pairs");

  // A keyboard walk is one finger, not a choice.
  for (const walk of WALKS) if (lower.includes(walk)) take(24, "a keyboard walk");

  // A single common word with leetspeak is the first thing a guessing tool tries.
  const leet = lower.replace(/[@]/g, "a").replace(/[0]/g, "o").replace(/[1|]/g, "i").replace(/[3]/g, "e").replace(/[$]/g, "s").replace(/[7]/g, "t");
  for (const word of WORDS) if (leet.includes(word)) take(30, `the word "${word}"`);

  // All digits, or one character repeated: no alphabet at all.
  if (/^[0-9]+$/.test(pass)) take(28, "only digits");
  if (/^(.)\1*$/.test(pass)) take(40, "one character");
  if (pass.length <= 4) take(40, "too short to matter");

  return { bits, why };
}

export function strength(pass: string): Strength {
  if (!pass) return { score: 0, bits: 0, label: "empty", hint: "Nada written yet." };
  const raw = Math.log2(poolSize(pass)) * pass.length;
  const penalty = penalties(pass);
  const bits = Math.max(0, Math.round(raw - penalty.bits));

  // Cut at 36/60/80/120: below 36 a determined attacker with a leaked list gets it
  // in minutes; above ~120 more length buys nothing a breach could exploit.
  const score: Strength["score"] =
    bits < 36 ? 0 : bits < 60 ? 1 : bits < 80 ? 2 : bits < 120 ? 3 : 4;
  const labels = ["débil", "débil", "justo", "sólido", "fort"];
  const hints = [
    "Longuíter, or mix upper, digits and symbols.",
    "Funciona, but 8 more characters would cost you nothing and cost them a lot.",
    "Good. A passphrase of four random words is stronger and easier to keep.",
    "Strong. Keep it unique to this application — that is the real protection.",
    "Excellent. Nothing brute-forces this inside a human lifetime.",
  ];
  return {
    score,
    bits,
    label: labels[score],
    hint: penalty.why ? `${hints[score]} (detected: ${penalty.why})` : hints[score],
  };
}

/** The four bars the meter draws. One per score step, so 2/4 never looks like 4/4. */
export const BARS = 4;
