package com.allmusicconnect.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * AllMusic 客户端 <b>3.x</b> 数据通道（例如 1.21.4 上官方发布的 AllMusic_Client-3.1.6）。
 * <p>
 * 3.x 没有 codec 包，由 {@code AllMusicCore.packRead(ByteBuf)} 自己解码；它的类型表与 4.x 不同：
 * <pre>
 * 3.x：LYRIC(0) INFO(1) LIST(2) PLAY(3) IMG(4) STOP(5) CLEAR(6) POS(7) HUD_DATA(8)
 * 4.x：LYRIC(0) LYRIC_KTV(1) INFO(2) PLAY(3) IMG(4) STOP(5) CLEAR(6) POS(7) HUD_DATA(8) TIME(9)
 * </pre>
 * 所以需要：重写类型字节（4.x INFO=2 → 3.x INFO=1）、丢弃 3.x 无法表达的 LYRIC_KTV(1)/TIME(9)、
 * 并把 4.x 的 HUD_DATA JSON 翻译成 3.x 的 {@code SaveOBJ} 结构（见 {@link #convertHud}）。
 * 字符串编码两边一致（int 长度 + UTF-8），且每帧都有长度前缀，多出来的字段会被 3.x 忽略。
 * <p>
 * 只有运行时确认客户端提供 {@code packRead} 时才会被加载（见 {@link PackRouter}）。
 */
final class PackV3 {

    /** 4.x 的 LYRIC_KTV */
    private static final int TYPE_V4_LYRIC_KTV = 1;
    /** 4.x 的 INFO */
    private static final int TYPE_V4_INFO = 2;
    /** HUD_DATA（两代序号相同，都是 8） */
    private static final int TYPE_HUD_DATA = 8;
    /** 4.x 的 TIME */
    private static final int TYPE_V4_TIME = 9;

    private static boolean skippedNotice;
    private static boolean hudFailNotice;
    private static int logged;
    private static boolean stateChecked;
    private static boolean picCheckStarted;
    /** 3.x 客户端的封面尺寸缓存（见 clientPicSize） */
    private static Integer clientPicSize;
    /** 3.x 客户端内部的解码入口：AllMusicCore.packRead(ByteBuf)，反射获取（首个数据包时） */
    private static Method packReadMethod;

    private PackV3() {
    }

    static void dispatch(byte[] frame) {
        if (frame.length < 1) {
            return;
        }
        byte[] out = convert(frame);
        if (out == null) {
            return;
        }
        debug(frame, out);
        Method method = packRead();
        if (method == null) {
            return;
        }
        ByteBuf buf = Unpooled.wrappedBuffer(out);
        Minecraft.getInstance().execute(() -> {
            try {
                method.invoke(null, buf);
            } catch (Throwable t) {
                System.out.println("[AllmusicConnect] 播放数据包处理出错：" + t);
                t.printStackTrace();
            }
        });
    }

    /**
     * 兼容调试日志：打印类型字节（原始 -> 转换后）、长度，PLAY(3)/IMG(4)/INFO(1) 还会打印 URL/文本内容，
     * 首个数据包时顺带检查 3.x 客户端的核心静态字段是否已初始化（便于区分「包没到」「包到了但客户端内部失败」）。
     * 每局最多打印 20 条普通包，PLAY/IMG/INFO 始终打印（它们很少且最能说明问题）。
     */
    private static void debug(byte[] raw, byte[] converted) {
        int type = converted[0] & 0xFF;
        boolean detail = type == 1 || type == 3 || type == 4;
        if (!detail && logged >= 20) {
            return;
        }
        logged++;
        if (!stateChecked) {
            stateChecked = true;
            checkClientState();
        }
        StringBuilder sb = new StringBuilder("[AllmusicConnect][兼容调试] 数据包 type=")
                .append(raw[0] & 0xFF).append("->").append(type)
                .append(" len=").append(converted.length);
        if (detail) {
            sb.append(" 内容=").append(payloadString(converted));
        }
        System.out.println(sb);
        if (type == 4) {
            // 封面包：延迟检查 3.x 客户端的封面加载状态
            schedulePicCheck();
        }
    }

    /** 检查 3.x 客户端的核心静态字段（bridge/hud/player/config）是否已初始化 */
    private static void checkClientState() {
        try {
            Class<?> core = Class.forName("com.coloryr.allmusic.client.core.AllMusicCore");
            for (String name : new String[]{"bridge", "hud", "player", "config"}) {
                String state;
                try {
                    Field field = core.getDeclaredField(name);
                    field.setAccessible(true);
                    state = field.get(null) == null ? "null（客户端未初始化）" : "已初始化";
                } catch (Throwable t) {
                    state = "读取失败：" + t;
                }
                System.out.println("[AllmusicConnect][兼容调试] AllMusicCore." + name + " = " + state);
            }
        } catch (Throwable t) {
            System.out.println("[AllmusicConnect][兼容调试] 未找到 AllMusicCore：" + t);
        }
    }

    /** 取出字符串型数据包的内容（int 长度 + UTF-8） */
    private static String payloadString(byte[] frame) {
        try {
            ByteBuffer in = ByteBuffer.wrap(frame);
            in.get();
            int length = in.getInt();
            if (length < 0 || length > frame.length - 5) {
                return "<长度异常 " + length + ">";
            }
            byte[] text = new byte[length];
            in.get(text);
            return new String(text, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "<读取失败 " + t + ">";
        }
    }

    /**
     * 收到封面（IMG）后延迟 5 秒打印一次 3.x 客户端的封面状态，用于定位「封面不显示」卡在哪一步：
     * haveImg=false 且 needUpload=true 表示它自己没完成图片加载/纹理上传；
     * haveImg=true 说明图片已就绪但绘制（位置/尺寸）有问题。
     */
    private static void schedulePicCheck() {
        if (picCheckStarted) {
            return;
        }
        picCheckStarted = true;
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                return;
            }
            try {
                Class<?> core = Class.forName("com.coloryr.allmusic.client.core.AllMusicCore");
                Object hud = read(core, null, "hud");
                if (hud == null) {
                    System.out.println("[AllmusicConnect][兼容调试] 封面检查：AllMusicCore.hud 为 null");
                    return;
                }
                System.out.println("[AllmusicConnect][兼容调试] 封面检查：haveImg=" + read(hud.getClass(), hud, "haveImg")
                        + " needUpload=" + read(hud.getClass(), hud, "needUpload")
                        + " size=" + read(hud.getClass(), hud, "size"));
                Object save = read(hud.getClass(), hud, "save");
                if (save == null) {
                    System.out.println("[AllmusicConnect][兼容调试] 封面检查：hud.save 为 null（未收到 HUD_DATA）");
                    return;
                }
                Object pic = save.getClass().getField("pic").get(save);
                System.out.println("[AllmusicConnect][兼容调试] 封面检查：save.pic enable="
                        + pic.getClass().getField("enable").get(pic)
                        + " x=" + pic.getClass().getField("x").get(pic)
                        + " y=" + pic.getClass().getField("y").get(pic)
                        + " dir=" + pic.getClass().getField("dir").get(pic));
            } catch (Throwable t) {
                System.out.println("[AllmusicConnect][兼容调试] 封面检查失败：" + t);
            }
        }, "allmusic-connect-piccheck");
        thread.setDaemon(true);
        thread.start();
    }

    /** 反射读取字段（先按实例类找，再回到 AllMusicCore 的静态字段） */
    private static Object read(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * 3.x 客户端使用的封面尺寸（{@code AllMusicHud.size}）；
     * 由于 3.1.6 把它误当绘制尺寸、而把 color 当尺寸传入，我们需要这个值来回填 color 字段。
     */
    private static int clientPicSize() {
        if (clientPicSize != null) {
            return clientPicSize;
        }
        int size = 200;
        try {
            Class<?> core = Class.forName("com.coloryr.allmusic.client.core.AllMusicCore");
            Object hud = read(core, null, "hud");
            if (hud != null) {
                Object value = read(hud.getClass(), hud, "size");
                if (value instanceof Integer number && number > 0) {
                    size = number;
                }
            }
        } catch (Throwable ignored) {
            // 读取失败用默认值
        }
        clientPicSize = size;
        return size;
    }

    /**
     * 4.x 帧 -> 3.x 帧
     *
     * @return 转换后的帧；返回 null 表示 3.x 无法表达该数据包（丢弃）
     */
    private static byte[] convert(byte[] frame) {
        int type = frame[0] & 0xFF;
        switch (type) {
            case TYPE_V4_LYRIC_KTV, TYPE_V4_TIME -> {
                if (!skippedNotice) {
                    skippedNotice = true;
                    System.out.println("[AllmusicConnect] 当前 AllMusic 客户端为 3.x，"
                            + (type == TYPE_V4_LYRIC_KTV ? "逐字（KTV）歌词" : "播放时间同步")
                            + "不可用，已跳过该数据包；升级到 4.x 客户端即可恢复");
                }
                return null;
            }
            case TYPE_HUD_DATA -> {
                return convertHud(frame);
            }
            case 0, 3, 4, 5, 6, 7 -> {
                // 类型序号与 3.x 一致，原样转发
                return frame;
            }
            case TYPE_V4_INFO -> {
                frame[0] = 1;   // 4.x INFO(2) -> 3.x INFO(1)
                return frame;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 4.x HUD_DATA -> 3.x HUD_DATA：载荷 JSON 用 {@link HudJsonV3} 翻译后重新打包
     * （未翻译的 4.x HUD JSON 会让 3.x 的 dir 为 null，渲染时崩溃）
     */
    private static byte[] convertHud(byte[] frame) {
        try {
            ByteBuffer in = ByteBuffer.wrap(frame);
            in.get();                                   // 类型字节
            int length = in.getInt();
            if (length < 0 || length > frame.length - 5) {
                return null;
            }
            byte[] text = new byte[length];
            in.get(text);
            String json = HudJsonV3.translate(new String(text, StandardCharsets.UTF_8), clientPicSize());
            if (json == null) {
                return null;
            }

            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            ByteBuffer out = ByteBuffer.allocate(5 + payload.length);
            out.put((byte) TYPE_HUD_DATA);
            out.putInt(payload.length);
            out.put(payload);
            return out.array();
        } catch (Throwable t) {
            // 翻译失败就不发送（未翻译的 HUD_DATA 会让 3.x 崩溃）
            if (!hudFailNotice) {
                hudFailNotice = true;
                System.out.println("[AllmusicConnect] 3.x 兼容：HUD 位置数据翻译失败，已跳过：" + t);
            }
            return null;
        }
    }

    private static synchronized Method packRead() {
        if (packReadMethod == null) {
            try {
                packReadMethod = Class.forName("com.coloryr.allmusic.client.core.AllMusicCore")
                        .getMethod("packRead", ByteBuf.class);
            } catch (Throwable ignored) {
                // 调用方会跳过该数据包
            }
        }
        return packReadMethod;
    }
}