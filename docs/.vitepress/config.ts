import { defineConfig } from "vitepress";

/**
 * 站点部署在两个地方，路径前缀不同：
 *   GitHub Pages   https://<user>.github.io/SystemUIRescue/  → DOCS_BASE=/SystemUIRescue/
 *   EdgeOne Pages  根域名                                    → 不设，默认 /
 * 工作流里通过环境变量传，本地 build 默认按根路径处理。
 */
const base = process.env.DOCS_BASE || "/";

const REPO = "https://github.com/XKspeed/SystemUIRescue";

export default defineConfig({
  base,
  lang: "zh-CN",
  title: "SystemUI 救援",
  description:
    "换入待测 SystemUI 界面，崩了自动换回救砖界面。Miuix 界面、音量键救砖、崩溃阈值与卡死检测。",
  cleanUrls: true,
  head: [
    ["meta", { name: "theme-color", content: "#3482ff" }],
    ["meta", { name: "color-scheme", content: "light dark" }],
    ["link", { rel: "icon", href: `${base}Icon.svg` }],
    ["link", { rel: "apple-touch-icon", href: `${base}Icon.svg` }],
    ["link", { rel: "preconnect", href: "https://cdn-font.hyperos.mi.com" }],
    [
      "link",
      {
        rel: "stylesheet",
        href: "https://cdn-font.hyperos.mi.com/font/css?family=MiSans_VF:VF:Chinese_Simplify,Latin&display=swap",
      },
    ],
  ],
  themeConfig: {
    logo: "/Icon.svg",
    siteTitle: "SystemUI 救援",

    nav: [
      { text: "首页", link: "/" },
      { text: "使用教程", link: "/guide/" },
      { text: "常见问题", link: "/faq" },
      { text: "模块说明", link: "/module" },
    ],

    sidebar: [
      {
        text: "开始",
        items: [{ text: "使用教程", link: "/guide/" }],
      },
      {
        text: "参考",
        items: [
          { text: "常见问题", link: "/faq" },
          { text: "模块说明", link: "/module" },
          { text: "更新日志", link: `${REPO}/releases` },
        ],
      },
    ],

    outline: {
      level: [2, 3],
      label: "本页目录",
    },

    search: {
      provider: "local",
      options: {
        translations: {
          button: { buttonText: "搜索", buttonAriaLabel: "搜索" },
          modal: {
            noResultsText: "没有找到相关内容",
            resetButtonTitle: "清除",
            footer: {
              selectText: "选择",
              navigateText: "切换",
              closeText: "关闭",
            },
          },
        },
      },
    },

    socialLinks: [{ icon: "github", link: REPO }],

    docFooter: { prev: "上一篇", next: "下一篇" },
    returnToTopLabel: "回到顶部",
    sidebarMenuLabel: "菜单",
    darkModeSwitchLabel: "主题",
    lightModeSwitchTitle: "切换到浅色模式",
    darkModeSwitchTitle: "切换到深色模式",
    outlineTitle: "本页目录",

    footer: {
      message: "换界面出问题风险自负，务必先备好可用的救砖界面",
      copyright: `GPL-3.0 · Copyright © ${new Date().getFullYear()} XKspeed`,
    },
  },
});
