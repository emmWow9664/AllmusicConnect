*本项目由AI生成
# AllMusicConnect

AllMusic 客户端增强模组（Fabric，客户端侧）：通过 `/music connect <ip> <端口>` 连接第三方独立音乐服务器，无需在服务器上安装 AllMusic 插件即可点歌、听歌。

## 功能

- `/music connect <ip> <端口>` —— 连接第三方独立音乐服务器（如 [AllmusicStandaloneServer](https://github.com/emmWow9664/AllmusicStandaloneServer)）
- `/music disconnect` —— 断开当前连接
- `/music status` —— 查看连接状态
- `/music <其它指令>` —— 将指令转发到独立音乐服务器执行（如 `play`、`stop`、`search` 等，带 Tab 补全）
- 指令冲突免疫：即使所连的 MC 服务器装有 AllMusic 服务端插件，上述指令依然可用（见下文「指令冲突处理」）
- 退出服务器 / 退出游戏时自动断开连接

## 指令冲突处理

MC 服务器若安装了 AllMusic 服务端插件，服务端的 `/music` 命令树会覆盖客户端注册的同名指令：Tab 补全里
看不到本模组的 `connect`，手动输入 `/music connect <ip> <端口>` 会被发往服务器并被插件拒绝
（提示「你没有权限执行这个操作」）。

为此模组通过 mixin 拦截 `ClientPacketListener#sendCommand`，在指令真正发往 MC 服务器之前本地处理：

| 输入的指令 | 行为 |
| --- | --- |
| `/music connect <ip> <端口>` | 本地连接独立音乐服务器，不发送给 MC 服务器 |
| `/music disconnect` | 本地断开连接 |
| `/music status` | 本地显示连接状态 |
| `/music <其它子指令>` | **已连接**音乐服务器时转发给它；**未连接**时放行给 MC 服务器（保留服务端 AllMusic 插件的原有行为） |

相关实现：`src/main/java/com/allmusicconnect/mixin/ClientPacketListenerMixin.java`、
配置 `src/main/resources/amc10086.mixins.json`（在 `fabric.mod.json` 的 `mixins` 中注册）。
该注入点在 1.21 ~ 26.2 全部受支持版本上签名一致（`sendCommand(String)`），无需按版本分支。

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

## 协议与数据

- 模组 ID：`amc10086`
- 环境：`client`
- 许可证：GPL-3.0
- 作者：emmWow9664、DeepseekV4Flash
