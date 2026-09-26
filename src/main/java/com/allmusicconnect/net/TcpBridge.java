package com.allmusicconnect.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
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
    /**
     * 独立音乐服务端默认端口（/music connect 省略端口时使用）
     */
    public static final int DEFAULT_PORT = 5223;

    private static final Gson GSON = new Gson();
    private static final Gson GSON_PRETTY = new GsonBuilder().setPrettyPrinting().create();

    /** 必须放在 GSON / GSON_PRETTY 之后：构造函数里会读配置文件，用到这两个字段 */
    public static final TcpBridge INSTANCE = new TcpBridge();

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
     * 转发兜底：由 {@code ClientPacketListenerMixin} 在指令即将发往 MC 服务器前调用。
     * <p>
     * 只做一件事——当独立服务端模式开启、且已连接到独立服务端，且玩家输入的是 {@code /music <其它子指令>}
     * （既不是本模组的 {@code /musicconnect}，也不是 {@code /music} 树上已有的控制指令
     * standalone / connect / disconnect / status / autoconnect / compat）时，把该指令转发给独立服务端。
     * <p>
     * 这样做是为了让服务端消息里可点击的 {@code /music select 1} 之类文本，在装了 AllMusic 插件
     * 或已装 AllMusic 客户端 3.x 自带 {@code /music} 指令树的环境下，仍能正确转发到独立服务端。
     * 本方法不做任何本地指令处理，因此不会再有状态门控导致的「指令失效」。
     *
     * @param rawCommand 玩家输入的指令（可能带前导 '/'）
     * @return true 表示已转发给独立服务端，不应再发送给 MC 服务器
     */
    public static boolean forwardFallback(String rawCommand) {
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
        if (!parts[0].equalsIgnoreCase("music") || parts.length < 2) {
            return false;
        }
        if (!INSTANCE.isStandaloneEnabled() || !INSTANCE.isConnected()) {
            return false;
        }
        switch (parts[1].toLowerCase(Locale.ROOT)) {
            // 本模组 /music 树上已有的控制指令：由客户端指令树本地处理，不转发
            case "standalone", "connect", "disconnect", "status", "autoconnect", "compat" -> {
                return false;
            }
            default -> {
                INSTANCE.forwardCommand("/music " + command.substring(parts[0].length()).trim());
                return true;
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

    /**
     * 连接独立音乐服务端；若独立服务端模式处于关闭状态，先自动开启再连接。
     * <p>
     * 供 {@code /musicconnect connect} 使用——关闭模式下也能一键连回独立服务端，
     * 不会出现「关闭后指令全部失效、无法再打开」的死锁。
     */
    public void connectAuto(String ip, int port) {
        if (!isStandaloneEnabled()) {
            setStandalone(true);
        }
        connect(ip, port);
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
     * 打印独立服务端模式当前状态（/musicconnect standalone 不带参数时）
     */
    public void printStandalone() {
        sendMsg(isStandaloneEnabled()
                ? "独立服务端模式：已开启（/music standalone false 关闭，关闭后由 MC 服务器上的 AllMusic 插件接管 /music 指令）"
                : "独立服务端模式：已关闭（/music standalone true 开启）");
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
     * 开启/关闭 AllMusic 客户端 3.x（老版）兼容通道。
     * <p>
     * 3.x（如 1.21.4 上的 AllMusic_Client-3.1.6）没有 codec 包、类型表与 HUD 数据结构都与 4.x 不同，
     * 由本模组把数据包翻译成 3.x 格式再交给它。
     *
     * @param enable true = 强制开启，false = 强制关闭，null = 自动（按检测到的客户端版本决定）
     */
    public void setClientCompat(Boolean enable) {
        prefs.client3xCompat = enable;
        savePrefs();
        PackRouter.setCompatOverride(enable);
        if (enable == null) {
            sendMsg("3.x 客户端兼容通道：已改为自动（检测到 3.x 客户端时自动开启，4.x 客户端不受影响）");
        } else if (enable) {
            sendMsg("已强制开启 3.x 客户端兼容通道：老版 AllMusic 客户端（3.1.6 等）可播放音乐与歌词；"
                    + "逐字歌词、播放时间同步、m4a 格式歌曲仍不可用，建议升级到 4.x 客户端");
        } else {
            sendMsg("已强制关闭 3.x 客户端兼容通道：3.x 客户端将不再收到音乐数据包（/music compat auto 恢复自动）");
        }
    }

    /** 打印 3.x 兼容通道状态 */
    public void printCompat() {
        String state = PackRouter.isCompatAuto()
                ? "自动（检测到 3.x 客户端时自动开启）"
                : (PackRouter.isCompatEnabled() ? "已强制开启" : "已强制关闭");
        sendMsg("3.x 客户端兼容通道：" + state
                + "；当前生效=" + (PackRouter.isCompatEnabled() ? "开" : "关")
                + "（/music compat [true|false|auto] 修改）");
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
                // MusicPack：按运行时安装的 AllMusic 客户端世代（3.x / 4.x）分发处理
                PackRouter.dispatch(data);
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
        } catch (Throwable t) {
            // 单个数据包处理失败（包括 NoClassDefFoundError 等 Error）绝不能断开与音乐服务器的连接
            System.out.println("[AllmusicConnect] 数据包处理出错（kind=" + kind + "）：" + t);
            t.printStackTrace();
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
            prefs.client3xCompat = loaded.client3xCompat;
        } catch (Exception e) {
            System.out.println("[AllmusicConnect] 读取配置失败：" + e);
        }
        PackRouter.setCompatOverride(prefs.client3xCompat);
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
        /** 3.x 客户端兼容通道：null = 自动（检测到 3.x 客户端自动开启），true/false = 强制开关。
         *  3.x 的类型表与 HUD 数据结构都与 4.x 不同，由模组翻译后转发 */
        private Boolean client3xCompat;
    }
}