package com.allmusicconnect;

import com.allmusicconnect.net.TcpBridge;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * AllmusicConnect 客户端入口
 * <p>
 * 注册 /music 客户端指令：
 * <ul>
 *   <li>/music connect &lt;ip&gt; &lt;端口&gt; —— 连接到第三方独立音乐服务器</li>
 *   <li>/music disconnect —— 断开连接</li>
 *   <li>/music status —— 查看连接状态</li>
 *   <li>/music &lt;其它 allmusic 指令&gt; —— 转发到独立音乐服务器执行（如 play、stop、search 等）</li>
 * </ul>
 */
public class AllmusicConnectClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("music")
                        // /music connect <ip> <端口>
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("connect")
                                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("ip", StringArgumentType.word())
                                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("port", IntegerArgumentType.integer(1, 65535))
                                                .executes(ctx -> {
                                                    String ip = StringArgumentType.getString(ctx, "ip");
                                                    int port = IntegerArgumentType.getInteger(ctx, "port");
                                                    TcpBridge.INSTANCE.connect(ip, port);
                                                    return 1;
                                                }))))
                        // /music disconnect
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("disconnect")
                                .executes(ctx -> {
                                    TcpBridge.INSTANCE.disconnect();
                                    return 1;
                                }))
                        // /music status
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                                .executes(ctx -> {
                                    TcpBridge.INSTANCE.printStatus();
                                    return 1;
                                }))
                        // /music <其它指令> —— 转发到独立服务端（带 tab 补全）
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("args", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    // AllMusic 服务端全部子命令 + 本模组命令（服务端无权限的命令会被拒绝）
                                    String[] commands = {
                                            "stop", "help", "list", "vote", "mute", "search", "searchapi",
                                            "select", "nextpage", "lastpage", "hud", "push", "join", "cancel", "agree",
                                            "reload", "next", "ban", "unban", "banplayer", "unbanplayer", "delete",
                                            "addlist", "clearlist", "clearban", "clearbanplayer", "test",
                                            "connect", "disconnect", "status"
                                    };
                                    for (String cmd : commands) {
                                        builder.suggest(cmd);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String args = StringArgumentType.getString(ctx, "args");
                                    TcpBridge.INSTANCE.forwardCommand("/music " + args);
                                    return 1;
                                }))
        ));

        // 退出服务器 / 退出游戏时自动断开连接
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TcpBridge.INSTANCE.disconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TcpBridge.INSTANCE.disconnect());
    }
}
