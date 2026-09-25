package com.allmusicconnect.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 4.x HUD 位置数据（{@code HudPosObj}：lyric/info/state/pic，方向字段 {@code pos}）
 * 翻译为 3.x 的 {@code SaveOBJ}（list/lyric/info/pic + picRotateSpeed，方向字段 {@code dir}）。
 * <p>
 * 关键点：3.x 的 {@code PosOBJ.dir} 为 null 时它的渲染代码会 {@code dir.ordinal()} 抛 NPE
 * 直接把客户端崩掉，所以每个元素都必须给出合法 dir（两代枚举名一致：TOP_LEFT … BOTTOM_RIGHT）。
 * <p>
 * 纯 Gson 实现，不依赖 Minecraft / netty，便于单独测试。
 */
final class HudJsonV3 {

    private HudJsonV3() {
    }

    /** @return 3.x 的 SaveOBJ JSON；输入不合法时返回 null */
    static String translate(String v4Json, int fallbackPicSize) {
        try {
            JsonElement root = JsonParser.parseString(v4Json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject src = root.getAsJsonObject();
            JsonObject srcPic = src.has("pic") && src.get("pic").isJsonObject() ? src.getAsJsonObject("pic") : null;
            JsonObject save = new JsonObject();
            save.add("lyric", element(src, "lyric"));
            save.add("info", element(src, "info"));
            JsonObject pic = element(src, "pic");
            // 3.1.6 的绘制把 pic.color 当成绘制尺寸传给 bridge.drawPic（内部 int a = size / 2），
            // 传白色 0xFFFFFF 会画出天文数字大小的四边形、实际看不见封面；
            // 该字段在 3.x 不用于着色，因此回填 4.x HUD 配置里的 pic.size（服务端 /music hud pic size 改的就是它），
            // 缺失时退回客户端自己的纹素尺寸
            pic.addProperty("color", srcPic != null && srcPic.has("size") && srcPic.get("size").isJsonPrimitive()
                    ? srcPic.get("size").getAsInt() : fallbackPicSize);
            save.add("pic", pic);
            // 4.x 的 state 对应 3.x 的 list 区域；缺省时给一份关闭状态的默认值（dir 不能为空）
            save.add("list", src.has("state") && src.get("state").isJsonObject()
                    ? element(src, "state") : disabledDefault());
            save.addProperty("picRotateSpeed", srcPic != null && srcPic.has("speed") && srcPic.get("speed").isJsonPrimitive()
                    ? srcPic.get("speed").getAsInt() : 10);
            return save.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 单个 HUD 元素：4.x -> 3.x（x/y/pos→dir/color/shadow/enable） */
    private static JsonObject element(JsonObject src, String name) {
        JsonObject obj = src.has(name) && src.get(name).isJsonObject() ? src.getAsJsonObject(name) : null;
        JsonObject out = new JsonObject();
        out.addProperty("x", intOf(obj, "x", 0));
        out.addProperty("y", intOf(obj, "y", 0));
        String dir = stringOf(obj, "pos");
        out.addProperty("dir", dir == null ? "TOP_LEFT" : dir);
        out.addProperty("color", intOf(obj, "color", 0xFFFFFF));
        out.addProperty("shadow", boolOf(obj, "shadow", true));
        out.addProperty("enable", boolOf(obj, "enable", true));
        return out;
    }

    /** 3.x 里没有对应元素的默认值：安全的方向 + 关闭显示 */
    private static JsonObject disabledDefault() {
        JsonObject out = new JsonObject();
        out.addProperty("x", 0);
        out.addProperty("y", 0);
        out.addProperty("dir", "TOP_LEFT");
        out.addProperty("color", 0xFFFFFF);
        out.addProperty("shadow", true);
        out.addProperty("enable", false);
        return out;
    }

    private static int intOf(JsonObject obj, String key, int fallback) {
        if (obj == null) {
            return fallback;
        }
        JsonElement value = obj.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static String stringOf(JsonObject obj, String key) {
        if (obj == null) {
            return null;
        }
        JsonElement value = obj.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static boolean boolOf(JsonObject obj, String key, boolean fallback) {
        if (obj == null) {
            return fallback;
        }
        JsonElement value = obj.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
    }
}