package ponto.view;

import io.javalin.json.JsonMapper;
import java.lang.reflect.Type;

/**
 * Javalin JSON via Jackson 3 (`tools.jackson`): core + databind, no XML stack.
 */
public final class Json3 implements JsonMapper {

    private final tools.jackson.databind.json.JsonMapper om = new tools.jackson.databind.json.JsonMapper();

    @Override
    public String toJsonString(Object obj, Type type) {
        return om.writeValueAsString(obj);
    }

    @Override
    public <T> T fromJsonString(String json, Type targetType) {
        try { return om.readValue(json, om.constructType(targetType)); }
        catch (tools.jackson.core.JacksonException e) { throw new ponto.Bad("JSON inválido ou campos incompatíveis."); }
    }
}
