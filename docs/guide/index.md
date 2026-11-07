# 使用教程

从装模块到换第一个界面 正常十几分钟能走完

一句话流程：

1. 下载模块 zip（别解压）
2. 把手机上现在用的 `MiuiSystemUI.apk` 放进 zip 里的 `system/system_ext/priv-app/MiuiSystemUI/`
3. 刷进去 重启 装应用 点开始测试

## 1 · 装模块 {#step1}

### 1.1 下载

在 [Release 页面](https://github.com/XKspeed/HyperMount/releases/latest) 下模块压缩包 文件名形如 `systemui_mount-v1.5.5.zip` **别解压** 

### 1.2 先看你的管理器要不要装「元模块」

这一步只影响 KernelSU / APatch 用户 Magisk 可以跳过 

| 管理器 | 谁负责挂载 | 你要做什么 |
| --- | --- | --- |
| Magisk | 自带 magic mount | 跳过本节 |
| KernelSU | 需要元模块 | 装一个 |
| APatch | 需要元模块 | 装一个 |

常用的四个：

| 元模块 | 仓库 | 说明 |
| --- | --- | --- |
| Mountify | [GitHub](https://github.com/backslashxx/mountify) | 通用 KernelSU / APatch 都能用 |
| Hybrid Mount | [GitHub](https://github.com/Hybrid-Mount/meta-hybrid_mount) | 混合挂载 兼容性取向 |
| Magic Mount-rs | [GitHub](https://github.com/Tools-cx-app/meta-magic_mount-rs) | Rust 重写 行为尽量贴近 Magisk |
| NoMount | [GitHub](https://github.com/maxsteeel/nomount) | **仅支持 5.10 以上 GKI 内核** 老内核别选 |

### 1.3 先把界面塞进压缩包 {#put-apk}

在刷模块之前 把 `MiuiSystemUI.apk` 放进 zip 里 

**第一步 拿到文件** <br>用 MT 管理器打开 `/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk` 

**第二步 塞进 zip** <br>用**另一侧窗口**打开模块 zip 以下目录

```
system/system_ext/priv-app/MiuiSystemUI/
```

把 `MiuiSystemUI.apk` 放进zip
```
systemui_mount-v1.5.5.zip
├── META-INF/
├── config/
├── system/
│   └── system_ext/
│       └── priv-app/
│           └── MiuiSystemUI/
│               └── MiuiSystemUI.apk   ← 就是它
├── customize.sh
├── module.prop
└── service.sh
```

**这份文件同时就是你后面的救砖包**<br>先别急着删除 等会还得用它当救砖界面

### 1.4 刷入模块

这步应该不用我说了

### 1.5 重启后确认

重启后回模块列表 应该能看到：

- 名称：`日志版挂载界面`
- 包名/ID：`systemui_mount`
- 作者：`XKspeed`
- 描述里有时间和「状态」

描述里如果写着「待重启」 说明你还没重启

### 1.6 挂载没生效怎么排查 {#mount-fail}

看 [@Mari0us](coolmarket://www.coolapk.com/u/578241) 写的[这篇](coolmarket://www.coolapk.com/feed/73074049) <br>讲的是挂载不生效的解决方法：

## 2 · 选择运行模式 {#step2}

**这是最容易漏的一步 也是为啥「点不了开始测试」的原因** 

模块默认是「完全禁用」——装好但什么都不做 

去**设置**页自行探索(懒得写了)

## 3 · 问题反馈

对于没有在群内的朋友 请前往[issues反馈](https://github.com/XKspeed/HyperMount/issues)<br>对于在群内的 直接 @XKspeed

## 4 · 最后 {#end}

这套东西的价值不在于「能换界面」 而在于**换坏了能回来** 

所以每次折腾之前 花两分钟确认三件事：

1. 救砖包是能开机的那一份 
2. 音量键校验过了 
3. 运行模式是「日志 + 救砖」 

三条都在 你就有退路 少一条 就是在赌 
