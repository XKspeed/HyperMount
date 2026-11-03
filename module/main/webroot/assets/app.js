/* 救砖模块 WebUI */
(function () {
'use strict';

var hasKsu = typeof ksu !== 'undefined';
var cbSeq = 0;
var MODDIR = null;
var CONFIG = {};
var pollTimer = null;
var pickerTarget = null;   // 'rescue' | 'target'
var pickerPath = '/storage/emulated/0';
var pickerBusy = false;    // 读目录 / 选文件期间防连点
var keysCache = null;      // 音量键探测结果缓存
var keysCacheTime = 0;

/* ==================== 沉浸模式 ==================== */

/**
 * 让 WebView 内容延伸到状态栏和导航栏下面。
 * 不调用的话，状态栏/底栏区域是系统默认黑底，和页面背景对不上。
 * 调用后 APatch / KernelSU 会注入 --window-inset-* 变量，
 * 页面据此做安全区内边距（见 style.css 的 --inset-*）。
 */
function setupEdgeToEdge() {
    if (!hasKsu || typeof ksu.enableEdgeToEdge !== 'function') return;
    try {
        ksu.enableEdgeToEdge(true);
    } catch (e) {
        try { ksu.enableEdgeToEdge(); } catch (e2) {}
    }
}

/* ==================== 桥接 ==================== */

function exec(cmd) {
    if (!hasKsu) return Promise.resolve({ errno: -1, stdout: '', stderr: 'ksu 不可用' });
    return new Promise(function (resolve) {
        var name = 'cb_' + Date.now() + '_' + (cbSeq++);
        window[name] = function (errno, stdout, stderr) {
            delete window[name];
            resolve({ errno: errno, stdout: stdout || '', stderr: stderr || '' });
        };
        try {
            ksu.exec(cmd, JSON.stringify({}), name);
        } catch (e) {
            delete window[name];
            resolve({ errno: -1, stdout: '', stderr: String(e) });
        }
    });
}

/** 让浏览器完成一次渲染后再继续（ksu.exec 会阻塞 JS 线程） */
function nextFrame() {
    return new Promise(function (resolve) {
        requestAnimationFrame(function () {
            setTimeout(resolve, 40);
        });
    });
}

function toast(msg) {
    // 有原生 toast 就用原生的，避免双重弹出
    if (hasKsu && typeof ksu.toast === 'function') {
        try { ksu.toast(msg); return; } catch (e) {}
    }
    // 浏览器预览等无原生环境的回退
    var el = document.getElementById('toast');
    if (!el) return;
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(el._t);
    el._t = setTimeout(function () { el.classList.remove('show'); }, 1900);
}

async function findModuleDir() {
    if (MODDIR) return MODDIR;
    if (hasKsu && ksu.moduleInfo) {
        try {
            var info = ksu.moduleInfo();
            if (info && info.id) {
                var p = '/data/adb/modules/' + info.id;
                var t = await exec('[ -d "' + p + '" ] && echo ok');
                if (t.stdout.trim() === 'ok') { MODDIR = p; return MODDIR; }
            }
        } catch (e) {}
    }
    var r = await exec('[ -d /data/adb/modules/systemui_mount ] && echo ok');
    if (r.stdout.trim() === 'ok') { MODDIR = '/data/adb/modules/systemui_mount'; return MODDIR; }
    return null;
}

/** 调用 check.sh（环境校验） */
async function checkctl(args) {
    var dir = await findModuleDir();
    if (!dir) return { errno: -1, stdout: '', stderr: '找不到模块' };
    return exec('sh "' + dir + '/webroot/check.sh" ' + args + ' 2>&1');
}

async function webctl(args) {
    var dir = await findModuleDir();
    if (!dir) return { errno: -1, stdout: '', stderr: '找不到模块' };
    return exec('sh "' + dir + '/webroot/webctl.sh" ' + args + ' 2>&1');
}

/* ==================== 配置 ==================== */

function parseConf(text) {
    var out = {};
    text.split('\n').forEach(function (line) {
        line = line.replace(/\r$/, '');
        if (!line || /^\s*#/.test(line)) return;
        var m = line.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
        if (!m) return;
        var val = m[2].trim();
        if ((val.charAt(0) === '"' && val.charAt(val.length - 1) === '"') ||
            (val.charAt(0) === "'" && val.charAt(val.length - 1) === "'")) {
            val = val.slice(1, -1);
        }
        out[m[1]] = val;
    });
    return out;
}

async function loadConfig() {
    var dir = await findModuleDir();
    if (!dir) return null;
    var r = await exec('cat "' + dir + '/config/settings.conf" 2>/dev/null');
    if (!r.stdout) return null;
    CONFIG = parseConf(r.stdout);
    return CONFIG;
}

async function setConf(key, value) {
    var dir = await findModuleDir();
    if (!dir) return false;
    var file = dir + '/config/settings.conf';
    var safe = String(value).replace(/[&\\|]/g, '\\$&');
    var cmd =
        'if grep -q "^' + key + '=" "' + file + '" 2>/dev/null; then ' +
        'sed -i "s|^' + key + '=.*|' + key + '=' + safe + '|" "' + file + '"; ' +
        'else echo "' + key + '=' + safe + '" >> "' + file + '"; fi';
    var r = await exec(cmd);
    return r.errno === 0;
}

/* ==================== 页面切换 ==================== */

function showPage(name) {
    document.getElementById('page-home').hidden = name !== 'home';
    document.getElementById('page-settings').hidden = name !== 'settings';
    document.getElementById('page-title').textContent =
        name === 'home' ? '救砖模块' : '设置';

    document.querySelectorAll('.tab').forEach(function (t) {
        t.classList.toggle('active', t.dataset.page === name);
    });

    void document.body.offsetHeight;
    window.scrollTo(0, 0);
}

/* ==================== 状态 ==================== */

function esc(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function fmtSize(n) {
    n = parseInt(n, 10) || 0;
    if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB';
    if (n >= 1024) return (n / 1024).toFixed(0) + ' KB';
    return n + ' B';
}

/** 刷新状态（轮询用）：走单次 boot 取数，比原来 4 次 exec 快很多 */
async function refreshStatus() {
    var dir = await findModuleDir();
    if (!dir) {
        var dot = document.getElementById('status-dot');
        if (dot) {
            dot.className = 'status-dot error';
            document.getElementById('status-main').textContent = '模块未安装';
            document.getElementById('status-sub').textContent = '找不到模块目录';
        }
        return;
    }
    var raw = await exec('sh "' + dir + '/webroot/webctl.sh" boot 2>&1');
    applyStatus(parseSections(raw.stdout));
}

/** 用 boot 或 status 取回的数据渲染状态卡片 */
function applyStatus(S) {
    var dot = document.getElementById('status-dot');
    var main = document.getElementById('status-main');
    var sub = document.getElementById('status-sub');
    if (!dot || !main || !sub) return;

    var kv = S.STATUS || {};
    var pidVal = ((S.PID || {}).pid || '').trim();
    var verVal = ((S.VERSION || {}).version || '').trim();
    if (verVal) document.getElementById('module-version').textContent = verVal;

    var rescueSize = parseInt(kv.rescue, 10) || 0;
    var running = (kv.running || '').trim();
    var targetOk = kv.targetok === '1';
    var targetSize = parseInt(kv.targetsize, 10) || 0;
    var targetTime = kv.targettime || '';

    dot.className = 'status-dot';
    if (!pidVal) {
        dot.classList.add('warn');
        main.textContent = 'SystemUI 未运行';
        sub.textContent = '可能正在重启';
    } else if (!rescueSize) {
        dot.classList.add('warn');
        main.textContent = 'SystemUI 运行中';
        sub.textContent = 'PID ' + pidVal + ' · 尚未准备救砖文件';
    } else {
        dot.classList.add('ok');
        main.textContent = 'SystemUI 运行中';
        sub.textContent = 'PID ' + pidVal + ' · 救砖文件 ' + fmtSize(rescueSize);
    }

    var rHead = document.getElementById('rescue-head');
    var rPath = document.getElementById('rescue-path');
    var ri = document.getElementById('rescue-info');
    if (rHead && rPath) {
        if (rescueSize > 0) {
            var rp = CONFIG.RESCUE_APK_PATH || '';
            var rn = rp ? rp.split('/').pop() : 'MiuiSystemUI.apk';
            rHead.innerHTML = esc(rn) +
                ' <span class="file-meta">' + fmtSize(rescueSize) + '</span>';
            rPath.textContent = rp || '(模块根目录 MiuiSystemUI.apk)';
            if (ri) ri.className = 'file-info ok';
        } else {
            rHead.textContent = '未选择';
            rPath.textContent = '';
            if (ri) ri.className = 'file-info';
        }
    }

    var tHead = document.getElementById('target-head');
    var tPath = document.getElementById('target-path');
    var ti = document.getElementById('target-info');
    if (tHead && tPath) {
        var tp = CONFIG.TARGET_APK_PATH || '';
        if (targetOk && tp) {
            tHead.innerHTML = esc(tp.split('/').pop()) +
                ' <span class="file-meta">' + fmtSize(targetSize) +
                (targetTime ? ' · ' + esc(targetTime) : '') + '</span>';
            tPath.textContent = tp;
            if (ti) ti.className = 'file-info ok';
        } else if (tp) {
            tHead.innerHTML = esc(tp.split('/').pop()) +
                ' <span class="file-meta">文件已不存在</span>';
            tPath.textContent = tp;
            if (ti) ti.className = 'file-info';
        } else {
            tHead.textContent = '未选择';
            tPath.textContent = '';
            if (ti) ti.className = 'file-info';
        }
    }

    var btnStart = document.getElementById('btn-start');
    var btnStop = document.getElementById('btn-stop');
    var rs = document.getElementById('run-status');
    if (btnStart && btnStop && rs) {
        btnStart.hidden = !!running;
        btnStop.hidden = !running;

        // 救砖文件就绪（模块根有）且待测文件存在才能开始
        var rescueReady = rescueSize > 0;
        var targetReady = targetOk;

        if (!running) {
            if (!rescueReady && !targetReady) {
                rs.textContent = '请选择救砖文件和待测文件';
            } else if (!rescueReady) {
                rs.textContent = '请选择救砖文件';
            } else if (!targetReady) {
                rs.textContent = '请选择待测文件';
            } else {
                rs.textContent = '准备就绪，可以开始';
                rs.className = 'file-info ok';
            }
        } else {
            rs.textContent = '监听中 (PID ' + running + ')';
            rs.className = 'file-info running';
        }

        // 自动测试暂不可用，按钮保持可点（点击只提示）
        btnStart.disabled = false;
    }
}

/* ==================== 系统救援开关 ==================== */

/** 用已有数据渲染救援开关 */
function applyRescue(kv) {
    var toggle = document.getElementById('rescue-toggle');
    var stateEl = document.getElementById('rescue-state');
    var blocked = kv.state === 'off';

    if (toggle) toggle.checked = blocked;
    if (stateEl) {
        stateEl.textContent = blocked
            ? '已屏蔽（崩溃 ' + (kv.limit || '?') + ' 次才触发）'
            : '正常（崩溃 12 次触发）';
    }
}

async function syncRescue() {
    var r = await webctl('rescue get');
    var kv = {};
    (r.stdout || '').split('\n').forEach(function (l) {
        var i = l.indexOf('=');
        if (i > 0) kv[l.slice(0, i)] = l.slice(i + 1);
    });
    applyRescue(kv);
}

/* ==================== 音量键校验 ==================== */

function showGuide(show) {
    document.getElementById('guide-card').hidden = !show;
    document.getElementById('home-normal').hidden = show;
}

async function probeKeys(force) {
    var now = Date.now();
    var kv;

    // 5 分钟内复用缓存，避免切页卡顿
    if (!force && keysCache && (now - keysCacheTime) < 300000) {
        kv = keysCache;
    } else {
        var r = await checkctl('keys');
        kv = {};
        (r.stdout || '').split('\n').forEach(function (l) {
            var i = l.indexOf('=');
            if (i > 0) kv[l.slice(0, i)] = l.slice(i + 1);
        });
        keysCache = kv;
        keysCacheTime = now;
    }
    renderKeys(kv);
    return kv;
}

function renderKeys(kv) {
    [['dev-up', kv.up], ['dev-up-s', kv.up], ['dev-down', kv.down], ['dev-down-s', kv.down]]
        .forEach(function (pair) {
            var el = document.getElementById(pair[0]);
            if (!el) return;
            el.textContent = pair[1] || '未找到';
            el.className = 'key-dev' + (pair[1] ? ' ok' : '');
        });
}

/** 读取系统环境信息 */
async function loadEnv() {
    var r = await checkctl('env');
    var kv = {};
    (r.stdout || '').split('\n').forEach(function (l) {
        var i = l.indexOf('=');
        if (i > 0) kv[l.slice(0, i)] = l.slice(i + 1);
    });
    return kv;
}

/**
 * 分步校验：每一步都是一次独立的 exec 调用，
 * 这样每测完一个键就能立刻更新界面。
 */
async function runCheck(btnId, suffix) {
    var btn = document.getElementById(btnId);
    var prog = document.getElementById('check-progress' + suffix);
    var stepUp = document.getElementById('step-up' + suffix);
    var stepDown = document.getElementById('step-down' + suffix);
    var hint = document.getElementById('check-hint' + suffix);

    if (!btn) return;

    btn.disabled = true;
    btn.classList.add('loading');

    if (prog) prog.hidden = false;
    if (stepUp) { stepUp.className = 'check-step'; stepUp.textContent = '音量 +  等待按键…'; }
    if (stepDown) { stepDown.className = 'check-step'; stepDown.textContent = '音量 −  等待按键…'; }
    if (hint) hint.textContent = '请按提示操作';
    await nextFrame();

    showOutput();
    appendOutput('> 开始环境校验\n');

    // ---------- 环境信息 ----------
    var envR = await checkctl('env');
    var env = {};
    (envR.stdout || '').split('\n').forEach(function (l) {
        var i = l.indexOf('=');
        if (i > 0) env[l.slice(0, i)] = l.slice(i + 1);
    });
    appendOutput('安卓 ' + (env.android_ver || '?') + ' (SDK ' + (env.android_sdk || '?') + ')\n');
    appendOutput('系统 ' + (env.os_name || '?') + '\n');
    appendOutput('机型 ' + (env.model || '?') + ' (' + (env.device || '?') + ')\n\n');

    // ---------- 音量 + ----------
    if (stepUp) { stepUp.className = 'check-step active'; stepUp.textContent = '音量 +  请按键…'; }
    if (hint) hint.textContent = '请按一次【音量 +】';
    await nextFrame();

    var upR = await checkctl('up');
    var upOut = (upR.stdout || '').trim();
    appendOutput('> 音量+ 实测: ' + upOut + '\n');
    var upDev = upOut.indexOf('OK ') === 0 ? upOut.slice(3).trim() : '';

    if (stepUp) {
        if (upDev) {
            stepUp.className = 'check-step pass';
            stepUp.textContent = '音量 +  已识别 ' + upDev;
        } else {
            stepUp.className = 'check-step fail';
            stepUp.textContent = '音量 +  未检测到';
        }
    }
    // 关键：这里立刻刷新界面，不等音量- 测完
    await nextFrame();

    // ---------- 音量 - ----------
    if (stepDown) { stepDown.className = 'check-step active'; stepDown.textContent = '音量 −  请按键…'; }
    if (hint) hint.textContent = '请按一次【音量 −】';
    await nextFrame();

    var downR = await checkctl('down');
    var downOut = (downR.stdout || '').trim();
    appendOutput('> 音量- 实测: ' + downOut + '\n');
    var downDev = downOut.indexOf('OK ') === 0 ? downOut.slice(3).trim() : '';

    if (stepDown) {
        if (downDev) {
            stepDown.className = 'check-step pass';
            stepDown.textContent = '音量 −  已识别 ' + downDev;
        } else {
            stepDown.className = 'check-step fail';
            stepDown.textContent = '音量 −  未检测到';
        }
    }
    await nextFrame();

    // ---------- 保存结果 ----------
    var ok = !!upDev && !!downDev;
    if (ok) {
        var saveCmd = 'save "' + upDev + '" "' + downDev + '"';
        var saveR = await checkctl(saveCmd);
        appendOutput('> ' + ((saveR.stdout || '').trim() || '已保存') + '\n');
    }

    btn.disabled = false;
    btn.classList.remove('loading');
    await probeKeys(true);

    if (ok) {
        if (hint) hint.textContent = '校验通过';
        toast('音量键校验通过');
        await loadConfig();
        fillForm(CONFIG);
        await renderEnv();
        await nextFrame();
        setTimeout(function () {
            if (prog) prog.hidden = true;
            showGuide(false);
        }, 1200);
    } else {
        if (hint) hint.textContent = '校验失败，请重试';
        toast('校验失败');
    }
}

/** 用已有数据渲染环境信息 */
function applyEnv(env) {
    var el = document.getElementById('env-info');
    if (!el) return;

    var savedA = env.saved_android || '';
    var savedO = env.saved_os || '';
    var curA = env.android_ver || '';
    var curO = env.os_name || '';

    if (!savedA && !savedO) {
        el.hidden = true;
        return;
    }

    el.hidden = false;

    var aEl = document.getElementById('env-android');
    var oEl = document.getElementById('env-os');
    var tEl = document.getElementById('env-time');

    if (aEl) {
        aEl.textContent = curA ? ('Android ' + curA + (savedA && savedA !== curA ? '（校验时 ' + savedA + '）' : '')) : '-';
        aEl.parentNode.className = 'env-row' + (savedA && savedA !== curA ? ' warn' : '');
    }
    if (oEl) {
        oEl.textContent = curO || '-';
        oEl.parentNode.className = 'env-row' + (savedO && savedO !== curO ? ' warn' : '');
    }
    if (tEl) {
        tEl.textContent = CONFIG.CHECK_TIME || '-';
    }

    // 版本变化时提示
    if ((savedA && savedA !== curA) || (savedO && savedO !== curO)) {
        toast('系统版本已变化，建议重新校验音量键');
    }
}

/** 单独刷新环境信息（取数后渲染） */
async function renderEnv() {
    applyEnv(await loadEnv());
}

/* ==================== 表单 ==================== */

var MODE_HINTS = {
    '0': '完全禁用。不记录日志，不监听按键，不救砖。',
    '1': '只记录崩溃日志，不监听音量键。',
    '2': '记录日志并监听音量键。音量+ 救砖，音量− 取消。',
    '3': '记录日志、监听按键，崩溃达到阈值自动救砖。'
};

function syncModeButtons(mode) {
    document.querySelectorAll('#log-mode button').forEach(function (b) {
        b.classList.toggle('active', b.dataset.value === String(mode));
    });
    var hint = document.getElementById('mode-hint');
    if (hint) hint.textContent = MODE_HINTS[String(mode)] || '';
}

function fillForm(cfg) {
    if (!cfg) return;
    syncModeButtons(cfg.LOG_MODE || '0');
    var f = document.getElementById('fatal-threshold');
    var k = document.getElementById('key-timeout');
    var d = document.getElementById('debug-toggle');
    if (f) f.value = cfg.FATAL_THRESHOLD || '3';
    if (k) k.value = cfg.KEY_TIMEOUT || '30';
    if (d) d.checked = /^(true|1|yes)$/i.test(cfg.DEBUG || 'false');
}

/* ==================== 输出 ==================== */

function showOutput() {
    var el = document.getElementById('output-card');
    if (el) el.hidden = false;
}

function appendOutput(text) {
    var el = document.getElementById('output');
    if (!el) return;
    el.textContent += text;
    el.scrollTop = el.scrollHeight;
}

/* ==================== 文件选择器 ==================== */

async function openPicker(target) {
    pickerTarget = target;
    pickerPath = '/storage/emulated/0';
    document.getElementById('picker').hidden = false;
    // 清掉上一次的目录内容：否则会先看到旧目录的列表，路径却已经是 /storage/emulated/0
    var list = document.getElementById('picker-list');
    if (list) list.innerHTML = '';
    // 压入一条历史，让系统返回键先关弹框而不是退出 WebUI
    try { history.pushState({ picker: 1 }, ''); } catch (e) {}
    await listDir(pickerPath);
}

/** 关闭文件选择器（同时清理历史记录，避免多压一层） */
function closePicker() {
    var el = document.getElementById('picker');
    if (!el || el.hidden) return;
    el.hidden = true;
    pickerBusy = false;
    if (history.state && history.state.picker) {
        try { history.back(); } catch (e) {}
    }
}

/**
 * 切换目录。
 * 外框（.picker-sheet）高度固定，这里只替换里面的列表，
 * 不再“先清空成加载中再填充”——那会让外框塌一下再撑开，就是切换时的那下闪烁。
 */
async function listDir(path) {
    if (pickerBusy) return;
    pickerBusy = true;

    pickerPath = path;
    document.getElementById('picker-path').textContent = path;

    var sheet = document.getElementById('picker-sheet');
    var list = document.getElementById('picker-list');
    if (sheet) sheet.classList.add('is-loading');
    if (list) list.classList.add('is-loading');

    // ksu.exec 是同步阻塞的，先让浏览器画一帧，加载态才看得见
    await nextFrame();

    try {
        var r = await webctl('ls "' + path.replace(/"/g, '\\"') + '"');
        var raw = (r.stdout || '').trim();
        var lines = raw.split('\n').filter(function (l) { return l.indexOf('|') > 0; });

        if (!lines.length) {
            // 脚本报错（目录无权限/不存在）时如实显示，别一律说“空目录”
            var msg = /^FAIL\b/.test(raw) ? raw.split('\n')[0].replace(/^FAIL\s*/, '') : '此目录没有 APK 或子目录';
            list.innerHTML = '<div class="picker-empty">' + esc(msg) + '</div>';
            return;
        }

        var dirs = [];
        var apks = [];
        lines.forEach(function (l) {
            var parts = l.split('|');
            var name = parts[0];
            var type = parts[1];
            var size = parts[2];
            if (type === 'd') dirs.push({ name: name });
            else apks.push({ name: name, size: size });
        });

        var html = '';

        // 返回上级
        if (path !== '/') {
            var parent = path.replace(/\/[^/]+$/, '') || '/';
            html += '<div class="picker-item dir" data-path="' + esc(parent) + '">' +
                    '<span class="picker-icon">↑</span><span class="picker-name">上级目录</span></div>';
        }

        dirs.forEach(function (d) {
            html += '<div class="picker-item dir" data-path="' + esc(path + '/' + d.name) + '">' +
                    '<span class="picker-icon">📁</span>' +
                    '<span class="picker-name">' + esc(d.name) + '</span></div>';
        });

        apks.forEach(function (a) {
            html += '<div class="picker-item apk" data-path="' + esc(path + '/' + a.name) + '">' +
                    '<span class="picker-icon">📦</span>' +
                    '<span class="picker-name">' + esc(a.name) + '</span>' +
                    '<span class="picker-size">' + fmtSize(a.size) + '</span></div>';
        });

        list.innerHTML = html;
        list.scrollTop = 0;   // 进新目录从顶部开始

        // 绑定点击
        list.querySelectorAll('.picker-item').forEach(function (el) {
            el.addEventListener('click', function (ev) {
                // 必须拦住冒泡：目录项一点就会重绘整个列表，被点的元素随即脱离文档，
                // 事件继续冒泡到遮罩时，遮罩那层已经认不出这是“弹框内部”的点击了。
                ev.stopPropagation();
                var p = el.dataset.path;
                if (el.classList.contains('dir')) {
                    listDir(p);
                } else {
                    selectFile(p);
                }
            });
        });
    } finally {
        // 无论成功、空目录还是出错，都要把加载态收掉，否则下次点击会被 busy 锁挡住
        if (sheet) sheet.classList.remove('is-loading');
        if (list) list.classList.remove('is-loading');
        pickerBusy = false;
    }
}

async function selectFile(path) {
    if (pickerBusy) return;
    pickerBusy = true;
    try {
        if (pickerTarget === 'rescue') {
            toast('正在准备救砖文件…');
            var r = await webctl('prepare "' + path.replace(/"/g, '\\"') + '"');

            if ((r.stdout || '').indexOf('OK') >= 0) {
                await setConf('RESCUE_APK_PATH', path);
                toast('救砖文件已就位');
            } else {
                toast('准备失败: ' + (r.stdout || r.stderr || '未知错误').split('\n')[0]);
            }
        } else {
            await setConf('TARGET_APK_PATH', path);
            toast('已选择待测文件');
        }

        await loadConfig();
        closePicker();
        await refreshStatus();
    } finally {
        pickerBusy = false;
    }
}

/* ==================== 事件 ==================== */

function bindEvents() {
    // 底栏
    document.querySelectorAll('.tab').forEach(function (t) {
        t.addEventListener('click', function () {
            showPage(t.dataset.page);
            if (t.dataset.page === 'settings') {
                // 键位信息用缓存；缓存为空则重新探测一次
                if (keysCache) {
                    renderKeys(keysCache);
                } else {
                    probeKeys();
                }
                syncRescue();
                renderEnv();
            }
        });
    });

    // 模式
    document.getElementById('log-mode').addEventListener('click', async function (e) {
        var btn = e.target.closest('button[data-value]');
        if (!btn) return;
        syncModeButtons(btn.dataset.value);
        await setConf('LOG_MODE', btn.dataset.value);
    });

    // 步进器
    document.querySelectorAll('.step-btn').forEach(function (b) {
        b.addEventListener('click', async function () {
            var which = b.dataset.step;
            var delta = parseInt(b.dataset.dir, 10);
            var input = document.getElementById(which === 'fatal' ? 'fatal-threshold' : 'key-timeout');
            var v = (parseInt(input.value, 10) || 0) + delta;
            var min = parseInt(input.min, 10) || 1;
            var max = parseInt(input.max, 10) || 999;
            input.value = Math.min(max, Math.max(min, v));
            await setConf(which === 'fatal' ? 'FATAL_THRESHOLD' : 'KEY_TIMEOUT', input.value);
        });
    });

    // 数字框
    ['fatal-threshold', 'key-timeout'].forEach(function (id) {
        var el = document.getElementById(id);
        if (!el) return;
        el.addEventListener('change', async function () {
            var v = parseInt(el.value, 10);
            var min = parseInt(el.min, 10) || 1;
            var max = parseInt(el.max, 10) || 999;
            if (isNaN(v)) v = min;
            v = Math.min(max, Math.max(min, v));
            el.value = v;
            await setConf(id === 'fatal-threshold' ? 'FATAL_THRESHOLD' : 'KEY_TIMEOUT', v);
        });
    });

    // 调试开关
    document.getElementById('debug-toggle').addEventListener('change', async function () {
        await setConf('DEBUG', this.checked ? 'true' : 'false');
    });

    // 系统救援开关
    document.getElementById('rescue-toggle').addEventListener('change', async function () {
        var on = this.checked;
        var r = await webctl('rescue ' + (on ? 'off' : 'on'));
        toast((r.stdout || '').replace('OK ', '') || '操作完成');
        await syncRescue();
    });

    // 校验按钮
    document.getElementById('btn-check').addEventListener('click', function () { runCheck('btn-check', ''); });
    document.getElementById('btn-recheck').addEventListener('click', function () { runCheck('btn-recheck', '-s'); });

    // 文件选择
    document.getElementById('btn-pick-rescue').addEventListener('click', function () { openPicker('rescue'); });
    document.getElementById('btn-pick-target').addEventListener('click', function () { openPicker('target'); });

    // 点上方遮罩区域收起（只认遮罩本身，点弹框内部不触发）
    var pickerEl = document.getElementById('picker');
    if (pickerEl) {
        pickerEl.addEventListener('click', function (ev) {
            // 只认“点到的就是遮罩本身”。不能再用 sheet.contains(ev.target)：
            // 点目录时列表会立刻重绘，ev.target 已脱离文档，contains 恒为 false，
            // 会把弹框内部的点击误判成点遮罩，导致点任意目录弹框就消失。
            if (ev.target !== pickerEl) return;
            closePicker();
        });
    }

    // 系统返回键：弹框开着就关弹框，不开才退出
    window.addEventListener('popstate', function () {
        var el = document.getElementById('picker');
        if (el && !el.hidden) {
            el.hidden = true;
        }
    });

    // 开始测试
    document.getElementById('btn-start').addEventListener('click', async function () {
        // 【暂不可用】自动替换界面功能暂时下线
        // 原因：脚本写入无法触发 SystemUI 重新加载，需先解决挂载感知问题
        // 原逻辑保留在 test-run 分支，恢复时改回 webctl('test-run', ...) 即可
        toast('自动测试暂不可用，请使用action进行手动测试');
        return;

        /* ===== 以下为原逻辑，暂时保留 ===== */
        var btn = this;
        var rescue = (CONFIG.RESCUE_APK_PATH || '').trim();
        var target = (CONFIG.TARGET_APK_PATH || '').trim();

        if (!rescue) {
            var st = await webctl('status');
            var kv = {};
            (st.stdout || '').split('\n').forEach(function (l) {
                var i2 = l.indexOf('=');
                if (i2 > 0) kv[l.slice(0, i2)] = l.slice(i2 + 1);
            });
            if (parseInt(kv.rescue, 10) > 0) {
                var dir = await findModuleDir();
                rescue = dir + '/MiuiSystemUI.apk';
            } else {
                toast('请先选择救砖文件');
                return;
            }
        }

        if (!target) { toast('请先选择待测文件'); return; }

        var active = document.querySelector('#log-mode button.active');
        var mode = active ? active.dataset.value : '0';
        if (mode === '0') { toast('请先在设置中选择运行模式'); return; }

        await setConf('RESCUE_APK_PATH', rescue);
        await setConf('TARGET_APK_PATH', target);

        btn.disabled = true;
        btn.classList.add('loading');
        showOutput();
        appendOutput('> 开始测试\n  救砖: ' + rescue.split('/').pop() + '\n  待测: ' + target.split('/').pop() + '\n\n');

        var r = await webctl('test-run "' + rescue.replace(/"/g, '\\"') + '" "' + target.replace(/"/g, '\\"') + '"');
        appendOutput(r.stdout || '');
        if (r.stderr) appendOutput(r.stderr);

        btn.disabled = false;
        btn.classList.remove('loading');

        var ok = (r.stdout || '').indexOf('OK ') >= 0;
        toast(ok ? '测试已开始' : '启动失败');
        await refreshStatus();

        if (pollTimer) clearInterval(pollTimer);
        pollTimer = setInterval(pollOutput, 1500);
        /* ===== 原逻辑结束 ===== */
    });

    // 停止
    document.getElementById('btn-stop').addEventListener('click', async function () {
        var r = await webctl('stop');
        toast((r.stdout || '').trim() || '已停止');
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
        await refreshStatus();
    });

    // 清空
    document.getElementById('btn-clear').addEventListener('click', function () {
        document.getElementById('output').textContent = '';
    });
}

async function pollOutput() {
    var r = await webctl('output');
    var el = document.getElementById('output');
    if (r.stdout && el) {
        el.textContent = r.stdout;
        el.scrollTop = el.scrollHeight;
    }
    await refreshStatus();
}

/* ==================== 启动 ==================== */

/** 把 boot 输出按 ##分节 切成对象 */
function parseSections(text) {
    var out = {};
    var cur = null;
    (text || '').split('\n').forEach(function (l) {
        var m = l.match(/^##([A-Z]+)$/);
        if (m) { cur = m[1]; out[cur] = {}; return; }
        if (cur === null) return;
        var i = l.indexOf('=');
        if (i > 0) out[cur][l.slice(0, i)] = l.slice(i + 1);
    });
    return out;
}

async function boot() {
    if (!hasKsu) {
        showGuide(true);
        document.getElementById('status-dot').className = 'status-dot error';
        document.getElementById('status-main').textContent = 'KSU 接口不可用';
        document.getElementById('status-sub').textContent = '请在 APatch / KernelSU 管理器内打开';
        return;
    }

    // 一次 exec 取回首屏需要的全部数据（原来要 7 次，约 550ms -> 330ms）
    var raw = await exec('sh "' + (await findModuleDir()) + '/webroot/webctl.sh" boot 2>&1');
    var S = parseSections(raw.stdout);

    // 配置单独读（需要完整原文才能解析所有键）
    var cfg = await loadConfig();
    if (cfg) fillForm(cfg);

    // 状态卡片
    applyStatus(S);

    // 系统救援开关
    applyRescue(S.RESCUE || {});

    // 环境信息
    applyEnv(S.ENV || {});

    // 音量键
    var kv = S.KEYS || {};
    keysCache = kv;
    keysCacheTime = Date.now();
    renderKeys(kv);

    if (!cfg || cfg.VOLUME_CHECK_ASSESS !== '1') {
        showGuide(true);
    } else {
        showGuide(false);
    }
}

// 先开沉浸模式，再渲染页面（否则状态栏/底栏区域会是黑底）
setupEdgeToEdge();

try {
    bindEvents();
} catch (e) {
    console.error('bindEvents failed:', e);
}

showPage('home');
boot().catch(function (e) { console.error('boot failed:', e); });

})();
