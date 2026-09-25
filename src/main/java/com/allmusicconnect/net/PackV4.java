package com.allmusicconnect.net;

import com.coloryr.allmusic.client.core.AllMusicCore;
import com.coloryr.allmusic.codec.MusicPack;
import com.coloryr.allmusic.codec.MusicPacketCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;

/**
 * AllMusic 客户端 <b>4.x</b> 数据通道：用 codec 包解码，再交给 {@code AllMusicCore.packDo} 播放。
 * <p>
 * 只有运行时确认客户端提供 {@code com.coloryr.allmusic.codec.MusicPacketCodec} 时才会被加载
 * （见 {@link PackRouter}），因此单独成类——3.x 客户端缺少 codec 包时不会触碰这个类。
 */
final class PackV4 {

    private PackV4() {
    }

    static void dispatch(byte[] frame) {
        ByteBuf buf = Unpooled.wrappedBuffer(frame);
        MusicPack pack = MusicPacketCodec.decode(buf);
        Minecraft.getInstance().execute(() -> {
            try {
                AllMusicCore.packDo(pack);
            } catch (Throwable t) {
                System.out.println("[AllmusicConnect] 播放数据包处理出错：" + t);
                t.printStackTrace();
            }
        });
    }
}