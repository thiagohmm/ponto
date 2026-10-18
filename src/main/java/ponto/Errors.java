package ponto;

/** User-facing failure: shown as a red line, the menu keeps running. */
final class Problema extends RuntimeException {
    Problema(String msg) { super(msg); }
}

/** Stdin closed (Ctrl-D): exit quietly. */
final class Eof extends RuntimeException { }

/** Rejected input: the message is echoed and the prompt repeats. */
final class Bad extends RuntimeException {
    Bad(String msg) { super(msg); }
}
