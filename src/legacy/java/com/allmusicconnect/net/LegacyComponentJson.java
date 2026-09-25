package com.allmusicconnect.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * 组件 JSON 降级转换（仅 1.21 ~ 1.21.5 的 legacy 兼容层使用）。
 * <p>
 * 服务端打包的是 adventure 4.21+，它序列化出的点击/悬停样式是<b>新格式</b>：
 * <pre>
 * {"click_event":{"action":"suggest_command","command":"/music "},"text":"[点我选择]"}
 * {"hover_event":{"action":"show_text","value":"提示文本"},"text":"..."}
 * </pre>
 * 而 Minecraft 1.21 ~ 1.21.4 只认识<b>旧格式</b>（{@code clickEvent}/{@code hoverEvent}，
 * 命令字段叫 {@code value}、悬停内容叫 {@code contents}）。旧版解析器会把未知的
 * {@code click_event} 字段直接忽略，因此聊天文本照常显示、点击却失效
 * （表现为 26.x 能点、1.21.4 不能点）。
 * <p>
 * 这里在解析前把新格式改写成旧格式；旧版无法表达的点击动作（如 show_dialog / custom）
 * 会被丢弃，只影响该处点击，不影响文本与颜色。
 */
public final class LegacyComponentJson {

    private LegacyComponentJson() {
    }

    /**
     * 把组件 JSON 里的新格式 click_event / hover_event 转换为旧格式
     *
     * @return 转换后的 JSON；输入不是合法 JSON 时原样返回
     */
    public static String downgrade(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            convert(root);
            return root.toString();
        } catch (Exception e) {
            // 交给上层按解析失败处理（回退纯文本）
            return json;
        }
    }

    private static void convert(JsonElement element) {
        if (element instanceof JsonArray array) {
            for (JsonElement item : array) {
                convert(item);
            }
            return;
        }
        if (!(element instanceof JsonObject obj)) {
            return;
        }

        JsonElement click = obj.remove("click_event");
        if (click instanceof JsonObject clickObj) {
            JsonObject converted = convertClick(clickObj);
            if (converted != null) {
                obj.add("clickEvent", converted);
            }
        }

        JsonElement hover = obj.remove("hover_event");
        if (hover instanceof JsonObject hoverObj) {
            JsonObject converted = convertHover(hoverObj);
            if (converted != null) {
                obj.add("hoverEvent", converted);
            }
        }

        // 继续递归所有成员：extra 数组、悬停内容里的子组件、translatable 参数等
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            convert(entry.getValue());
        }
    }

    /** 新格式点击 -> 旧格式 {"action":..., "value":"..."}；旧版无法表达时返回 null（丢弃该点击） */
    private static JsonObject convertClick(JsonObject src) {
        String action = asString(src.get("action"));
        if (action == null) {
            return null;
        }
        String value = switch (action) {
            case "run_command", "suggest_command" -> asString(src.get("command"));
            case "open_url" -> asString(src.get("url"));
            case "copy_to_clipboard" -> asString(src.get("value"));
            case "change_page" -> asString(src.get("page"));
            // show_dialog / custom 等 1.21.5+ 才有的动作，旧版无法表达
            default -> null;
        };
        if (value == null) {
            return null;
        }
        JsonObject out = new JsonObject();
        out.addProperty("action", action);
        out.addProperty("value", value);
        return out;
    }

    /** 新格式悬停 -> 旧格式 {"action":..., "contents":...}；无法表达时返回 null（丢弃该悬停） */
    private static JsonObject convertHover(JsonObject src) {
        String action = asString(src.get("action"));
        JsonElement value = src.get("value");
        if (action == null || value == null) {
            return null;
        }
        switch (action) {
            case "show_text", "show_item", "show_entity" -> {
                JsonObject out = new JsonObject();
                out.addProperty("action", action);
                out.add("contents", value);
                return out;
            }
            default -> {
                // show_dialog 等新动作旧版不支持
                return null;
            }
        }
    }

    private static String asString(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }
}