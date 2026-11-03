---
layout: home

hero:
  name: HyperMount
  text: 换界面不再怕黑屏
  tagline: 先把一份确认能用的 SystemUI 存成后路 再换入要测的那一版 崩溃 卡死 界面点不动 它自己换回去——不用救命线 不用重刷系统 不换界面的时候 也能拿它测任意 APK 崩不崩 
  image:
    src: /app-icon.svg
    alt: HyperMount
  actions:
    - theme: brand
      text: 下载 APK
      link: https://github.com/XKspeed/SystemUIRescue/releases/latest
    - theme: alt
      text: 先看怎么装
      link: /guide/
    - theme: alt
      text: GitHub
      link: https://github.com/XKspeed/SystemUIRescue

features:
  - icon: |-
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
        <path d="M20.5 12a8.5 8.5 0 1 1-2.49-6.01"/>
        <path d="M20.5 3.8v4.3h-4.3"/>
      </svg>
    title: 崩了自动换回
    details: 崩溃累计到阈值 日志疯长判定卡死 堆栈溢出 三种都算 自动装回救砖界面并重启 SystemUI 
  - icon: |-
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
        <path d="M4 9.5h2.6L11 5.6v12.8L6.6 14.5H4z"/>
        <path d="M15.4 9a4.2 4.2 0 0 1 0 6"/>
        <path d="M18.2 6.4a8 8 0 0 1 0 11.2"/>
      </svg>
    title: 音量键救砖
    details: 界面已经点不动 通知也拉不下来的时候 音量 + 直接换回 音量 − 取消误触 
  - icon: |-
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
        <path d="M7 4.5v12"/>
        <path d="M4.2 13.7 7 16.5l2.8-2.8"/>
        <path d="M17 19.5v-12"/>
        <path d="M14.2 10.3 17 7.5l2.8 2.8"/>
      </svg>
    title: 两套流程
    details: 界面页换 SystemUI 主页测任意 APK 后者不碰系统界面 也不依赖模块守护 关掉模块照样能跑 
  - icon: |-
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 3.2 3.4 7.6 12 12l8.6-4.4z"/>
        <path d="M3.4 12 12 16.4 20.6 12"/>
        <path d="M3.4 16.4 12 20.8l8.6-4.4"/>
      </svg>
    title: 模块化挂载
    details: 真正干活的逻辑在模块里 开机就跑 不依赖应用存活——应用本身也可能被一起带走 
---

<section class="home-section">
  <h2>一条链路 三步</h2>
  <p class="home-sub">HyperMount 不是换肤工具 它只做一件事：换 SystemUI 的时候 永远留一条能回去的路 </p>

  <div class="steps">
    <div class="step">
      <span class="no">01 / 备</span>
      <h3>备好救砖界面</h3>
      <p>从能正常开机的系统里取一份 SystemUI 存下来 它就是出事后的退路 选择时校验包名 只认 <code>com.android.systemui</code> 下载器留下的 <code>apk.1</code> 后缀会自动还原 </p>
    </div>
    <div class="step">
      <span class="no">02 / 换</span>
      <h3>换入待测界面</h3>
      <p>确认后立即替换 不再有等待倒计时 之后盯 SystemUI 的 PID 变了才算真的重启成功 超时没变化 直接判定这次替换失败 </p>
    </div>
    <div class="step">
      <span class="no">03 / 回</span>
      <h3>崩了自动换回</h3>
      <p>累计 fatal 到阈值 日志疯长判定为卡死 堆栈溢出 三种情况任一触发 就把救砖界面装回去并重启 SystemUI 回到能看的状态 </p>
    </div>
  </div>
</section>

