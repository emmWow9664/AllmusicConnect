package com.allmusicconnect.mixin;

import com.allmusicconnect.net.TcpBridge;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 转发兜底：拦截玩家即将发往 MC 服务器的 {@code /music} 指令，把本模组不拥有的子指令转发给独立服务端。
 * <p>
 * 背景：当所连的 MC 服务器装有 AllMusic 插件（或玩家已装 AllMusic 客户端 3.x，其自带 {@code /music} 指令树）时，
 * 客户端 {@code /music} 指令树会被遮蔽，玩家点击聊天里 {@code /music select 1} 这类文本时，
 * 指令会被发往 MC 服务器而被插件拒绝。
 * <p>
 * 本 mixin 只做一件事：当独立服务端模式开启且已连接到独立服务端、且输入的是 {@code /music <其它子指令>}
 * （不是 {@code /musicconnect}、也不是 {@code /music} 树上已有的控制指令）时，把它转发给独立服务端并取消发送
 * （见 {@link TcpBridge#forwardFallback(String)}）。
 * <p>
 * 本模组的全部玩家操作统一走独立的 {@code /musicconnect} 指令树（没有任何状态门控，见
 * {@code AllmusicConnectClient#buildMusicConnectTree()}），因此这里不再做任何本地指令处理，
 * 也不会再有任何状态门控导致的「指令失效」。
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "sendCommand(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void allmusicconnect$forwardFallback(String command, CallbackInfo ci) {
        try {
            if (TcpBridge.forwardFallback(command)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 转发兜底异常时不影响指令正常发送
        }
    }
}