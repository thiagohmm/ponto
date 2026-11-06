package ponto;

/** An optimistic write would replace a newer revision. */
public final class Conflict extends Problema {
    public Conflict(String message) { super(message); }
}
