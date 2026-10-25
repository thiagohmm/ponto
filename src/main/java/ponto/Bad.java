package ponto;

/** Rejected input: the message is echoed and the prompt repeats. */
public final class Bad extends RuntimeException {
    public Bad(String msg) { super(msg); }
}
