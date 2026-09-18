*本项目由AI生成，并同步到仓库
# AllMusicConnect

AllMusic 客户端增强模组（Fabric，客户端侧）：通过 `/music connect <ip> <端口>` 连接第三方独立音乐服务器，无需在服务器上安装 AllMusic 插件即可点歌、听歌。

## 功能

- `/music connect <ip> <端口>` —— 连接第三方独立音乐服务器（如 [AllmusicStandaloneServer](https://github.com/emmWow9664/AllmusicStandaloneServer)）
- `/music disconnect` —— 断开当前连接
- `/music status` —— 查看连接状态
- `/music <其它指令>` —— 将指令转发到独立音乐服务器执行（如 `play`、`stop`、`search` 等，带 Tab 补全）
- 退出服务器 / 退出游戏时自动断开连接

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
