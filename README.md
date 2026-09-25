*本项目由AI生成
# AllMusicConnect

项目仓库：<https://github.com/emmWow9664/AllmusicConnect>

AllMusic 客户端增强模组（Fabric，客户端侧）：通过 `/music connect <ip> [端口]` 连接第三方独立音乐服务器，无需在服务器上安装 AllMusic 插件即可点歌、听歌。

## 功能

- `/music connect <ip> [端口]` —— 连接第三方独立音乐服务器（如 [AllmusicStandaloneServer](https://github.com/emmWow9664/AllmusicStandaloneServer)）；端口省略时使用默认端口 `5223`
- `/music disconnect` —— 断开当前连接（连接过程中执行可取消本次连接）
- `/music status` —— 查看连接状态
- `/music autoconnect <true|false>` —— 是否在进入服务器时自动连接上次连接的独立音乐服务器（默认关闭，关闭时用 `connect` 手动连接）；开关与上次地址保存在 `config/amc10086.json`
- `/music standalone [true|false]` —— 独立服务端模式总开关（默认开启；不带参数时显示当前状态）。**关闭后会立即断开独立音乐服务器，本模组不再转发指令、也不介入 Tab 补全**，`play` / `stop` / `search` / `list` 等交给所连 MC 服务器上的 AllMusic 插件处理
- 关闭状态下本模组自身的控制指令仍然可用（插件的指令表里没有它们，交给插件只会被当成点歌）：`/music connect <ip> [端口]` 会**自动重新开启独立服务端模式**并连接，`disconnect` / `status` / `autoconnect` 会提示当前处于关闭状态，`standalone true` 也能直接开启
- `/music compat [true|false|auto]` —— AllMusic 客户端 **3.x（老版）兼容通道**开关（**默认 `auto`**：检测到 3.x 客户端时自动开启，4.x 客户端不受影响；不带参数显示状态）。详见下文「AllMusic 客户端 3.x 兼容」
- `/music <其它指令>` —— 将指令转发到独立音乐服务器执行（如 `play`、`stop`、`search` 等，带 Tab 补全）
- 指令冲突免疫：即使所连的 MC 服务器装有 AllMusic 服务端插件，上述指令依然可用（见下文「指令冲突处理」）
- 连接在后台线程建立（5 秒连接超时），不会卡住游戏
- 退出服务器 / 退出游戏时自动断开连接

## AllMusic 客户端 3.x 兼容

本模组需要 **AllMusic 客户端 4.x**（提供 `com.coloryr.allmusic.codec` 包与 `AllMusicCore.packDo`）。
某些 Minecraft 版本上官方只有 3.x 客户端（例如 1.21.4 常见的 `AllMusic_Client-3.1.6`），它与 4.x 有两处不同：

| | 3.x | 4.x |
| --- | --- | --- |
| 编解码 | 无 `codec` 包，入口是 `AllMusicCore.packRead(ByteBuf)`（客户端内部解码） | `MusicPacketCodec.decode()` + `AllMusicCore.packDo(MusicPack)` |
| 类型表 | LYRIC(0) INFO(1) LIST(2) PLAY(3) IMG(4) STOP(5) CLEAR(6) POS(7) HUD_DATA(8) | LYRIC(0) LYRIC_KTV(1) INFO(2) PLAY(3) … HUD_DATA(8) TIME(9) |
| HUD 数据 | `SaveOBJ`（`list/lyric/info/pic`，方向字段 `dir`） | `HudPosObj`（`lyric/info/state/pic`，方向字段 `pos`） |

模组会按运行时安装的客户端自动识别世代（`PackRouter`），并提供翻译层（`PackV3`）：

- 重写类型字节（4.x `INFO=2` → 3.x `INFO=1`）
- 把 4.x 的 HUD 位置 JSON 翻译成 3.x 的 `SaveOBJ`（每个元素都补齐合法的 `dir`，否则 3.x 渲染时会 NPE 崩溃）
- 3.x 无对应协议的 `LYRIC_KTV`（逐字歌词）与 `TIME`（播放时间同步）会被跳过

兼容通道**默认 `auto`**：模组在收到第一个音乐数据包时探测客户端世代，3.x 自动开启、4.x 不受影响；
也可以用 `/music compat true|false|auto` 强制开关，或改 `config/amc10086.json` 的 `client3xCompat`（留空 = 自动）。

3.x 客户端自身的限制（模组无法绕过）：解码器只有 flac / ogg / mp3，
所以音乐接口返回 **m4a（AAC）** 的歌曲会提示「[AllMusic客户端]不支持这样的文件播放」且无声——4.x 客户端多了 m4a 解码器。

> 若服务端用的是 netapi，可在 `allmusic_server/api/netapi.json` 里把编码改成 mp3（音质损失很小，`exhigh` + `mp3` 通常即 320k MP3），
> 改完重启服务端，3.x 客户端即可正常播放：
> ```json
> { "level": "exhigh", "encodeType": "mp3" }
> ```
> （`encodeType: "aac"` 会返回 `.m4a`，正是 3.x 无法解码的原因）
推荐直接升级到 4.x 客户端以获得完整功能（4.x 的服务端/客户端源码见 <https://github.com/coloryr/AllMusic>，
3.x 客户端源码见 <https://github.com/Coloryr/AllMusic_Client>）。

## 指令冲突处理

MC 服务器若安装了 AllMusic 服务端插件，服务端的 `/music` 命令树会覆盖客户端注册的同名指令：Tab 补全里
看不到本模组的 `connect`，手动输入 `/music connect <ip> [端口]` 会被发往服务器并被插件拒绝
（提示「你没有权限执行这个操作」）。

为此模组通过 mixin 拦截 `ClientPacketListener#sendCommand`，在指令真正发往 MC 服务器之前本地处理；
并在服务端指令树到达时（`ClientPacketListener#handleCommands`）把本模组的 `/music` 子指令注册进
同一个指令调度器（brigadier 的同名节点合并），使 Tab 补全能显示本模组的子指令：

| 输入的指令 | 行为 |
| --- | --- |
| `/music standalone [true\|false]` | 本地开关独立服务端模式（关闭后不再拦截下列指令） |
| `/music connect <ip> [端口]` | 本地连接独立音乐服务器，不发送给 MC 服务器 |
| `/music disconnect` | 本地断开连接 |
| `/music status` | 本地显示连接状态 |
| `/music autoconnect <true\|false>` | 本地开关自动连接 |
| `/music <其它子指令>` | **已连接**音乐服务器时转发给它；**未连接**时放行给 MC 服务器（保留服务端 AllMusic 插件的原有行为） |

**关闭独立服务端模式后**（`/music standalone false`）：本模组不再转发指令、也不再向服务器的指令树注入
Tab 补全项；`play` / `stop` / `search` / `list` 等（AllMusic 插件自己的指令）原样发给 MC 服务器。
本模组自身的控制指令仍然由本地处理——`/music connect <ip> [端口]` 会自动重新开启独立服务端模式并连接，
`disconnect` / `status` / `autoconnect` 会提示当前处于关闭状态，`standalone [true|false]` 仍可开关。

相关实现：`src/main/java/com/allmusicconnect/mixin/ClientPacketListenerMixin.java`、
配置 `src/main/resources/amc10086.mixins.json`（在 `fabric.mod.json` 的 `mixins` 中注册）。
上述两个注入点（`sendCommand(String)`、`handleCommands(ClientboundCommandsPacket)`）在
1.21 ~ 26.2 全部受支持版本上签名一致，无需按版本分支。

## 依赖

| 依赖 | 要求 |
| --- | --- |
| Fabric Loader | >= 0.19.3 |
| Fabric API | 任意版本 |
| Minecraft | 1.21 ~ 26.2（按构建版本） |
| AllMusic Client | 任意版本（`allmusic_client`，运行时提供真实编解码类） |

## 构建

按 Minecraft 版本构建（默认 `26_2`）：

```bash
gradlew build                       # 默认 26.2
gradlew build -Pmc=1_21_11          # 构建 1.21.11
gradlew build -Pmc=26_1_2           # 构建 26.1.2
```

支持的目标版本：`1_21`、`1_21_1` ~ `1_21_11`、`26_1`、`26_1_2`、`26_2`。

构建产物输出到 `build/libs`，命名格式：`AllmusicConnect-<版本>-mc<MC版本>.jar`。

> 说明：编译期使用 `stubs` 桩类与 `libs/allmusic-codec.jar` 的编解码类（不打包进最终 jar），运行时由 AllMusic Client 模组提供。

## 兼容层

按 Minecraft 版本世代划分 4 个适配层：

- `legacy` —— 1.21 ~ 1.21.5
- `compat_1_21_6` —— 1.21.6 ~ 1.21.8
- `compat_1_21_11` —— 1.21.9 ~ 1.21.11
- `modern` —— 26.x

> 组件 JSON 兼容：服务端（AllmusicStandaloneServer）打包的 adventure 4.21+ 会把点击/悬停样式序列化成
> 新写法 `click_event` / `hover_event`，只有 **1.21.5 及以上**的 Minecraft 认识它；
> 1.21 ~ 1.21.4 会把未知字段直接忽略，导致聊天里的 `[点我选择]` 显示正常但点不动。
> `legacy` 层因此在解析前把新写法降级为旧写法（`clickEvent{action,value}` / `hoverEvent{action,contents}`，
> 见 `src/legacy/java/com/allmusicconnect/net/LegacyComponentJson.java`）。

## 协议与数据

- 模组 ID：`amc10086`
- 环境：`client`
- 许可证：GPL-3.0
- 作者：emmWow9664、DeepseekV4Flash
