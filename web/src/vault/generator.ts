/**
 * Password generation — CSPRNG only, and never `Math.random()`.
 *
 * Two properties the code is written for, and the tests check:
 *
 * - **No modulo bias.** `randomInt` draws 32 bits and rejects anything at or above the
 *   largest multiple of the alphabet size. `Math.random() * alphabet.length | 0` is biased
 *   towards the first characters and, worse, `Math.random` is not a CSPRNG: a seeded PRNG
 *   would make the whole vault predictable to anyone who knows the seed.
 * - **Every chosen class appears.** The first `n` characters are forced to be one per
 *   selected class, then the rest is filled and the whole string shuffled. "Pick a random
 *   character and hope" produces 20-character passwords with no digit often enough to hurt.
 *
 * `avoidSimilar` drops il1LoO0 from the alphabet: those are the characters that make a
 * handwritten or dictated password fail, and dropping them costs ~0.2 bits per position.
 */

const UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const LOWER = "abcdefghijklmnopqrstuvwxyz";
const DIGITS = "0123456789";
const SYMBOLS = "!@#$%^&*()-_=+[]{};,.?~";
const SIMILAR = "il1LoO0";

export type GenOptions = {
  length: number;
  upper: boolean;
  lower: boolean;
  digits: boolean;
  symbols: boolean;
  avoidSimilar?: boolean;
};

export const DEFAULTS: GenOptions = { length: 20, upper: true, lower: true, digits: true, symbols: true, avoidSimilar: false };

export const MIN_LENGTH = 8;
export const MAX_LENGTH = 128;

export class GeneratorError extends Error {}

/** Uniform over [0, max): reject and redraw above the largest multiple. Never a remainder. */
export function randomInt(max: number): number {
  if (max < 1 || max > 0x100000000) throw new GeneratorError("range inválido");
  const ceiling = 0x100000000 - (0x100000000 % max);
  let value = 0;
  do {
    value = crypto.getRandomValues(new Uint32Array(1))[0];
  } while (value >= ceiling);
  return value % max;
}

function pick(alphabet: string): string {
  return alphabet[randomInt(alphabet.length)];
}

function shuffle<T>(items: T[]): T[] {
  const out = items.slice();
  for (let i = out.length - 1; i > 0; i--) {
    const j = randomInt(i + 1);
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

export function alphabetFor(opts: GenOptions): string[] {
  const pools: string[] = [];
  if (opts.upper) pools.push(opts.avoidSimilar ? filter(UPPER) : UPPER);
  if (opts.lower) pools.push(opts.avoidSimilar ? filter(LOWER) : LOWER);
  if (opts.digits) pools.push(opts.avoidSimilar ? filter(DIGITS) : DIGITS);
  if (opts.symbols) pools.push(opts.avoidSimilar ? filter(SYMBOLS) : SYMBOLS);
  return pools;
}

function filter(alphabet: string): string {
  return [...alphabet].filter((c) => !SIMILAR.includes(c)).join("");
}

/** Generate. An empty alphabet is an error, not a silently empty password. */
export function generate(opts: Partial<GenOptions> = {}): string {
  const o: GenOptions = { ...DEFAULTS, ...opts };
  const length = Math.trunc(o.length);
  if (!Number.isFinite(o.length) || length < MIN_LENGTH || length > MAX_LENGTH) {
    throw new GeneratorError(`longueur entre ${MIN_LENGTH} and ${MAX_LENGTH}`);
  }
  const pools = alphabetFor(o);
  if (pools.length === 0) throw new GeneratorError("escolha at least one character set");

  // One character per chosen class, first — then the rest, then shuffled so the forced
  // characters do not cluster at the front where a person would notice the pattern.
  const chars: string[] = pools.map(pick);
  const all = pools.join("");
  while (chars.length < length) chars.push(pick(all));
  return shuffle(chars).join("");
}

/** A hint for the field: how much entropy this alphabet carries per character. */
export function bitsOf(opts: GenOptions): number {
  const size = alphabetFor(opts).reduce((n, p) => n + p.length, 0);
  return size === 0 ? 0 : Math.floor(Math.log2(size) * Math.trunc(opts.length || DEFAULTS.length));
}
