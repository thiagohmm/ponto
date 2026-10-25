package ponto;

/** Save needs an explicit confirm (midnight / overlap). The TUI asks y/n; the API returns 409. */
public final class NeedConfirm extends Problema {
    public final String code;
    public NeedConfirm(String code, String msg) { super(msg); this.code = code; }
}
