package com.coloryr.allmusic.client.core;

import com.coloryr.allmusic.codec.MusicPack;

/**
 * 编译期桩类：运行时由 AllMusic 客户端模组提供真正的实现。
 * 本文件只用于编译本模组，不会被打入模组 jar。
 * <p>
 * 这里只声明 4.x 的入口 {@code packDo(MusicPack)}；3.x 客户端的
 * {@code packRead(io.netty.buffer.ByteBuf)} 由 {@code com.allmusicconnect.net.PackV3}
 * 通过反射调用（该源码集没有 netty 依赖，且反射能避免缺少方法时的类加载问题）。
 */
public class AllMusicCore {
    public static void packDo(MusicPack pack) {
    }
}
