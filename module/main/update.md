## latest

### 修复

- 修复模块缺少系统挂载目录

### 更改

- 移除模块默认日志目录
- 移除模块调试日志
- 新增未设日志目录拒绝运行
- 新增升级保留原有配置

## v1.5.4 更新

- 增加`action.sh`防呆机制 每次运行都会清理旧`action.sh`进程

## v1.5.3 更新

### 修复
- **切换目录时文件选择框闪一下 / 瞬间消失又出现**
  - 原因一：外框用 `max-height` 撑高，列表一清空（换成"加载中…"）框就塌到只剩标题那一截，
    数据回来再撑开，看起来就是"框没了又冒出来"
  - 原因二：每次切目录都把列表整块清空再重建，中间必然出现空内容帧
  - 外框改为固定高度（75%），成为独立的一层，不再随内容多少伸缩
  - 切换时不再清空列表：旧内容保留并轻微变淡，新数据回来一次性替换内容层
  - 加载指示改为标题栏右侧的小转圈，不占列表空间、不引起重排
- 快速连点目录时可能读到上一个目录的结果，加了连点锁
- 打开选择器时先清掉上次的列表，避免"路径已是新目录、内容还是旧目录"

## v1.5.2 更新

### 修复
- **文件选择器点任意目录都会关闭弹框**
  - 现象：打开选择文件后，点目录（含「上级目录」）弹框直接消失，看不到进入下一级
  - 原因：目录项点击后立刻重绘列表，被点的元素随即脱离文档；事件继续冒泡到遮罩层时
    `sheet.contains(ev.target)` 恒为 false，被误判成「点了遮罩」而关闭
  - 改为只认「点击目标就是遮罩本身」才关闭，并在列表项点击时阻止冒泡
  - 选 APK 的路径未受影响，行为不变
- 目录无权限或不存在时如实显示脚本返回的原因，不再一律提示「此目录没有 APK 或子目录」
- 文件名/目录名含 `&` `<` `"` 等字符时列表错乱（列表项未做转义）

## v1.5.1 更新

### 性能
- 优化 WebUI 打开性能

### 功能
- 文件选择器：点上方空白处收起，返回键优先关闭弹框
- 音量键校验分步显示，失败标红

### 修复
- 修复音量键偶尔识别不到
- 风险提示精简为两行

## v1.5.0_hotfix1 更新

### 修复
- **音量键识别错乱**：原来按设备名（gpio-keys / qpnp_pon）猜设备，遇到 uinput 虚拟设备同时声明上下键时会取错，导致「上正常下不行 / 下正常上不行」轮流失败
  - 改为按内核能力位识别：KEY_VOLUMEUP = bit115，KEY_VOLUMEDOWN = bit114
  - 位图解析兼容 32 位和 64 位内核（字长 8 / 16 个十六进制字符都试）
- **内核位图解析错误**：内核按「高位字在前、每字左侧不补零」打印，`8000000000000 0` 实际是 bit115 而非 bit12
- **check.sh 内容重复三遍**：文件被多次追加，重写为单份实现

### 新增
- **音量键实测流程** volkey.sh 改为四步，不再依赖固定 event 编号
  - 第 1 步：扫描所有声明了音量键的设备
  - 第 2 步：让用户按音量+，实测哪个设备真的收到（多个则列出让用户选，带推荐标记）
  - 第 3 步：音量- 同理
  - 第 4 步：用选定设备做最终校验
  - 关键：能力位图声明 ≠ 实际产生事件，必须实测。uinput-goodix 声明了上下键但一次事件都不产生
- **check.sh 拆分为分步命令**：env / keys / scan / up / down / save / verify
  - `up` 和 `down` 各自独立，测完一个立即返回
- **WebUI 校验实时更新**：音量+ 打勾后立刻刷新界面，再开始等音量-
  - 原因：ksu.exec 是同步阻塞的，一次调用无法中途更新 UI
  - 做法：runCheck 拆成 env → up → down → save 四次串行调用，每次之间 await nextFrame()
- **校验结果写入配置**：VOL_UP_DEV / VOL_DOWN_DEV，action.sh 优先读取
- 校验步骤新增失败态样式（红色 ✕），与等待（蓝色脉冲）、成功（绿色 ✓）区分

### 变更
- **设备识别三层降级**：内核 sysfs → /proc/bus/input/devices → getevent -pl
  - 前两种只读文件，不依赖 toolbox，老 ROM 也能用
  - 识别失败时退化为监听全部 /dev/input/event*
- **action.sh 严格区分上下键**：每个设备单独一个 getevent 进程、单独输出文件，避免事件串台
  - 修复 getevent 传多个设备参数会打印 usage 退出的问题（只接受一个 device 参数）
- action.sh 主循环改为按设备列表逐个启动监听，进程意外退出时自动重启

### 说明
- 音量键设备因机型和 ROM 而异，不写死 event 编号
- 本机（umi / HyperOS 4）实测：event4 = 音量+，event1 = 音量-，event2 为虚拟设备不产生事件
- 运行 volkey.sh 可直接查看完整识别与实测过程

## v1.5.0 更新

