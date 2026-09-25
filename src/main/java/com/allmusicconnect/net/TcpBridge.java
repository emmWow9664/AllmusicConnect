package com.allmusicconnect.net;

import com.coloryr.allmusic.client.core.AllMusicCore;
import com.coloryr.allmusic.codec.MusicPack;
import com.coloryr.allmusic.codec.MusicPacketCodec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 与独立音乐服务端（AllmusicServer）的 TCP 桥接
 * <p>
 * 协议（客户端 -&gt; 服务端）：1 字节 kind(0) + 4 字节长度 + JSON
 * <ul>
 *   <li>{"type":"handshake","name":"玩家名"}</li>
 *   <li>{"type":"command","text":"/music ..."}</li>
 * </ul>
 * 协议（服务端 -&gt; 客户端）：
 * <ul>
 *   <li>kind=1：MusicPack 二进制数据（与 AllMusic 客户端模组兼容），直接交给 AllMusicCore.packDo 播放</li>
 *   <li>kind=2：JSON {"type":"chat"|"bar","json":"adventure组件json","text":"纯文本"}</li>
 * </ul>
 */
public class TcpBridge {
    public static final TcpBridge INSTANCE = new TcpBridge();

    /**
     * 独立音乐服务端默认端口（/music connect 省略端口时使用）
     */
    public static final int DEFAULT_PORT = 5223;

    private static final Gson GSON = new Gson();
    private static final Gson GSON_PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean connected;
    /** 是否正在后台建立连接 */
    private volatile boolean connecting;
    private volatile String target;

    /**
     * 客户端配置：自动连接开关 + 上次连接的地址（保存在 config/amc10086.json）
     */
    private final ClientPrefs prefs = new ClientPrefs();

    private TcpBridge() {
        loadPrefs();
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * 在客户端本地处理本模组拥有的 /music 子指令。
     * <p>
     * 由 {@code ClientPacketListenerMixin} 在客户端即将把指令发给 MC 服务器时调用：
     * 当所连的 MC 服务器装有 AllMusic 服务端插件时，服务端的 /music 命令树会覆盖客户端注册的同名指令，
     * 导致 /music connect ... 被发往服务器并被拒绝（"你没有权限执行这个操作"）。
     *
     * @param rawCommand 玩家输入的指令（可能带前导 '/'，由调用方保证非空）
     * @return true 表示已在本地处理，不应再发送给 MC 服务器
     */
    public static boolean handleLocal(String rawCommand) {
        if (rawCommand == null) {
            return false;
        }
        String command = rawCommand.trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty()) {
            return false;
        }
        String[] parts = command.split("\\s+");
        if (!parts[0].equalsIgnoreCase("music")) {
            return false;
        }
        if (parts.length < 2) {
            // 只有 "/music"，交给原有逻辑
            return false;
        }
        String sub = parts[1].toLowerCase(Locale.ROOT);
        // 独立服务端模式开关始终由本模组处理：关闭后玩家仍能靠它重新开启
        if (sub.equals("standalone")) {
            if (parts.length >= 3 && parts[2].equalsIgnoreCase("true")) {
                INSTANCE.setStandalone(true);
            } else if (parts.length >= 3 && parts[2].equalsIgnoreCase("false")) {
                INSTANCE.setStandalone(false);
            } else if (parts.length >= 3) {
                INSTANCE.sendMsg("用法：/music standalone [true|false]");
            } else {
                INSTANCE.sendMsg(INSTANCE.isStandaloneEnabled()
                        ? "独立服务端模式：已开启（/music standalone false 关闭，关闭后由 MC 服务器上的 AllMusic 插件接管 /music 指令）"
                        : "独立服务端模式：已关闭（/music standalone true 开启）");
            }
            return true;
        }
        if (!INSTANCE.isStandaloneEnabled()) {
            // 独立服务端模式已关闭：本模组完全不介入，指令全部交给 MC 服务器（AllMusic 插件）
            return false;
        }
        switch (sub) {
            case "connect" -> {
                if (parts.length >= 3) {
                    int port = DEFAULT_PORT;
                    if (parts.length >= 4) {
                        try {
                            port = Integer.parseInt(parts[3]);
                        } catch (NumberFormatException e) {
                            INSTANCE.sendMsg("端口号无效：" + parts[3]);
                            return true;
                        }
                        if (port < 1 || port > 65535) {
                            INSTANCE.sendMsg("端口号超出范围：" + port);
                            return true;
                        }
                    }
                    INSTANCE.connect(parts[2], port);
                } else {
                    INSTANCE.sendMsg("用法：/music connect <ip> [端口]（端口默认 " + DEFAULT_PORT + "）");
                }
                return true;
            }
            case "disconnect" -> {
                INSTANCE.disconnect();
                return true;
            }
            case "status" -> {
                INSTANCE.printStatus();
                return true;
            }
            case "autoconnect" -> {
                if (parts.length >= 3 && parts[2].equalsIgnoreCase("true")) {
                    INSTANCE.setAutoConnect(true);
                } else if (parts.length >= 3 && parts[2].equalsIgnoreCase("false")) {
                    INSTANCE.setAutoConnect(false);
                } else {
                    INSTANCE.sendMsg("用法：/music autoconnect <true|false>");
                }
                return true;
            }
            default -> {
                // 其它子指令：已连接音乐服务器时由其处理；否则放行，交给 MC 服务器（可能是服务端 AllMusic 插件）
                if (INSTANCE.isConnected()) {
                    INSTANCE.forwardCommand("/music " + command.substring(parts[0].length()).trim());
                    return true;
                }
                return false;
            }
        }
    }

