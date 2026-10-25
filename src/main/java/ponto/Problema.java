package ponto;

/** User-facing failure: shown as a red line, the menu keeps running. */
public class Problema extends RuntimeException {
    public Problema(String msg) { super(msg); }
}
