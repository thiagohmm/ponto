package ponto;

/** User-facing failure: shown as a red line, the menu keeps running. */
class Problema extends RuntimeException {
    Problema(String msg) { super(msg); }
}

/** Stdin closed (Ctrl-D): exit quietly. */
final class Eof extends RuntimeException { }

/** Rejected input: the message is echoed and the prompt repeats. */
final class Bad extends RuntimeException {
    Bad(String msg) { super(msg); }
}

/** Save needs an explicit confirm (midnight / overlap). The TUI asks y/n; the API returns 409. */
final class NeedConfirm extends Problema {
    final String code;
    NeedConfirm(String code, String msg) { super(msg); this.code = code; }
}