<section class="home-section">
  <h2>另一条链路：测任意 APK</h2>
  <p class="home-sub">不换系统界面 也能用它验一个包稳不稳 和上一条链路完全独立 两份配置各存各的 </p>

  <div class="steps">
    <div class="step">
      <span class="no">01 / 选</span>
      <h3>待测 + 救砖</h3>
      <p>选要测的 apk 再选崩了要装回的那份 把「自动替换」关掉就只抓日志 连救砖包都不用选 </p>
    </div>
    <div class="step">
      <span class="no">02 / 装</span>
      <h3>立即安装并开抓</h3>
      <p>确认后直接安装 装完立刻抓 crash 中间不插等待 待测包先复制到应用缓存再交给系统安装 绕开 <code>/storage</code> 的权限限制 </p>
    </div>
    <div class="step">
      <span class="no">03 / 判</span>
      <h3>崩了就换回</h3>
      <p>抓取时长内崩溃 或者日志超过设定大小 就装回救砖包 测的是 <code>miui.systemui.plugin</code> 时 额外看崩溃次数 日志疯长 堆栈占比三路 </p>
    </div>
  </div>
</section>

<section class="home-section">
  <h2>真出事时才起作用的细节</h2>
  <p class="home-sub">这些不是锦上添花 是黑屏那一刻唯一能救命的</p>

  <div class="steps">
    <div class="step">
      <span class="no">卡死检测</span>
      <h3>不只数崩溃</h3>
      <p>堆栈刷屏 日志疯长同样判定为卡死 这类崩溃单次就能刷几万行 靠次数阈值永远等不到 </p>
    </div>
    <div class="step">
      <span class="no">音量键</span>
      <h3>先实测再记</h3>
      <p>按内核能力位找设备 再逐个按键实测是否真出事件 系统升级后版本对不上 旧结果自动作废重新校验 </p>
    </div>
    <div class="step">
      <span class="no">系统救援</span>
      <h3>屏蔽掉它</h3>
      <p>把 AMS 的崩溃次数上限拉到 30 别让系统抢在自动救砖之前把 SystemUI 杀掉 开启增强救砖时强制打开 </p>
    </div>
    <div class="step">
      <span class="no">日志</span>
      <h3>三份分开留</h3>
      <p>应用日志分六档（关闭 / 仅错误 / 警告 / 常规 / 详细 / 全部） 模块脚本逐步记录 崩溃堆栈单独存一份 日志目录 保留天数 缓存上限都能改 </p>
    </div>
  </div>
</section>

<section class="home-section">
  <h2>下载</h2>
  <p class="home-sub">先刷模块 再装应用 应用只提供 arm64-v8a 覆盖安装即可升级</p>

  <div class="dl-card">
    <div>
      <h2>模块 <span data-module-version>最新版</span></h2>
      <p>第一步 在 Root 管理器里「从本地安装」 刷入后重启手机 </p>
      <div class="dl-meta">
        <span>systemui_mount</span>
        <span>Magisk / KernelSU / APatch</span>
        <span>可单独更新</span>
      </div>
    </div>
    <div class="dl-actions">
      <a class="VPButton medium brand" data-module-download href="https://github.com/XKspeed/SystemUIRescue/releases/latest">下载模块 zip</a>
      <a class="VPButton medium alt" data-module-mirror href="https://gh-proxy.com/https://github.com/XKspeed/SystemUIRescue/releases/latest">走镜像下载</a>
    </div>
  </div>

  <div class="dl-card">
    <div>
      <h2>应用 <span data-version>最新版</span></h2>
      <p>第二步 模块装好重启后再装它 第一次打开会走引导 </p>
      <div class="dl-meta">
        <span>arm64-v8a</span>
        <span>Android 15 (API 35)+</span>
        <span>需 Root</span>
        <span>GPL-3.0</span>
      </div>
    </div>
    <div class="dl-actions">
      <a class="VPButton medium brand" data-download href="https://github.com/XKspeed/SystemUIRescue/releases/latest">下载 APK</a>
      <a class="VPButton medium alt" data-mirror href="https://gh-proxy.com/https://github.com/XKspeed/SystemUIRescue/releases/latest">走镜像下载</a>
    </div>
  </div>
</section>
