package com.allmusicconnect;

import com.allmusicconnect.net.TcpBridge;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/**
 * AllmusicConnect 客户端入口
 * <p>
 * 注册 /music 客户端指令：
 * <ul>
 *   <li>/music connect &lt;ip&gt; [端口] —— 连接到第三方独立音乐服务器（端口省略时用默认 5223）</li>
 *   <li>/music disconnect —— 断开连接</li>
 *   <li>/music status —— 查看连接状态</li>
 *   <li>/music autoconnect &lt;true|false&gt; —— 开关「进入服务器时自动连接上次的独立音乐服务器」</li>
 *   <li>/music &lt;其它 allmusic 指令&gt; —— 转发到独立音乐服务器执行（如 play、stop、search 等）</li>
 * </ul>
 */
public class AllmusicConnectClient implements ClientModInitializer {

    /**
     * 转发到独立服务端的子指令（仅用于 Tab 补全提示）
     */
    private static final String[] FORWARD_COMMANDS = {
            "stop", "help", "list", "vote", "mute", "search",
            "select", "nextpage", "lastpage", "hud", "push", "cancel", "agree",
            "addlist", "connect", "disconnect", "autoconnect", "status"
    };

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(buildMusicTree()));

        // 进入世界/服务器后，按配置自动连接上次的独立音乐服务器
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> TcpBridge.INSTANCE.autoConnect());
        // 退出服务器 / 退出游戏时自动断开连接
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TcpBridge.INSTANCE.disconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TcpBridge.INSTANCE.disconnect());
    }

    /**
     * 构建 /music 指令树。
     * <p>
     * 方法泛型化：既能注册到客户端指令调度器（负责执行），也能注册到 MC 服务器的指令调度器
     * （负责 Tab 补全，见 {@code ClientPacketListenerMixin}）。
     */
    public static <S> LiteralArgumentBuilder<S> buildMusicTree() {
        return LiteralArgumentBuilder.<S>literal("music")
                // /music connect <ip> [端口]
                .then(LiteralArgumentBuilder.<S>literal("connect")
                        .then(RequiredArgumentBuilder.<S, String>argument("ip", StringArgumentType.word())
                                .executes(ctx -> {
                                    TcpBridge.INSTANCE.connect(StringArgumentType.getString(ctx, "ip"),
                                            TcpBridge.DEFAULT_PORT);
                                    return 1;
                                })
                                .then(RequiredArgumentBuilder.<S, Integer>argument("port", IntegerArgumentType.integer(1, 65535))
                                        .executes(ctx -> {
                                            TcpBridge.INSTANCE.connect(StringArgumentType.getString(ctx, "ip"),
                                                    IntegerArgumentType.getInteger(ctx, "port"));
                                            return 1;
                                        }))))
                // /music disconnect
                .then(LiteralArgumentBuilder.<S>literal("disconnect")
                        .executes(ctx -> {
                            TcpBridge.INSTANCE.disconnect();
                            return 1;
                        }))
                // /music status
                .then(LiteralArgumentBuilder.<S>literal("status")
                        .executes(ctx -> {
                            TcpBridge.INSTANCE.printStatus();
                            return 1;
                        }))
                // /music autoconnect <true|false>
                .then(LiteralArgumentBuilder.<S>literal("autoconnect")
                        .then(LiteralArgumentBuilder.<S>literal("true")
                                .executes(ctx -> {
                                    TcpBridge.INSTANCE.setAutoConnect(true);
                                    return 1;
                                }))
                        .then(LiteralArgumentBuilder.<S>literal("false")
                                .executes(ctx -> {
                                    TcpBridge.INSTANCE.setAutoConnect(false);
                                    return 1;
                                })))
                // /music <其它指令> —— 转发到独立服务端（带 tab 补全）
                .then(RequiredArgumentBuilder.<S, String>argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            // AllMusic 服务端子命令 + 本模组命令（服务端无权限的命令会被拒绝）
                            for (String cmd : FORWARD_COMMANDS) {
                                builder.suggest(cmd);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            TcpBridge.INSTANCE.forwardCommand("/music " + StringArgumentType.getString(ctx, "args"));
                            return 1;
                        }));
    }
}