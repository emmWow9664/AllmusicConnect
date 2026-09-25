package com.allmusicconnect.net;

import io.netty.buffer.ByteBuf;

/**
 * 按运行时安装的 AllMusic 客户端版本分发音乐数据包（kind=1）。
 * <p>
 * 必须同时支持两代客户端，因为客户端模组的版本由玩家自行安装，与 Minecraft 版本无关：
 * <ul>
 *   <li><b>4.x</b>：提供 {@code com.coloryr.allmusic.codec.MusicPacketCodec} + {@code AllMusicCore.packDo(MusicPack)}</li>
 *   <li><b>3.x</b>：没有 codec 包，只有 {@code AllMusicCore.packRead(ByteBuf)}（例如 1.21.4 上的 3.1.6）</li>
 * </ul>
 * 首次收到音乐数据包时探测一次并缓存结果；两代实现各自放在独立类里
 * （{@link PackV4} / {@link PackV3}），确保缺少某一方时不会影响另一方的类加载。
 * 注意：3.1.6 之前 {@code handleFrame} 只捕获 Exception，探测失败抛出的
 * {@code NoClassDefFoundError} 会直接终结读线程、表现为「点歌后与音乐服务器连接断开」。
 */
public final class PackRouter {

    private static final int UNKNOWN = 0;
    private static final int MODE_V4 = 1;
    private static final int MODE_V3 = 2;
    private static final int MODE_NONE = 3;

    private static int mode = UNKNOWN;
    private static boolean noneNotice;
    private static boolean readyNotice;
    private static boolean v3CompatNotice;
    /**
     * 3.x 兼容通道开关：null = 自动（检测到 3.x 客户端就开启），true/false = 玩家强制。
     * 见 ClientPrefs#client3xCompat
     */
    private static volatile Boolean compatOverride;

    private PackRouter() {
    }

    /** 由配置（config/amc10086.json 的 client3xCompat）或 /music compat 指令设置；null 表示自动 */
    public static void setCompatOverride(Boolean override) {
        compatOverride = override;
    }

    /** 分发一个音乐数据包（kind=1）的载荷 */
    public static void dispatch(byte[] frame) {
        int detected = detect();
        if (!readyNotice) {
            readyNotice = true;
            System.out.println("[AllmusicConnect] 已就绪：模组版本 " + modVersion()
                    + "，检测到 AllMusic 客户端世代 "
                    + (detected == MODE_V4 ? "4.x" : detected == MODE_V3 ? "3.x" : "未知（缺少兼容接口）"));
        }
        switch (detected) {
            case MODE_V4 -> PackV4.dispatch(frame);
            case MODE_V3 -> {
                if (!isCompatEnabled()) {
                    if (!v3CompatNotice) {
                        v3CompatNotice = true;
                        System.out.println("[AllmusicConnect] 检测到 AllMusic 客户端 3.x（老版）："
                                + "兼容通道已关闭，音乐数据包被忽略（/music compat true 开启）");
                    }
                    return;
                }
                PackV3.dispatch(frame);
            }
            default -> {
                if (!noneNotice) {
                    noneNotice = true;
                    System.out.println("[AllmusicConnect] 未找到兼容的 AllMusic 客户端接口"
                            + "（4.x 提供 codec 包，3.x 提供 AllMusicCore.packRead），音乐数据包已忽略");
                }
            }
        }
    }

    /** 兼容通道是否生效：自动模式下检测到 3.x 客户端即开启 */
    public static boolean isCompatEnabled() {
        Boolean override = compatOverride;
        return override == null || override;
    }

    /** 是否处于自动模式 */
    static boolean isCompatAuto() {
        return compatOverride == null;
    }

    /** 取本模组版本号（写进日志，便于确认实际加载的是哪一版） */
    private static String modVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("amc10086")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("未知");
        } catch (Throwable t) {
            return "未知";
        }
    }

    private static synchronized int detect() {
        if (mode != UNKNOWN) {
            return mode;
        }
        mode = MODE_NONE;
        try {
            Class.forName("com.coloryr.allmusic.codec.MusicPacketCodec");
            Class.forName("com.coloryr.allmusic.client.core.AllMusicCore")
                    .getMethod("packDo", Class.forName("com.coloryr.allmusic.codec.MusicPack"));
            mode = MODE_V4;
            return mode;
        } catch (Throwable ignored) {
            // 不是 4.x 客户端，继续探测 3.x
        }
        try {
            Class.forName("com.coloryr.allmusic.client.core.AllMusicCore")
                    .getMethod("packRead", ByteBuf.class);
            mode = MODE_V3;
            if (isCompatEnabled()) {
                System.out.println("[AllmusicConnect] 检测到 AllMusic 客户端 3.x（老版）：已"
                        + (isCompatAuto() ? "自动" : "") + "开启兼容通道"
                        + "（逐字歌词与播放时间同步不可用；/music compat false 可关闭，推荐升级到 4.x 客户端）");
            }
        } catch (Throwable ignored) {
            // 两个世代都不匹配，保持 MODE_NONE
        }
        return mode;
    }
}