package com.allmusicconnect.net;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/**
 * Minecraft 26.x 世代兼容层
 * <p>
 * 该世代使用新 API：GameProfile.name()、sendOverlayMessage(Component)、
 * ComponentSerialization.CODEC 解析组件 JSON。
 */
public final class Compat {

    private Compat() {
    }

    /**
     * 获取当前玩家名
     */
    public static String getPlayerName() {
        return Minecraft.getInstance().getGameProfile().name();
    }

    /**
     * 在物品栏上方显示一条消息
     */
    public static void showOverlayMessage(Component component) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendOverlayMessage(component);
        }
    }

    /**
     * 在聊天栏显示一条消息
     */
    public static void showSystemMessage(Component component) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(component);
        }
    }

    /**
     * 解析服务端发来的组件 JSON（adventure Gson 序列化，与 Minecraft 组件格式兼容），
     * 完整还原颜色、可点击文本等样式；解析失败时回退为纯文本组件。
     */
    public static Component parseComponent(String json, String fallbackText) {
        if (json != null && !json.isEmpty()) {
            try {
                return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
            } catch (Exception ignored) {
                // 格式不兼容时回退纯文本
            }
        }
        return Component.literal(fallbackText == null ? "" : fallbackText);
    }
}
