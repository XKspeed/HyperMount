/* ============================================================
   读取同域的 update.json 填充版本号与下载地址
   这份清单由发版工作流自动改写，页面本身不用跟着改
   取不到就退回静态兜底（Releases 页面），不会出现死链
   ============================================================ */

(function () {
  var FALLBACK = {
    versionName: '',
    versionCode: '',
    url: 'https://github.com/XKspeed/SystemUIRescue/releases/latest',
  };

  // 与 App 内更新检查同源的一组镜像：GitHub 直连不通时挨个试
  var MIRRORS = [
    'https://gh-proxy.com/',
    'https://ghproxy.net/',
    'https://ghfast.top/',
  ];

  function apply(info) {
    var v = info.versionName || FALLBACK.versionName;
    var code = info.versionCode || FALLBACK.versionCode;
    var url = info.url || FALLBACK.url;

    document.querySelectorAll('[data-version]').forEach(function (el) {
      el.textContent = v ? 'v' + v : '最新版';
    });
    document.querySelectorAll('[data-version-code]').forEach(function (el) {
      el.textContent = code ? 'versionCode ' + code : '';
    });

    document.querySelectorAll('[data-download]').forEach(function (el) {
      el.setAttribute('href', url);
    });

    // 镜像按钮：同一个文件换前缀，主站打不开时点这个
    document.querySelectorAll('[data-mirror]').forEach(function (el) {
      el.setAttribute('href', MIRRORS[0] + url);
    });

    document.querySelectorAll('[data-releases]').forEach(function (el) {
      el.setAttribute('href', 'https://github.com/XKspeed/SystemUIRescue/releases');
    });
  }

  function load() {
    apply(FALLBACK);
    if (!window.fetch) return;
    fetch('update.json', { cache: 'no-cache' })
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (data) {
        if (data && data.versionName) apply(data);
      })
      .catch(function () {
        /* 静默：已经用兜底值渲染过 */
      });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', load);
  } else {
    load();
  }
})();
