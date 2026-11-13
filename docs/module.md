# 模块说明

模块是挂载apk的工具<br>应用**只作为**基础工具测试apk
## 模块做什么 {#what}

`模块` 负责把一份 `MiuiSystemUI.apk` 在开机时 将我们修改后的 SystemUI 放在系统分区：

- **放置**：把待测的 SystemUI 放到模块目录里约定的位置 
- **接管**：开机阶段让系统加载这份替换后的界面 
- **记录**：累计崩溃次数 判断是否需要救砖 记日志 
- **回退**：把救砖界面装回去并重启 SystemUI 

应用不做任何系统改动 它只做三件事：读状态 写配置 调 `webctl.sh` 

## 目录结构 {#layout}

```
/data/adb/modules/systemui_mount/
├── module.prop                    模块信息（应用读 author 判断是否配套）
├── MiuiSystemUI.apk               救砖界面：出事后换回的那一份
├── system/system_ext/priv-app/MiuiSystemUI/
│   └── MiuiSystemUI.apk           待测界面：当前换进去的那一份
├── config/settings.conf           运行参数（应用写的都在这）
├── config/functions.sh            脚本函数（升级时整体替换 不碰用户配置）
├── scripts/                       应用调用的脚本目录
│   ├── webctl.sh                  应用调用的入口脚本
│   ├── check.sh                   音量键校验
│   └── watch.sh                   常驻救砖守护
└── action.sh                      模块动作脚本
```

日志默认不记录 需要先在设置页选一个日志目录

## 兼容与限制 {#compat}

| 项 | 说明 |
| --- | --- |
| 系统 | Android 15 及以上 SystemUI 包名为 `com.android.systemui` |
| 架构 | arm64-v8a |
| Root | 主流都适配 |

## 卸载与回滚 {#remove}

1. 在 Root 管理器里停用或删除模块 重启 
2. 重启后系统会回到原始 SystemUI 若界面异常<br>千万不要用 Recovery 删除模块目录

只卸载应用不动模块也没问题 配置会留在模块里 下次装回应用仍然生效 

## 异常处理方法 {#fatal}

在 Recovery 内将系统分区的系统界面替换进模块对应目录

