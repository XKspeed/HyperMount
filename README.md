<div align="center">

# HyperMount

一个 **SystemUI 界面替换与自动救砖** 的原生控制端 UI 基于 [Miuix](https://github.com/compose-miuix-ui/miuix)（小米 HyperOS 风格 Compose 组件库）

![Android](https://img.shields.io/badge/Android-15%2B%20(API%2035)-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20Miuix-4285F4)
![Root](https://img.shields.io/badge/Root-Required-D32F2F)
![License](https://img.shields.io/badge/License-GPL--3.0-blue)

<br>

围绕「**备好救砖界面 → 换入待测界面 → 崩了自动换回 → 继续正常使用**」这条主链路设计：放胆把会崩的界面换进去试 出事自动换回可用的那一版 不再怕 SystemUI 黑屏卡死

</div>

---

### 📌 基本信息

| 项目 | 说明 |
| :--- | :--- |
| **当前版本** | `1.2.0`（`versionCode 4`） |
| **最低系统** | **Android 15（API 35）** 及以上 |
| **运行环境** | 需 **Root** 与同名的 Magisk / APatch / KernelSU 模块配套使用 |

---

## ✨ 功能

### 救援流程
- **救砖界面**：选一个确认可用的 SystemUI 界面留作后路 出事时换回它
- **待测界面**：选要实测的那一版界面 换进去观察
  - 选文件直接拉起 MT 管理器（正版优先 没装再走共存版） 不经过系统的安全访问
  - 两个版本都没装时提示未找到 MT 管理器 不会退回别的选择器
  - 兼容下载器留下的 `apk.1` 后缀 落地时自动还原成 `apk`
  - 复制完成先读清单校验包名 不是 `com.android.systemui` 的一律拒收
- **开始 / 停止**：一键接管与收手 随时可以喊停

### 自动恢复
- **崩溃阈值**：累计多少条 fatal 日志后自动换回救砖界面（可调）
- **卡死检测**：日志疯长、堆栈溢出同样判定为卡死 —— 不必等崩溃次数攒够
  - 这类崩溃单次就可能刷出几万行堆栈 但频率低 永远达不到阈值 只能靠它兜住
- **等待超时**：替换界面后等 SystemUI 重启的秒数（可调）
- **音量键救砖**：音量+ / 音量- 直接触发换回 界面已经点不动时也能救
- **屏蔽系统救援**：把 AMS 的崩溃次数限制拉高 避免系统抢在前面杀掉 SystemUI
  - 开启**增强救砖**时强制打开 且不可关闭 —— 否则系统先崩到上限 自动救砖根本没机会执行
  - 日志 + 救砖模式（不开增强）自动把崩溃上限设成 **30** 比系统默认的 12 宽 音量键才来得及按

### 音量键校验
- 按内核能力位识别音量键设备（KEY_VOLUMEUP / KEY_VOLUMEDOWN） 逐个实测按键是否真的产生事件
  - 声明了上下键 ≠ 真能按出事件 必须实测
- 识别结果写入配置供脚本优先读取 不再猜 event 编号

### 主页（独立测试流程）
- 版式照搬「界面」页（没有设备区） 但**不依赖模块守护** 自己往 `/data/local/tmp` 丢一份脚本跑
- 流程照「双兼容版 logcat.sh」：后台 `pm install` 待测 apk → 等「等待时长」→ 抓 crash 日志
  自动替换打开时再盯着日志大小 超过 5KB 就把救砖 apk 装回去并重启 SystemUI
- **不校验包名** 测的可以不是 SystemUI 只拦「待测 apk 和救砖 apk 是同一个文件」
- **自动替换**：关掉也能正常测试 只是少了崩溃后换回救砖 apk 这一步
- 选完的 apk 落在 App 私有目录 `files/apk/` 只有重新选才覆盖
  真正开跑时才拷到 `/data/local/tmp`（不受限目录）用完即删
- 主页这两份 apk 和「界面」页选的那份互不影响
- **配置项**：点开是底部弹窗 里面是目标包名、等待时长（0 到 20 秒）、抓取时长（5 到 60 秒）
- 运行日志实时回显在状态卡里 救砖次数也在这里数
- 日志落在设置页「日志目录」指定的目录里 `logcat_<包名>_<时间>.log`（设置页运行模式带日志时才出现 点卡片直接改路径）
- 目标包名与救砖 / 待测 apk 的包名**无关** 测的是插件时崩溃日志会记在宿主包名里 所以可以随便填
- 所有滑动条都支持点标题区域弹输入框 直接敲数值
- **崩溃日志**：独立的崩溃记录页
- **调试日志**：可开关 在 `config/debug.log` 里记录每一步动作

### 检查更新
- 启动时后台读仓库根目录的 `update.json`（10 秒超时 失败静默） 可在关于页关掉「自动检查更新」
- 这份清单由发版工作流自动维护 版本号、下载地址、变更说明都不用手工改
- 有新版就弹提示 主页版本号右侧出现绿色「有新版本可用」胶囊 点一下直达 release
- 设置页「关于」分组下面的「检查更新」可手动复查 点一下先弹「正在检查更新」 期间整行变灰不可重复点
- 同一张卡里的「自动检查更新」开关可以关掉启动时那次后台检查

### 界面
- Miuix 界面 支持深色模式 顶栏是可收缩的大标题
- **底栏三页**：界面 / 主页 / 设置 只能通过底栏切换 左右滑动不再翻页 避免误触
- **主题**：一张卡里放主题模式（跟随系统 / 浅色 / 深色）· 莫奈取色 · 种子色（含调色风格与色彩规范）
- **模糊强度**：玻璃控件的模糊程度可调 拖动即时生效
- **预测性返回手势**：根据手势预览上一级 关闭后拖动时页面不动 松手才返回 圆角按系统圆角裁切
- **底栏模糊**：关闭后退化为磨砂胶囊 不做折射

### 关于
- 入口在设置页最下面 点进去是二级页
- 应用版本（含 `versionCode`）、模块版本、最低系统
- **第三方许可证**：开源库清单与许可证 独立二级页

---

## 🛠️ 技术栈

| 用途 | 技术 |
| :--- | :--- |
| 语言 | Kotlin 2.4.20 |
| 构建 | AGP 9.4.0 |
| UI | Jetpack Compose + Miuix 0.9.4（HyperOS 风格） |
| 导航 | Miuix Nav（NavDisplay + 预测性返回） |
| 玻璃 / 模糊 | Miuix Blur（AGSL 渐进模糊）+ kyant Backdrop 1.0.5 |
| Root 命令 | libsu 6.0.0（常驻 shell 避免每条命令 fork 一次 `su`） |
| 序列化 | KotlinX Serialization 1.7.3 |

---

## 🚀 构建

### Android Studio

1. 用 Android Studio 打开项目根目录
2. 等待 Gradle 同步完成（首次需下载依赖）
3. Run ▶ 安装到设备（Android 15+ 且已 Root）

### 命令行

> 需要 JDK 21 与 Android SDK

```bash
export JAVA_HOME=<jdk21 路径>
export ANDROID_HOME=<sdk 路径>

./gradlew :app:assembleDebug      # 产物 app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # 需自备签名密钥
```

### 云编译

仓库内置两条 GitHub Actions 工作流：

| 工作流 | 触发 | 产物 |
| :--- | :--- | :--- |
| `build.yml` | 手动触发 | debug APK |
| `release.yml` | 手动一键发布 | Release APK（R8 混淆 + 固定密钥 V2 签名） |

两条工作流**都只在 Actions 页面手动触发** 不挂 push / tag 触发 —— 免得每次提交都产一版包或发一个 Release

Release 工作流从仓库 Secrets 读取签名密钥 密钥不进入代码库 流程为：

1. 解析版本号：填 `1.0.1` 即自动把 `versionName` 改成它、`versionCode` 递增 1；留空则沿用现有版本不动
2. 从 Secrets 解出密钥并校验（内容/密码/别名分别校验 出错直接指出是哪一项）
3. R8 构建并签名
4. 校验产物确实签上了名（没签上直接失败 不会发布）
5. 归档日志（`## latest` → `## v<版本> 更新` 并插入新的空 latest）连同版本号一起提交、打标签并推送
6. 发布 GitHub Release 附带 APK 与模块 zip 说明里同时放**应用段**和**模块段**
7. **自动改写仓库根的 `update.json`**：版本号取构建产物、下载地址拼本次 tag 的资产、应用日志与模块日志分别写进 `changelog` 和 `moduleChangelog` 然后提交回 `main`

---

## 🔐 签名与升级

- 正式版使用固定密钥签名 因此后续版本可以直接覆盖安装升级
- 升级前提：`versionCode` 必须递增、`applicationId` 不可变更
- ⚠️ 更换签名密钥会导致已安装用户无法覆盖升级（需卸载重装） 请勿随意更换

---

## 📝 发版与更新日志

更新日志有两个文件 规则完全一样：

| 文件 | 内容 |
| :--- | :--- |
| [`update.md`](update.md) | App 的更新日志 |
| [`module/main/update.md`](module/main/update.md) | 模块的更新日志 |

**平时只往 `## latest` 里加。** 文件顶部永远是一个 `## latest` 段 每改一处就往这段下面加一条 不用管版本号 也不用改标题：

```markdown
## latest

- 这次改了什么
- 又改了什么

## v1.4.0 更新          ← 已发布的历史 自动往下沉
```

**发版时工作流自动归档**：把 `## latest` 就地改名成 `## v<你填的版本号> 更新` 再在最顶上插入一个新的空 `## latest` 留给下一轮。带来的效果：

- 不用手动改标题 也就不会再出现「发的是 1.4.0 说明却还是 1.3.0」这种事故
- `## latest` 空着会直接让工作流失败 逼你先写日志（重发同一版本除外 会自动复用已归档的段落）
- 模块这版没改动时 `module/main/update.md` 的 latest 可以空着 工作流跳过模块段 不阻断 App 发版

Release 说明会拼成两段：

```markdown
### 应用 · v1.5.0
- ...

### 模块 · v1.5.5
- ...
```

写进 `update.json` 时也分开：应用段进 `changelog` 模块段进 `moduleChangelog`（关于页的模块更新卡读的就是后者）。

发版两步：① 确认两个 `update.md` 的 latest 写好了；② Actions 里点 Release 填 **App 版本号**和**模块版本号**（都可以留空 = 沿用现有版本）。剩下交给工作流：归档日志 → 改两处版本号 → 构建签名 APK + 打模块 zip → 发**一个** Release（两个文件放一起）→ 把版本号 / 下载地址 / 应用日志 / 模块日志写进仓库根的 [`update.json`](update.json) 并提交回 `main`。

本地手动归档（不走 Actions 时）：

```sh
sh scripts/changelog.sh finalize update.md 1.5.0            # ## latest → ## v1.5.0 更新
sh scripts/changelog.sh finalize module/main/update.md 1.5.5
sh scripts/changelog.sh extract update.md 1.5.0             # 看归档后的段落
sh scripts/changelog.sh has update.md latest                # latest 有没有内容
```

版本代号不用手填：新版本号比现有**高**就 +1 比现有**低或完全一样**就不动 数字段相同但名字不同（`1.3.0` → `1.3.0-hotfix1`）也算新版本 同样 +1

模块包由 [`module/打包.sh`](module/打包.sh) 打 工作流和本地跑的是同一条命令：

```sh
sh module/打包.sh 1.6.0     # 本地发版 写回 module/main/module.prop
sh module/打包.sh --keep    # 只打包当前版本 不改源码（CI 用）
```

版本号支持带后缀 例如 `1.2.0-hotfix1` 会打出 tag `v1.2.0-hotfix1` 并把 `versionName` 原样写进 `build.gradle.kts`（`versionCode` 按上面的规则走）`update.md` 顶部照样写 `## latest` 就行 归档后标题会自动变成 `## v1.2.0-hotfix1 更新` App 端只比 `versionCode` 所以 hotfix 能正常推送

---

## 📁 项目结构

```text
app/src/main/java/com/xkspeed/HyperMount/
├── ui/                   界面层
│   ├── App.kt            导航与底栏（界面 / 主页 / 设置）
│   ├── HomePage.kt       界面页：救砖 / 待测界面选择、开始停止
│   ├── MainPage.kt       主页：安装待测 apk · 抓 crash 日志 · 崩溃自动救砖
│   ├── SettingsPage.kt   运行参数（模式 · 超时 · 阈值）· 关于入口
│   ├── UiSettingsPage.kt 界面与主题
│   ├── LogPage.kt        日志
│   ├── CrashLogPage.kt   崩溃日志
│   ├── VolumeCheckPage.kt  音量键校验
│   ├── Picker.kt         MT 调用 文件落地与包名校验（主页只比对两份 apk）
│   ├── AboutPage.kt      关于
│   ├── ThirdPartyLicensesPage.kt  第三方许可证
│   └── component/        玻璃与背景组件
├── core/                 崩溃日志 · root 命令 · 配置读写 · 更新检查 · 测试脚本与 apk 副本
└── ...

module/
├── main/                 模块源码（action.sh · webroot · config · module.prop）
└── 打包.sh               打包模块 zip（本地与 CI 同一份）
```

---

## ⚠️ 免责声明

本工具用于救回自己改崩的 SystemUI 操作涉及替换系统界面 风险自负：**务必先备好可用的救砖界面 再换入待测界面**

---

## 📄 许可证

GNU General Public License v3.0

玻璃与背景效果参考自 [Sonify](https://github.com/XiaoZhi0729/Sonify)（同为 GPL-3.0） 第三方库的版权声明见应用内「第三方许可证」页 各依赖沿用其自身许可证
