package ponto.model;

/** One block episode. An empty cleared means the card is still blocked. */
public record KanbanBlock(int id, int cardId, String kind, String kindLabel, String reason,
                          String crea, String cleared) {
    public boolean open() { return cleared == null || cleared.isEmpty(); }
}