    /**
     * 连接独立音乐服务端并完成握手。
     * <p>
     * 建立连接（含 5 秒超时）在后台线程执行，避免阻塞渲染线程导致游戏卡顿。
     */
    public void connect(String ip, int port) {
        if (connected) {
            sendMsg("已连接到音乐服务器：" + target);
            return;
        }
        if (connecting) {
            sendMsg("正在连接音乐服务器，请稍候（可用 /music disconnect 取消）");
            return;
        }
        connecting = true;
        sendMsg("正在连接音乐服务器 " + ip + ":" + port + " ...");
        Thread thread = new Thread(() -> doConnect(ip, port), "allmusic-connect-" + ip + ":" + port);
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized void doConnect(String ip, int port) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ip, port), 5000);
            if (!connecting) {
                // 连接过程中被 /music disconnect 取消
                s.close();
                return;
            }
            s.setTcpNoDelay(true);
            socket = s;
            in = new DataInputStream(s.getInputStream());
            out = new DataOutputStream(s.getOutputStream());
            connected = true;
            target = ip + ":" + port;

            // 记下这次成功连接，供 /music autoconnect 下次自动连接
            prefs.lastIp = ip;
            prefs.lastPort = port;
            savePrefs();

            String playerName = Compat.getPlayerName();
            JsonObject handshake = new JsonObject();
            handshake.addProperty("type", "handshake");
            handshake.addProperty("name", playerName);
            sendJson(GSON.toJson(handshake));

            Thread thread = new Thread(this::readLoop, "allmusic-connect-read");
            thread.setDaemon(true);
            thread.start();

            sendMsg("已连接到音乐服务器 " + target + "（玩家：" + playerName + "）");
        } catch (Exception e) {
            connected = false;
            closeSocket();
            sendMsg("连接音乐服务器失败：" + e.getMessage());
        } finally {
            connecting = false;
        }
    }

    /**
     * 断开连接
     */
    public synchronized void disconnect() {
        if (connecting) {
            // 取消正在进行的连接
            connecting = false;
            sendMsg("已取消连接音乐服务器");
            return;
        }
        if (!connected) {
            return;
        }
        connected = false;
        closeSocket();
        sendMsg("已断开与音乐服务器的连接");
    }

    /**
     * 进入服务器/世界后，若独立服务端模式与自动连接都已开启，则连接上次的独立音乐服务器
     */
    public void autoConnect() {
        if (!prefs.standalone || !prefs.autoConnect || prefs.lastIp == null || prefs.lastIp.isEmpty()) {
            return;
        }
        connect(prefs.lastIp, prefs.lastPort);
    }

    /**
     * 独立服务端模式是否开启。
     * <p>
     * 关闭后本模组不再拦截 /music 指令（除开关本身），全部交给 MC 服务器上的 AllMusic 插件处理。
     * 用 Boolean 保存并把 null 视为开启：旧版本配置文件里没有该字段时保持「开启」，行为不变。
     */
    public boolean isStandaloneEnabled() {
        return !Boolean.FALSE.equals(prefs.standalone);
    }

    /**
     * 开启/关闭独立服务端模式；关闭时断开当前连接，让 MC 服务器的 AllMusic 插件接管 /music
     */
    public void setStandalone(boolean enable) {
        if (isStandaloneEnabled() == enable) {
            sendMsg(enable ? "独立服务端模式已经是开启状态" : "独立服务端模式已经是关闭状态");
            return;
        }
        prefs.standalone = enable;
        savePrefs();
        if (enable) {
            sendMsg("已开启独立服务端模式：/music <指令> 将转发给独立音乐服务器（用 /music connect 连接）");
        } else {
            disconnect();
            sendMsg("已关闭独立服务端模式：已断开独立音乐服务器，/music 指令交由 MC 服务器上的 AllMusic 插件处理（用 /music standalone true 可重新开启）");
        }
    }

    /**
     * 开启/关闭自动连接
     */
    public void setAutoConnect(boolean enable) {
        prefs.autoConnect = enable;
        savePrefs();
        if (enable) {
            if (prefs.lastIp == null || prefs.lastIp.isEmpty()) {
                sendMsg("已开启自动连接：还没有可用的上次地址，请先用 /music connect 连接一次");
            } else {
                sendMsg("已开启自动连接：进入服务器后将自动连接 " + prefs.lastIp + ":" + prefs.lastPort);
            }
        } else {
            sendMsg("已关闭自动连接：请使用 /music connect <ip> [端口] 手动连接");
        }
    }

    /**
     * 打印当前连接状态
     */
    public void printStatus() {
        String usage = "使用 /music connect <ip> [端口] 连接（端口默认 " + DEFAULT_PORT + "）";
        if (connected) {
            sendMsg("已连接到音乐服务器：" + target);
        } else if (connecting) {
            sendMsg("正在连接音乐服务器 ...");
        } else {
            sendMsg("未连接到音乐服务器，" + usage);
        }
    }

    /**
     * 转发一条音乐指令到独立服务端；未连接时给出提示
     */
    public void forwardCommand(String command) {
        if (!connected) {
            sendMsg("未连接到音乐服务器，使用 /music connect <ip> [端口] 连接");
            return;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "command");
        obj.addProperty("text", command);
        sendJson(GSON.toJson(obj));
    }

    /**
     * 读线程：接收服务端推送的数据包
     */
    private void readLoop() {
        try {
            while (connected) {
                int kind = in.readUnsignedByte();
                int length = in.readInt();
                if (length < 0 || length > 1024 * 1024 * 16) {
                    break;
                }
                byte[] data = new byte[length];
                in.readFully(data);
                handleFrame(kind, data);
            }
        } catch (EOFException e) {
            // 服务端正常关闭连接
        } catch (Exception e) {
            if (connected) {
                System.out.println("[AllmusicConnect] 连接异常：" + e);
            }
        } finally {
            boolean wasConnected = connected;
            synchronized (this) {
                connected = false;
            }
            closeSocket();
            if (wasConnected) {
                sendMsg("与音乐服务器连接已断开");
            }
        }
    }

    private void handleFrame(int kind, byte[] data) {
        try {
            if (kind == 1) {
                // MusicPack：交给 AllMusic 客户端核心处理（播放、歌词、封面等）
                ByteBuf buf = Unpooled.wrappedBuffer(data);
                MusicPack pack = MusicPacketCodec.decode(buf);
                Minecraft.getInstance().execute(() -> AllMusicCore.packDo(pack));
            } else if (kind == 2) {
                String json = new String(data, StandardCharsets.UTF_8);
                JsonObject obj = GSON.fromJson(json, JsonObject.class);
                if (obj == null || !obj.has("type")) {
                    return;
                }
                String type = obj.get("type").getAsString();
                String text = obj.has("text") ? obj.get("text").getAsString() : "";
                // 优先解析服务端发来的 adventure 组件 JSON（保留颜色、可点击事件等完整样式），失败则回退纯文本
                String jsonData = obj.has("json") ? obj.get("json").getAsString() : null;
                Component component = Compat.parseComponent(jsonData, text);
                if ("bar".equals(type)) {
                    // 物品栏上方消息
                    Minecraft.getInstance().execute(() -> Compat.showOverlayMessage(component));
                } else {
                    // 聊天消息
                    Minecraft.getInstance().execute(() -> Compat.showSystemMessage(component));
                }
            }
        } catch (Exception e) {
            System.out.println("[AllmusicConnect] 数据包处理出错：" + e);
        }
    }

    private void sendJson(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                if (!connected || out == null) {
                    return;
                }
                out.writeByte(0);
                out.writeInt(bytes.length);
                out.write(bytes);
                out.flush();
            } catch (Exception e) {
                // 发送失败，交给读线程处理断开
            }
        }
    }

    private void closeSocket() {
        synchronized (writeLock) {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
            in = null;
            out = null;
        }
    }

    /**
     * 在游戏聊天栏显示一条本地消息
     */
    private void sendMsg(String text) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player == null) {
                return;
            }
            Compat.showSystemMessage(
                    Component.literal("[AllmusicConnect] " + text).withStyle(ChatFormatting.AQUA));
        });
    }

    private void loadPrefs() {
        try {
            Path file = prefsFile();
            if (!Files.exists(file)) {
                return;
            }
            ClientPrefs loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClientPrefs.class);
            if (loaded == null) {
                return;
            }
            prefs.standalone = loaded.standalone == null ? Boolean.TRUE : loaded.standalone;
            prefs.autoConnect = loaded.autoConnect;
            prefs.lastIp = loaded.lastIp == null ? "" : loaded.lastIp;
            prefs.lastPort = loaded.lastPort < 1 || loaded.lastPort > 65535 ? DEFAULT_PORT : loaded.lastPort;
        } catch (Exception e) {
            System.out.println("[AllmusicConnect] 读取配置失败：" + e);
        }
    }

    private void savePrefs() {
        try {
            Path file = prefsFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON_PRETTY.toJson(prefs), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[AllmusicConnect] 保存配置失败：" + e);
        }
    }

    private Path prefsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("amc10086.json");
    }

    /**
     * 客户端配置内容（Gson 序列化）
     */
    private static final class ClientPrefs {
        /** 独立服务端模式：关闭后不拦截 /music 指令，交给 MC 服务器上的 AllMusic 插件。
         *  用包装类型：旧配置文件不含该字段时按 null 处理，视为开启（保持原有行为） */
        private Boolean standalone = Boolean.TRUE;
        /** 是否在进入服务器时自动连接上次的独立音乐服务器 */
        private boolean autoConnect;
        /** 上次成功连接的地址 */
        private String lastIp = "";
        private int lastPort = DEFAULT_PORT;
    }
}