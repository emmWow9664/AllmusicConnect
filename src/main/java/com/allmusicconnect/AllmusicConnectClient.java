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
 * 注册两条客户端指令树：
 * <ul>
 *   <li><b>/musicconnect</b> —— 本模组的独立根指令树，<b>不受独立服务端模式与 MC 服务器指令树影响</b>，
 *       任何时候（包括关闭独立服务端模式后、或服务器装有 AllMusic 插件时）都能使用：</li>
 *   <ul>
 *     <li>/musicconnect connect &lt;ip&gt; [端口] —— 连接独立音乐服务器（关闭模式下会先自动开启）</li>
 *     <li>/musicconnect disconnect —— 断开连接</li>
 *     <li>/musicconnect status —— 查看连接状态</li>
 *     <li>/musicconnect standalone [true|false] —— 独立服务端模式总开关</li>
 *     <li>/musicconnect autoconnect &lt;true|false&gt; —— 进入服务器时自动连接开关</li>
 *     <li>/musicconnect compat [true|false|auto] —— AllMusic 客户端 3.x 兼容通道开关</li>
 *     <li>/musicconnect &lt;其它指令&gt; —— 转发到独立音乐服务器（如 play、select、search 等）</li>
 *   </ul>
 *   <li><b>/music</b> —— 与独立服务端兼容的指令树：开启独立服务端模式时，控制指令本地执行、
 *       其余指令转发到独立音乐服务器；在装有 AllMusic 插件的服务器上，关闭该模式后由插件接管 /music。</li>
 * </ul>
 */
public class AllmusicConnectClient implements ClientModInitializer {

