package cn.dreamingfish.updater.protocol;

import java.util.List;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/** Data rendered by the player's built-in showcase components. */
public record PlayerBlock(
        String id, String kind, String title, String lead, String navLabel, String tone,
        @JsonDeserialize(using = ColumnsDeserializer.class) Integer columns,
        String markdown, String imageFile, String imageUrl,
        List<PlayerBlockItem> items, List<PlayerLinkTarget> actions, String eyebrow
) {
    public PlayerBlock {
        if (items != null) items = List.copyOf(items);
        if (actions != null) actions = List.copyOf(actions);
    }

    public PlayerBlock(String id, String kind, String title, String lead, String navLabel, String tone,
                       Integer columns, String markdown, String imageFile, String imageUrl,
                       List<PlayerBlockItem> items, List<PlayerLinkTarget> actions) {
        this(id, kind, title, lead, navLabel, tone, columns, markdown, imageFile, imageUrl,
                items, actions, null);
    }

    /** Jackson's default integer coercion silently truncates fractional AI-generated values. */
    public static final class ColumnsDeserializer extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser input, DeserializationContext context) throws IOException {
            if (input.currentToken().isNumeric()) {
                try { return input.getDecimalValue().intValueExact(); }
                catch (ArithmeticException ignored) { }
            }
            return context.reportInputMismatch(Integer.class, "columns 必须是 1–4 之间的整数");
        }
    }
}

