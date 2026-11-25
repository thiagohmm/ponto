package ponto.http;

import io.javalin.json.JsonMapper;
import java.lang.reflect.Type;

/** Javalin JSON via Jackson 3. */
final class JsonMap implements JsonMapper {

    private final tools.jackson.databind.json.JsonMapper om = new tools.jackson.databind.json.JsonMapper();

    @Override
    public String toJsonString(Object obj, Type type) {
        return om.writeValueAsString(obj);
    }

    @Override
    public <T> T fromJsonString(String json, Type targetType) {
        try {
            return om.readValue(json, om.constructType(targetType));
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("JSON inválido.", e);
        }
    }
}