### 新增
- **WebUI 界面**（webroot/），APatch / KernelSU 管理器内直接打开
  - 底栏双页导航：主页（测试配置）/ 设置（运行参数）
  - Miuix 风格，支持深色模式，适配系统顶栏和底栏 inset
  - 配置项实时写入 settings.conf，无需点保存
  - 文件选择器：目录浏览，选完自动复制救砖文件到模块根
- **环境校验脚本** check.sh，独立于 action.sh
  - 安卓版本校验（ro.build.version.release / sdk）
  - 系统版本校验（ro.mi.os.version.name / ro.miui.ui.version.name）
  - 机型信息记录（device / model / build）
  - 系统升级后版本变化会提示重新校验
- **保底恢复脚本** safety.sh，与救砖逻辑完全独立
  - 到点无条件恢复界面，不依赖 action.sh 和音量键
  - 写入方式与救砖一致（大小相同用 dd，不同用 cp）
- settings.conf 新增环境记录项：CHECK_ANDROID_VER / CHECK_ANDROID_SDK / CHECK_OS_NAME / CHECK_DEVICE / CHECK_TIME

### 变更
- **音量键校验移到 WebUI**，action.sh 不再自行校验
  - 未校验时降级为「仅记录日志」模式，而非直接退出
  - 校验方式改为文件轮询（0.1 秒响应），替代管道的块缓冲问题
- action.sh 支持 --test 参数（测试模式，内部延迟 5 秒自动替换）
- logcat 和 getevent 加 setsid，避免父进程退出时被进程组清理
- webctl.sh 精简为命令入口：ls / prepare / test-run / status / output / stop / rescue
- 设置页新增系统救援开关（屏蔽 RescueParty），带风险提示

### 修复
- settings.conf 中 CHECK_TIME 含空格未加引号，source 时报 "inaccessible or not found"
- 重新打开 WebUI 后设置页音量键设备信息丢失（boot 时始终探测）
- WebUI 点击设置卡顿（键位探测结果缓存 5 分钟）

### 暂不可用
- **WebUI 自动测试功能暂时下线**
  - 原因：脚本写入 APK 无法触发 SystemUI 重新加载，MT 管理器写入可以
  - 现状：按钮保留但点击只提示，原逻辑代码保留待恢复
  - 手动流程：MT 复制待测 APK → 系统自动重启 → 音量键救砖

## v1.3.0 更新

## v1.3.0 更新

### 新增
- action.sh: **黑屏卡死检测**，解决「进程活着但界面卡死」无法自动救砖的问题
  - 日志疯长检测：连续 2 轮增长超过 LOG_FLOOD_BYTES（默认 2MB）判定卡死
  - 堆栈溢出检测：堆栈行超过 5000 行且占比超过 80% 判定 StackOverflow
  - 日志体积上限：超过 LOG_MAX_BYTES（默认 50MB）自动截断，防止写爆存储
- action.sh: 崩溃计数实时日志，每次变化打印 `崩溃计数: N/阈值`
- action.sh: debug 日志增加 `hh:mm:ss` 时间戳，便于分析崩溃间隔
- action.sh: 音量键监听进程意外退出时自动重启，保证全程可响应
- settings.conf: 新增 LOG_FLOOD_BYTES、LOG_MAX_BYTES 配置项

### 变更
- action.sh: 移除替换后的超时限制，救砖监听改为无限等待
  - 等待用户替换界面（PID 变化）：保留 KEY_TIMEOUT 超时，防止误启动后一直挂着
  - 音量键监听：无超时，直到按下按键
- action.sh: 移除"等待 SystemUI 启动"循环，能手动运行脚本即代表 SystemUI 在跑
- action.sh: 出口从三个扩展为五个（自动触发 / 日志疯长 / 堆栈溢出 / 音量+ / 音量-）
- action.sh: debug 日志新增基线 PID 记录，便于对照
- settings.conf: KEY_TIMEOUT 说明更新，现在仅影响 LOG_MODE=1 和等待替换阶段

### 修复
- 修复 StackOverflow 类崩溃（如 SimCarrierView 递归）无法触发救砖
  - 此类崩溃单次产生 7 万行堆栈、16MB 日志，但 30 秒内只崩 2 次
  - 永远达不到 FATAL_THRESHOLD，导致 SystemUI 黑屏卡死却无法自动恢复

### 说明
- 替换检测到之后的音量键监听不再受 KEY_TIMEOUT 控制
- 测试时可将 FATAL_THRESHOLD 设为较大值（如 20）只观察计数不触发
- 堆栈检测要求日志大于 1MB，避免小日志误判

## v1.2.0 更新

### 修复
- service.sh: 配置文件不存在时直接退出，避免后续静默失败
- service.sh: LOG_MODE 用默认值兜底，防止空值比较报错
- service.sh: 开机描述改为「等待开机完成」，更准确
- log.sh: MODDIR 推导修正（脚本在 config/ 目录下）
- log.sh: 等开机完成最多 10 分钟，避免死等
- log.sh: 启动前清空 crash 缓冲，防止历史日志污染
- log.sh: 空日志自动删除，不留 0 字节文件
- customize.sh: LOG_MODE 改用 case 判断，避免空值报错
- settings.conf: update_desc 增加文件存在检查