    /**
     * 转发到独立服务端的子指令（仅用于 Tab 补全提示）
     */
    private static final String[] FORWARD_COMMANDS = {
            "stop", "help", "list", "vote", "mute", "search",
            "select", "nextpage", "lastpage", "hud", "push", "cancel", "agree",
            "addlist", "connect", "disconnect", "autoconnect", "standalone", "compat", "status",
            "musicconnect"
    };

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(buildMusicConnectTree());
            dispatcher.register(buildMusicTree());
        });

        // 进入世界/服务器后，按配置自动连接上次的独立音乐服务器
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> TcpBridge.INSTANCE.autoConnect());
        // 退出服务器 / 退出游戏时自动断开连接
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TcpBridge.INSTANCE.disconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TcpBridge.INSTANCE.disconnect());
    }

    /**
     * 构建 /musicconnect 指令树（本模组的独立根指令，无任何状态门控）。
     * <p>
     * 不添加 {@code .requires}：即使关闭独立服务端模式（或 MC 服务器上装有 AllMusic 插件、已自带 /music 指令树），
     * 玩家依然能用它开关模式、连接独立服务端，避免出现「关闭后再也无法打开」的死锁。
     */
    public static <S> LiteralArgumentBuilder<S> buildMusicConnectTree() {
        return LiteralArgumentBuilder.<S>literal("musicconnect")
                .then(standaloneNode())
                // connect：关闭独立服务端模式时先自动开启再连接
                .then(connectNode(true))
                .then(disconnectNode())
                .then(statusNode())
                .then(autoconnectNode())
                .then(compatNode())
                .then(forwardNode());
    }

    /**
     * 构建 /music 指令树。
     * <p>
     * 根节点带 {@code .requires(独立服务端模式开启)}：关闭模式下让 Fabric 的客户端指令调度器在根节点就解析失败，
     * brigadier 抛出 dispatcherUnknownCommand（属于 Fabric isIgnoredException 会忽略的类型），于是指令不被本地执行、
     * 原样发给 MC 服务器，由服务器上的 AllMusic 插件处理。
     */
    public static <S> LiteralArgumentBuilder<S> buildMusicTree() {
        return LiteralArgumentBuilder.<S>literal("music")
                .requires(source -> TcpBridge.INSTANCE.isStandaloneEnabled())
                .then(standaloneNode())
                .then(connectNode(false))
                .then(disconnectNode())
                .then(statusNode())
                .then(autoconnectNode())
                .then(compatNode())
                .then(forwardNode());
    }

    /**
     * 独立服务端模式开关节点（/music standalone [true|false]，不带参数显示当前状态）
     */
    private static <S> LiteralArgumentBuilder<S> standaloneNode() {
        return LiteralArgumentBuilder.<S>literal("standalone")
                .executes(ctx -> {
                    TcpBridge.INSTANCE.printStandalone();
                    return 1;
                })
                .then(LiteralArgumentBuilder.<S>literal("true")
                        .executes(ctx -> {
                            TcpBridge.INSTANCE.setStandalone(true);
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<S>literal("false")
                        .executes(ctx -> {
                            TcpBridge.INSTANCE.setStandalone(false);
                            return 1;
                        }));
    }

    /**
     * 连接节点（/music connect &lt;ip&gt; [端口]）
     *
     * @param autoEnable 关闭独立服务端模式时是否先自动开启（/musicconnect 为 true）
     */
    private static <S> LiteralArgumentBuilder<S> connectNode(boolean autoEnable) {
        return LiteralArgumentBuilder.<S>literal("connect")
                .then(RequiredArgumentBuilder.<S, String>argument("ip", StringArgumentType.word())
                        .executes(ctx -> {
                            connect(autoEnable, StringArgumentType.getString(ctx, "ip"), TcpBridge.DEFAULT_PORT);
                            return 1;
                        })
                        .then(RequiredArgumentBuilder.<S, Integer>argument("port", IntegerArgumentType.integer(1, 65535))
                                .executes(ctx -> {
                                    connect(autoEnable, StringArgumentType.getString(ctx, "ip"),
                                            IntegerArgumentType.getInteger(ctx, "port"));
                                    return 1;
                                })));
    }

    private static void connect(boolean autoEnable, String ip, int port) {
        if (autoEnable) {
            TcpBridge.INSTANCE.connectAuto(ip, port);
        } else {
            TcpBridge.INSTANCE.connect(ip, port);
        }
    }

    /**
     * 断开连接节点（/music disconnect）
     */
    private static <S> LiteralArgumentBuilder<S> disconnectNode() {
        return LiteralArgumentBuilder.<S>literal("disconnect")
                .executes(ctx -> {
                    TcpBridge.INSTANCE.disconnect();
                    return 1;
                });
    }

    /**
     * 连接状态节点（/music status）
     */
    private static <S> LiteralArgumentBuilder<S> statusNode() {
        return LiteralArgumentBuilder.<S>literal("status")
                .executes(ctx -> {
                    TcpBridge.INSTANCE.printStatus();
                    return 1;
                });
    }

    /**
     * 自动连接开关节点（/music autoconnect &lt;true|false&gt;）
     */
    private static <S> LiteralArgumentBuilder<S> autoconnectNode() {
        return LiteralArgumentBuilder.<S>literal("autoconnect")
                .then(LiteralArgumentBuilder.<S>literal("true")
                        .executes(ctx -> {
                            TcpBridge.INSTANCE.setAutoConnect(true);
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<S>literal("false")
                        .executes(ctx -> {
                            TcpBridge.INSTANCE.setAutoConnect(false);
                            return 1;
                        }));
    }

    /**
     * AllMusic 客户端 3.x（老版）兼容通道开关节点（/music compat [true|false|auto]）
     */
    private static <S> LiteralArgumentBuilder<S> compatNode() {
        return LiteralArgumentBuilder.<S>literal("compat")
                .executes(ctx -> {
                    TcpBridge.INSTANCE.printCompat();
                    return 1;
                })
                .then(LiteralArgumentBuilder.<S>literal("true")
                        .executes(ctx -> {
                            TcpBridge.INSTANCE.setClientCompat(true);
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<S>literal("false")
                        .executes(ctx -> {
                            TcpBridge.INSTANCE.setClientCompat(Boolean.FALSE);
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<S>literal("auto")
                        .executes(ctx -> {
                            TcpBridge.INSTANCE.setClientCompat(null);
                            return 1;
                        }));
    }

    /**
     * 贪婪转发节点（{@literal <其它指令>}）—— 转发到独立服务端（带 tab 补全）
     */
    private static <S> RequiredArgumentBuilder<S, String> forwardNode() {
        return RequiredArgumentBuilder.<S, String>argument("args", StringArgumentType.greedyString())
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
                });
    }
}