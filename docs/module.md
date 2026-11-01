# 模块说明

应用是面板，模块是干活的那一半。两者靠一份配置文件和一个 shell 脚本对接。

## 模块做什么 {#what}

`systemui_mount` 负责把一份 `MiuiSystemUI.apk` 送到 SystemUI 的安装位置并在开机时生效，同时保留一份原始版本用于回退。具体来说它管四件事：

- **放置**：把待测的 SystemUI 放到模块目录里约定的位置；
- **接管**：开机阶段让系统加载这份替换后的界面；
- **记录**：累计崩溃次数、判断是否需要救砖、记日志；
- **回退**：把救砖界面装回去并重启 SystemUI。

应用不做任何系统改动，它只做三件事：读状态、写配置、调 `webctl.sh`。

## 为什么分成两半 {#split}

因为「界面崩了」这件事发生时，应用本身很可能也没法正常操作——通知拉不下来、点不动、甚至进程被一起带走。所以真正能救命的逻辑必须在开机阶段就跑起来、不依赖应用存活，也就是模块里的守护脚本。

而配置和选择文件这些事，命令行做起来太难受，交给 Compose 界面。

::: tip 校验结果为什么写在模块里
救砖那一刻，读到它的是脚本，不是应用。所以音量键校验结果要写进模块配置而不是应用本地。
:::

## 目录结构 {#layout}

```text
/data/adb/modules/systemui_mount/
├── module.prop                    模块信息（应用读 author 判断是否配套）
├── MiuiSystemUI.apk               救砖界面：出事后换回的那一份
├── system_ext/priv-app/MiuiSystemUI/
│   └── MiuiSystemUI.apk           待测界面：当前换进去的那一份
├── config/settings.conf           运行参数（应用写的都在这）
├── webroot/webctl.sh              应用调用的入口脚本
└── action.sh                      模块动作脚本
```

日志默认落在 `/data/adb/systemui_mount_log`，可以在设置页改到 sdcard 上方便导出。

## 配置项 {#conf}

应用改设置本质就是改这个文件里的键值，改完会回读确认。手动编辑也可以，但改完建议重启一次让守护重新读取。

| 键 | 含义 |
| --- | --- |
| `LOG_MODE` | 运行模式：`0` 完全禁用 · `1` 仅日志 · `2` 日志 + 救砖 · `3` 增强救砖 |
| `LOG_DIR` | 日志目录，留空时用默认路径 |
| `CRASH_LIMIT` | 崩溃次数上限，达到即救砖 |
| `DEBUG` | `true` 时把脚本每一步写进 `config/debug.log` |
| `VOL_UP_DEV` / `VOL_DOWN_DEV` | 音量键对应的输入设备，由校验写入 |
| `VOLUME_CHECK_ASSESS` | 校验结论，`1` 表示真的测到过按键事件 |

::: danger 别手改 VOLUME_CHECK_ASSESS
它是「实测过」的凭据，直接填 1 只会让设置页显示已识别，真出事时按键依然没反应。
:::

## 兼容与限制 {#compat}

| 项 | 说明 |
| --- | --- |
| 系统 | Android 15（API 35）及以上；HyperOS / MIUI 类系统，SystemUI 包名为 `com.android.systemui` |
| 架构 | arm64-v8a（应用侧同样只提供 arm64 包） |
| Root | Magisk / APatch / KernelSU，需要能执行 `su` |
| 已知限制 | 系统大版本升级后需要重新安装模块并重做音量键校验；不同机型的 SystemUI 目录可能不同，以模块实际安装位置为准 |

## 卸载与回滚 {#remove}

1. 先在应用里停止守护，确认「待测界面」不再是半坏状态；
2. 在 Root 管理器里停用或删除模块，重启；
3. 重启后系统会回到原始 SystemUI。若界面异常，用 Recovery 删除模块目录再启动。

只卸载应用不动模块也没问题，配置会留在模块里，下次装回应用仍然生效。
