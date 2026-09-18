package com.allmusicconnect.net;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Minecraft 1.21.x 世代兼容层
 * <p>
 * 该世代使用旧 API：GameProfile.getName()、displayClientMessage(Component, boolean)、
 * Component.Serializer.fromJson。
 */
public final class Compat {

    private Compat() {
    }

    /**
     * 获取当前玩家名
     */
    public static String getPlayerName() {
        return Minecraft.getInstance().getGameProfile().getName();
    }

    /**
     * 在物品栏上方显示一条消息
     */
    public static void showOverlayMessage(Component component) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(component, true);
        }
    }

    /**
     * 在聊天栏显示一条消息
     */
    public static void showSystemMessage(Component component) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(component, false);
        }
    }

    /**
     * 解析服务端发来的组件 JSON（adventure Gson 序列化，与 Minecraft 组件格式兼容），
     * 完整还原颜色、可点击文本等样式；解析失败时回退为纯文本组件。
     * <p>
     * 1.21.x 世代签名：Serializer.fromJson(String, HolderLookup.Provider)，注册表取自当前连接。
     */
    public static Component parseComponent(String json, String fallbackText) {
        if (json != null && !json.isEmpty()) {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                var provider = minecraft.getConnection() != null
                        ? minecraft.getConnection().registryAccess()
                        : null;
                Component component = provider != null
                        ? Component.Serializer.fromJson(json, provider)
                        : null;
                if (component != null) {
                    return component;
                }
            } catch (Exception ignored) {
                // 格式不兼容时回退纯文本
            }
        }
        return Component.literal(fallbackText == null ? "" : fallbackText);
    }
}
