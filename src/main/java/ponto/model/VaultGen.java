package ponto.model;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import ponto.Bad;

/**
 * The same generator the browser uses, in Java — for the terminal.
 *
 * Mirrors `web/src/vault/generator.ts` on purpose: the same four sets, the same
 * "one character per chosen class first, then shuffle", the same spelled-out rejection. A
 * password made here and one made there should be indistinguishable, because both do the
 * same two things that matter:
 *
 * - **No modulo bias.** `randomInt` draws 31 bits and rejects anything at or above the
 *   largest multiple of the alphabet size. `SecureRandom.nextInt(bound)` is unbiased too,
 *   but the rejection is written out so the property is visible in the source instead of
 *   delegated to a library the reader has to trust.
 * - **Every chosen class appears.** "Pick a random character and hope" produces
 *   20-character passwords with no digit often enough to hurt.
 *
 * `SecureRandom` is a CSPRNG seeded by the OS. `java.util.Random` is not: a PRNG with a
 * known seed would make every password in the cofre predictable to whoever knew the seed,
 * which is the mistake this file exists to avoid.
 */
public final class VaultGen {

    public static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    public static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    public static final String DIGITS = "0123456789";
    public static final String SYMBOLS = "!@#$%^&*()-_=+[]{};,.?~";
    /** Characters that make a dictated or handwritten password fail: i l 1 L o O 0. */
    public static final String SIMILAR = "il1LoO0";

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;
    public static final int DEFAULT_LENGTH = 20;

    private static final SecureRandom RNG = new SecureRandom();

    /** One character class in play. */
    public record Set(String name, String chars) { }

    /** The pools in the order they are offered; empty means "nothing was chosen". */
    public static List<Set> pools(boolean upper, boolean lower, boolean digits, boolean symbols, boolean avoidSimilar) {
        List<Set> out = new ArrayList<>();
        if (upper) out.add(new Set("A-Z", cut(UPPER, avoidSimilar)));
        if (lower) out.add(new Set("a-z", cut(LOWER, avoidSimilar)));
        if (digits) out.add(new Set("0-9", cut(DIGITS, avoidSimilar)));
        if (symbols) out.add(new Set("symbols", cut(SYMBOLS, avoidSimilar)));
        return out;
    }

    private static String cut(String alphabet, boolean avoidSimilar) {
        if (!avoidSimilar) return alphabet;
        StringBuilder b = new StringBuilder();
        for (char c : alphabet.toCharArray()) if (!SIMILAR.contains(String.valueOf(c))) b.append(c);
        return b.toString();
    }

    /**
     * Uniform over [0, max): reject and redraw above the largest multiple, so the last
     * letters of the alphabet are not slightly likelier than the first.
     */
    public static int randomInt(int max) {
        if (max < 1) throw new Bad("range inválido");
        long ceiling = 0x8000_0000L - (0x8000_0000L % max);
        long v;
        do {
            v = RNG.nextInt() & 0x7fff_ffffL;
        } while (v >= ceiling);
        return (int) (v % max);
    }

    private static String pick(String alphabet) { return alphabet.charAt(randomInt(alphabet.length())) + ""; }

    /** Generate. An empty alphabet is an error, never a silently empty password. */
    public static String generate(int length, boolean upper, boolean lower, boolean digits, boolean symbols, boolean avoidSimilar) {
        if (length < MIN_LENGTH || length > MAX_LENGTH)
            throw new Bad("longueur entre " + MIN_LENGTH + " and " + MAX_LENGTH);
        List<Set> ps = pools(upper, lower, digits, symbols, avoidSimilar);
        if (ps.isEmpty()) throw new Bad("escolha at least one character set");

        List<String> chars = new ArrayList<>();
        for (Set s : ps) chars.add(pick(s.chars()));       // one per chosen class, first
        String all = ps.stream().map(Set::chars).collect(java.util.stream.Collectors.joining(""));
        while (chars.size() < length) chars.add(pick(all));

        // then shuffle, so the forced characters do not cluster at the front
        for (int i = chars.size() - 1; i > 0; i--) {
            int j = randomInt(i + 1);
            String t = chars.get(i); chars.set(i, chars.get(j)); chars.set(j, t);
        }
        return String.join("", chars);
    }

    /** The default the terminal and the page both start from. */
    public static String generate() { return generate(DEFAULT_LENGTH, true, true, true, true, false); }

    /** Bits this alphabet carries per password — the hint printed next to the field. */
    public static double bitsOf(int length, boolean upper, boolean lower, boolean digits, boolean symbols, boolean avoidSimilar) {
        int size = pools(upper, lower, digits, symbols, avoidSimilar).stream()
                .mapToInt(s -> s.chars().length()).sum();
        return size == 0 ? 0 : Math.log(size) / Math.log(2) * length;
    }

    /** "A-Z a-z 0-9 symbols (no il1LoO0)" — the rules, as one line. */
    public static String sets(boolean upper, boolean lower, boolean digits, boolean symbols, boolean avoidSimilar) {
        List<String> l = new ArrayList<>();
        for (Set s : pools(upper, lower, digits, symbols, avoidSimilar)) l.add(s.name());
        return String.join(" ", l) + (avoidSimilar ? " · no il1LoO0" : "");
    }
}
