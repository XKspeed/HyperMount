import DefaultTheme from "vitepress/theme";
import { onMounted } from "vue";
import { useData } from "vitepress";
import "./style/var.css";

/**
 * 与 App 内更新检查同源的一组镜像，GitHub 直连不通时挨个试。
 * 官网只做「换个前缀」这一层，真正的探测在客户端里做。
 */
const MIRRORS = [
  "https://gh-proxy.com/",
  "https://ghproxy.net/",
  "https://ghfast.top/",
];

const FALLBACK_URL = "https://github.com/XKspeed/SystemUIRescue/releases/latest";

let manifest: Promise<any> | null = null;

function loadManifest(base: string) {
  if (!manifest) {
    manifest = fetch(base + "update.json", { cache: "no-cache" })
      .then((r) => (r.ok ? r.json() : null))
      .catch(() => null);
  }
  return manifest;
}

export default {
  extends: DefaultTheme,

  setup() {
    const { site } = useData();

    onMounted(async () => {
      const base = site.value.base || "/";
      const list = document.querySelectorAll<HTMLElement>("[data-version]");
      const links = document.querySelectorAll<HTMLAnchorElement>("[data-download]");
      const mirror = document.querySelectorAll<HTMLAnchorElement>("[data-mirror]");
      if (!list.length && !links.length && !mirror.length) return;

      const info = await loadManifest(base);
      const version = info?.versionName ? "v" + info.versionName : "";
      const url = info?.url || FALLBACK_URL;

      list.forEach((el) => {
        el.textContent = version || "最新版";
      });
      links.forEach((el) => el.setAttribute("href", url));
      mirror.forEach((el) => el.setAttribute("href", MIRRORS[0] + url));
    });
  },
};
