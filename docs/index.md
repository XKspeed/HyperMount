---
layout: home

hero:
  name: SystemUI 救援
  text: 换界面不再怕黑屏
  tagline: 先留一份确认可用的 SystemUI 当后路，再换入要测的那一版。崩溃、卡死、界面点不动，它自己换回去——不用救命线，不用重刷系统。
  image:
    src: /app-icon.svg
    alt: SystemUI 救援
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
    details: 累计崩溃到阈值，或者日志疯长判定为卡死，自动把救砖界面装回去并重启 SystemUI。
  - icon: |-
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
        <path d="M4 9.5h2.6L11 5.6v12.8L6.6 14.5H4z"/>
        <path d="M15.4 9a4.2 4.2 0 0 1 0 6"/>
        <path d="M18.2 6.4a8 8 0 0 1 0 11.2"/>
      </svg>
    title: 音量键救砖
    details: 界面已经点不动、通知也拉不下来的时候，音量 + 直接换回，音量 − 取消误触。
  - icon: |-
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 3.2 3.4 7.6 12 12l8.6-4.4z"/>
        <path d="M3.4 12 12 16.4 20.6 12"/>
        <path d="M3.4 16.4 12 20.8l8.6-4.4"/>
      </svg>
    title: 模块化挂载
    details: 真正干活的逻辑在模块里，开机就跑，不依赖应用存活——应用本身也可能被一起带走。
  - icon: |-
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
        <path d="M6.5 3.5h7.6L18.5 8v12.5h-12z"/>
        <path d="M14.1 3.5V8h4.4"/>
        <path d="M9.3 12.2h6.4"/>
        <path d="M9.3 16h4.2"/>
      </svg>
    title: 日志可诊断
    details: 应用日志分等级、脚本日志逐步记录、崩溃堆栈单独留一份，出问题先看它们。
---

<section class="home-section">
  <h2>一条链路，三步</h2>
  <p class="home-sub">所有设计都围绕这三步，不多做别的</p>

  <div class="steps">
    <div class="step">
      <span class="no">01 / 备</span>
      <h3>备好救砖界面</h3>
      <p>从已确认能用的系统里取一份 SystemUI 存下来，它就是出事后的退路。选择时会校验包名，只认 <code>com.android.systemui</code>。</p>
    </div>
    <div class="step">
      <span class="no">02 / 换</span>
      <h3>换入待测界面</h3>
      <p>把要实测的那一版换进去，正常点亮、正常滑动。稍有不对就停，守护会一直盯着崩溃日志。</p>
    </div>
    <div class="step">
      <span class="no">03 / 回</span>
      <h3>崩了自动换回</h3>
      <p>累计到设定次数、或者日志疯长判定为卡死，自动把救砖界面装回去并重启 SystemUI，回到能看的状态。</p>
    </div>
  </div>
</section>

<section class="home-section">
  <h2>真出事时才起作用的细节</h2>
  <p class="home-sub">这些不是锦上添花，是黑屏那一刻唯一能救命的</p>

  <div class="steps">
    <div class="step">
      <span class="no">卡死检测</span>
      <h3>不只数崩溃</h3>
      <p>堆栈刷屏、日志疯长同样判定为卡死。这类崩溃单次就能刷几万行，靠次数阈值永远等不到。</p>
    </div>
    <div class="step">
      <span class="no">音量键</span>
      <h3>先实测再记</h3>
      <p>按内核能力位找设备，再逐个按键实测是否真出事件。系统升级后版本对不上，自动作废旧结果重新校验。</p>
    </div>
    <div class="step">
      <span class="no">系统救援</span>
      <h3>屏蔽掉它</h3>
      <p>把 AMS 的崩溃次数上限拉高，别让系统抢在自动救砖之前把 SystemUI 杀掉。开启增强救砖时强制打开。</p>
    </div>
    <div class="step">
      <span class="no">独立测试</span>
      <h3>主页另一套</h3>
      <p>不依赖模块守护，自己往 <code>/data/local/tmp</code> 丢脚本跑。测别的 apk 会不会崩，不动系统界面。</p>
    </div>
  </div>
</section>

<section class="home-section">
  <h2>下载</h2>
  <p class="home-sub">只提供 arm64-v8a 包，覆盖安装即可升级</p>

  <div class="dl-card">
    <div>
      <h2><span data-version>最新版</span></h2>
      <p>装之前请先看一遍<a href="guide/">使用教程</a>，第一步是装模块。</p>
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
