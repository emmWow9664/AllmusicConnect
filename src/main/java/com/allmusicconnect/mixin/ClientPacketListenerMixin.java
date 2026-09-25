package com.allmusicconnect.mixin;

import com.allmusicconnect.net.TcpBridge;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截客户端发往 MC 服务器的指令。
 * <p>
 * 背景：当所连的 MC 服务器装有 AllMusic 服务端插件时，服务端的 /music 命令树会覆盖客户端注册的同名
 * 客户端指令，玩家输入 /music connect ... 会被发往服务器并被插件拒绝（"你没有权限执行这个操作"），
 * 同时 tab 补全里也看不到本模组的子指令。
 * <p>
 * 这里在指令真正发送前拦截，把本模组拥有的 /music 子指令改为本地处理
 * （见 {@link TcpBridge#handleLocal(String)}），从而在任何服务器上都能正常工作。
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "sendCommand(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void allmusicconnect$handleLocalMusicCommand(String command, CallbackInfo ci) {
        try {
            if (TcpBridge.handleLocal(command)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 本地处理异常时不影响指令正常发送
        }
    }
}