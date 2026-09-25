package com.allmusicconnect.mixin;

import com.allmusicconnect.AllmusicConnectClient;
import com.allmusicconnect.net.TcpBridge;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截客户端发往 MC 服务器的指令，并把本模组的 /music 子指令补进服务端指令树。
 * <p>
 * 背景：当所连的 MC 服务器装有 AllMusic 服务端插件时，服务端的 /music 命令树会覆盖客户端注册的同名
 * 客户端指令，玩家输入 /music connect ... 会被发往服务器并被插件拒绝（"你没有权限执行这个操作"），
 * 同时 tab 补全里也看不到本模组的子指令。
 * <p>
 * 两个处理：
 * <ul>
 *   <li>指令真正发送前拦截，把本模组拥有的 /music 子指令改为本地处理
 *       （见 {@link TcpBridge#handleLocal(String)}），从而在任何服务器上都能正常工作；</li>
 *   <li>服务端指令树到达后（handleCommands），把本模组的 /music 子指令注册进同一个调度器，
 *       由 brigadier 合并进服务端的 /music 节点，使 tab 补全能显示 connect / disconnect /
 *       status / autoconnect 等子指令（Fabric 客户端指令的合并逻辑在服务器已有同名根指令时
 *       不会把子节点并入已有的 /music 节点，故这里主动补一次）。</li>
 * </ul>
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    /**
     * MC 客户端的指令调度器（由服务端指令树构建）。
     * 1.21 ~ 1.21.5 的泛型参数是 SharedSuggestionProvider、1.21.6+ 是 ClientSuggestionProvider，
     * 擦除后类型相同，这里用原始类型以同时适配全部支持版本。
     */
    @Shadow
    @Final
    @SuppressWarnings("rawtypes")
    private CommandDispatcher commands;

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

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "handleCommands", at = @At("RETURN"))
    private void allmusicconnect$mergeMusicCompletions(ClientboundCommandsPacket packet, CallbackInfo ci) {
        try {
            // register 内部走 CommandNode#addChild：同名节点会合并子节点，
            // 因此服务器已存在的 /music 节点会获得本模组的子指令
            commands.register(AllmusicConnectClient.buildMusicTree());
        } catch (Throwable ignored) {
            // 补全注入失败不影响指令本身
        }
    }
}