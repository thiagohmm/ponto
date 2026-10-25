package ponto;

import io.javalin.json.JsonMapper;
import java.lang.reflect.Type;

/**
 * Javalin JSON via Jackson 3 (`tools.jackson`), already on the classpath for
 * jollyday. Jackson 2 (`com.fasterxml`) must stay off the path: it hides
 * annotations jollyday needs and the holiday calendar goes silent.
 */
final class Json3 implements JsonMapper {

    private final tools.jackson.databind.json.JsonMapper om = new tools.jackson.databind.json.JsonMapper();

    @Override
    public String toJsonString(Object obj, Type type) {
        return om.writeValueAsString(obj);
    }

    @Override
    public <T> T fromJsonString(String json, Type targetType) {
        return om.readValue(json, om.constructType(targetType));
    }
}
