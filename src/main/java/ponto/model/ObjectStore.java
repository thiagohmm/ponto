package ponto.model;

/** Minimal object-store boundary, also used by failure-injection tests. */
interface ObjectStore {
    boolean up();
    void ensureBucket();
    void put(String key, byte[] body, String contentType);
    byte[] get(String key);
    void delete(String key);
}
