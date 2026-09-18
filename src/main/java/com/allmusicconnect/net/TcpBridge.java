package com.allmusicconnect.net;

import com.coloryr.allmusic.client.core.AllMusicCore;
import com.coloryr.allmusic.codec.MusicPack;
import com.coloryr.allmusic.codec.MusicPacketCodec;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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

    private static final Gson GSON = new Gson();

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean connected;
    private volatile String target;

    private TcpBridge() {
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * 连接独立音乐服务端并完成握手
     */
    public synchronized void connect(String ip, int port) {
        if (connected) {
            sendMsg("已连接到音乐服务器：" + target);
            return;
        }
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ip, port), 5000);
            s.setTcpNoDelay(true);
            socket = s;
            in = new DataInputStream(s.getInputStream());
            out = new DataOutputStream(s.getOutputStream());
            connected = true;
            target = ip + ":" + port;

            String playerName = Compat.getPlayerName();
            JsonObject handshake = new JsonObject();
            handshake.addProperty("type", "handshake");
            handshake.addProperty("name", playerName);
            sendJson(GSON.toJson(handshake));

            Thread thread = new Thread(this::readLoop, "allmusic-connect");
            thread.setDaemon(true);
            thread.start();

            sendMsg("已连接到音乐服务器 " + target + "（玩家：" + playerName + "）");
        } catch (Exception e) {
            connected = false;
            closeSocket();
            sendMsg("连接音乐服务器失败：" + e.getMessage());
        }
    }

    /**
     * 断开连接
     */
    public synchronized void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        closeSocket();
        sendMsg("已断开与音乐服务器的连接");
    }

    /**
     * 打印当前连接状态
     */
    public void printStatus() {
        if (connected) {
            sendMsg("已连接到音乐服务器：" + target);
        } else {
            sendMsg("未连接到音乐服务器，使用 /music connect <ip> <端口> 连接");
        }
    }

    /**
     * 转发一条音乐指令到独立服务端；未连接时给出提示
     */
    public void forwardCommand(String command) {
        if (!connected) {
            sendMsg("未连接到音乐服务器，使用 /music connect <ip> <端口> 连接");
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
}
