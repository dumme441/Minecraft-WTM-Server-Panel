package wtm.web;

/** 面板页面：单文件、零外部资源、原生 JS。 */
public final class Page {

    private Page() {}

    public static String html(String appName) {
        return """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__APP__</title>
<style>
  :root{
    --bg:#0a0e13; --bg2:#0e141b; --card:#121a23; --card2:#0f161e; --line:#1e2a37;
    --text:#dce6f2; --dim:#7d8ea3; --accent:#4c9aff; --accent2:#7db4ff;
    --ok:#3ecf8e; --warn:#f5b544; --bad:#f47171; --radius:14px;
  }
  *{box-sizing:border-box}
  html,body{height:100%}
  body{margin:0;background:
      radial-gradient(1200px 600px at 10% -10%, #12233a 0%, transparent 60%),
      radial-gradient(900px 500px at 110% 10%, #1a1430 0%, transparent 55%),
      var(--bg);
    color:var(--text);
    font:14px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC","Microsoft YaHei",sans-serif;
    -webkit-font-smoothing:antialiased}
  button,select,input{font:inherit;color:inherit}
  .hidden{display:none!important}

  /* ---------- login ---------- */
  #login{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;z-index:50;
    background:rgba(6,9,13,.82);backdrop-filter:blur(14px)}
  .login-card{width:min(360px,92vw);background:linear-gradient(180deg,#131c26,#0f161e);
    border:1px solid var(--line);border-radius:20px;padding:30px 26px;
    box-shadow:0 24px 60px rgba(0,0,0,.5)}
  .login-card .mark{width:46px;height:46px;border-radius:13px;display:grid;place-items:center;
    background:linear-gradient(140deg,#2f6fd0,#4c9aff);color:#04101f;font-weight:800;font-size:20px;
    box-shadow:0 8px 22px rgba(76,154,255,.35);margin-bottom:16px}
  .login-card h2{margin:0 0 4px;font-size:19px;letter-spacing:.2px}
  .login-card p{margin:0 0 20px;color:var(--dim);font-size:12.5px}
  .field{display:flex;flex-direction:column;gap:7px;margin-bottom:14px}
  .field label{font-size:12px;color:var(--dim)}
  input[type=password],input[type=text],input:not([type]),select{
    background:#0a1017;border:1px solid var(--line);border-radius:10px;padding:10px 12px;outline:none;
    transition:border-color .15s, box-shadow .15s}
  input:focus,select:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(76,154,255,.15)}
  .btn{cursor:pointer;border:1px solid var(--line);background:#16202b;border-radius:10px;
    padding:8px 14px;transition:.15s;white-space:nowrap}
  .btn:hover{border-color:#31506f;background:#1a2735}
  .btn.primary{background:linear-gradient(180deg,#4c9aff,#3a86e8);border-color:#3a86e8;color:#04121f;font-weight:700}
  .btn.primary:hover{filter:brightness(1.08)}
  .btn.ghost{background:transparent}
  .btn.danger{color:#ffb3b3;border-color:#4a2a2a;background:#1d1414}
  .btn.danger:hover{background:#2a1717;border-color:#6a3434}
  .btn:disabled{opacity:.42;cursor:not-allowed;filter:none}
  .btn.block{width:100%;padding:11px}
  .err{color:var(--bad);font-size:12px;min-height:16px;margin-top:6px}

  /* ---------- shell ---------- */
  .wrap{max-width:1460px;margin:0 auto;padding:16px 18px 40px}
  header.top{position:sticky;top:0;z-index:20;margin:-16px -18px 16px;padding:12px 18px;
    display:flex;align-items:center;gap:14px;flex-wrap:wrap;
    background:rgba(10,14,19,.82);backdrop-filter:blur(12px);border-bottom:1px solid var(--line)}
  .brand{display:flex;align-items:center;gap:10px;min-width:150px}
  .brand .mark{width:34px;height:34px;border-radius:10px;display:grid;place-items:center;
    background:linear-gradient(140deg,#2f6fd0,#4c9aff);color:#04101f;font-weight:900;font-size:16px}
  .brand h1{font-size:15px;margin:0;letter-spacing:.3px}
  .brand p{margin:0;font-size:11px;color:var(--dim);letter-spacing:.6px;text-transform:uppercase}
  .pills{display:flex;gap:8px;flex-wrap:wrap;margin-left:auto}
  .pill{display:flex;align-items:center;gap:7px;padding:6px 11px;border:1px solid var(--line);
    border-radius:999px;background:#101821;font-size:12px;color:var(--dim)}
  .pill b{color:var(--text);font-weight:600;font-variant-numeric:tabular-nums}
  .dot{width:8px;height:8px;border-radius:50%;background:#4b5a6a}
  .dot.on{background:var(--ok);box-shadow:0 0 10px rgba(62,207,142,.8)}
  .dot.busy{background:var(--warn);box-shadow:0 0 10px rgba(245,181,68,.8)}
  .dot.off{background:var(--bad)}

  main{display:grid;grid-template-columns:minmax(0,1fr) 380px;gap:14px;align-items:start}
  @media(max-width:980px){main{grid-template-columns:1fr}}

  .card{background:linear-gradient(180deg,var(--card),var(--card2));border:1px solid var(--line);
    border-radius:var(--radius);overflow:hidden;box-shadow:0 10px 30px rgba(0,0,0,.22)}
  .card-head{display:flex;align-items:center;gap:10px;padding:11px 14px;border-bottom:1px solid var(--line)}
  .card-head h2{margin:0;font-size:12px;letter-spacing:1.2px;text-transform:uppercase;color:var(--dim);font-weight:600}
  .card-head .spacer{flex:1}
  .tiny{font-size:11.5px;color:var(--dim)}
  .chip{padding:2px 8px;border-radius:999px;background:#182430;border:1px solid var(--line);font-size:11px}

  /* console */
  .console-body{position:relative;transition:height .2s ease}
  .console-body.folded{height:0!important;overflow:hidden;border:0}
  #console{min-height:200px;max-height:min(48vh,430px);overflow:auto;margin:0;padding:12px 14px;
    font:12.5px/1.6 ui-monospace,SFMono-Regular,Consolas,"Courier New",monospace;
    white-space:pre-wrap;word-break:break-word;background:#080c11;color:#c6d3e2}
  #console .sys{color:var(--warn)}
  #console .cmd{color:var(--accent2)}
  #console .err{color:var(--bad)}
  .cmdrow{display:flex;gap:8px;padding:10px 12px;border-top:1px solid var(--line);background:#0d141c}
  .cmdrow input{flex:1}
  .folded-hint{display:none;padding:9px 14px;font-size:12px;color:var(--dim);border-top:1px solid var(--line)}
  .card.folded .folded-hint{display:block}

  /* backups */
  .bk-list{max-height:min(52vh,520px);overflow:auto}
  table{width:100%;border-collapse:collapse;font-size:13px}
  th,td{padding:9px 12px;text-align:left;border-bottom:1px solid #182430;vertical-align:middle}
  th{position:sticky;top:0;background:#0f161e;color:var(--dim);font-weight:500;font-size:11px;
    text-transform:uppercase;letter-spacing:.7px}
  tbody tr:hover{background:#131d27}
  td.act{text-align:right}
  .tag{font-size:11px;padding:2px 7px;border-radius:6px;background:#1b2734;color:#8fb0cf;border:1px solid #22303f}
  .tag.manual{background:#16283c;color:#8fc3ff;border-color:#24405f}
  .tag.pre_restore{background:#2e2416;color:#f3c37f;border-color:#4a3a22}
  .empty{padding:26px 14px;text-align:center;color:var(--dim);font-size:12.5px}

  .jobbar{display:flex;align-items:center;gap:10px;padding:9px 14px;border-bottom:1px solid var(--line);
    background:#0f1822;font-size:12px;color:var(--dim)}
  .bar{flex:1;height:6px;border-radius:99px;background:#0a1017;overflow:hidden}
  .bar>i{display:block;height:100%;width:0;background:linear-gradient(90deg,#3a86e8,#4c9aff);transition:width .35s}

  .actions{display:flex;gap:8px;flex-wrap:wrap;padding:12px 14px}
  #toast{position:fixed;right:18px;bottom:18px;display:flex;flex-direction:column;gap:8px;z-index:60}
  .toast{background:#1b2633;border:1px solid #2b3d50;border-left:3px solid var(--accent);
    padding:10px 14px;border-radius:10px;font-size:12.5px;box-shadow:0 12px 30px rgba(0,0,0,.4);
    animation:in .18s ease}
  .toast.bad{border-left-color:var(--bad)}
  @keyframes in{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}
</style>
</head>
<body>

<div id="login">
  <form class="login-card" id="loginForm">
    <div class="mark">W</div>
    <h2>__APP__</h2>
    <p>家庭服控制台 · 请输入面板口令</p>
    <div class="field">
      <label for="pw">口令</label>
      <input id="pw" type="password" autocomplete="current-password" placeholder="••••••••">
    </div>
    <button class="btn primary block" type="submit">进入面板</button>
    <div class="err" id="loginErr"></div>
  </form>
</div>

<div id="app" class="hidden">
<div class="wrap">
  <header class="top">
    <div class="brand">
      <div class="mark">W</div>
      <div><h1>WTM</h1><p>save guardian</p></div>
    </div>
    <div class="pills">
      <div class="pill"><span class="dot" id="dot"></span><b id="stateTx">未知</b></div>
      <div class="pill">在线 <b id="players">-</b></div>
      <div class="pill">TPS <b id="tps">-</b></div>
      <div class="pill">运行 <b id="uptime">-</b></div>
      <div class="pill">磁盘 <b id="disk">-</b></div>
      <div class="pill">备份 <b id="lastBackup">-</b></div>
    </div>
  </header>

  <main>
    <section class="card" id="consoleCard">
      <div class="card-head">
        <h2>控制台</h2>
        <span class="chip" id="lineChip">最近 50 行</span>
        <div class="spacer"></div>
        <select id="lineSel" title="显示行数">
          <option value="50">50 行</option>
          <option value="200">200 行</option>
          <option value="1000">1000 行</option>
        </select>
        <button class="btn ghost" id="btnFold" title="折叠 / 展开控制台">折叠</button>
      </div>
      <div class="jobbar hidden" id="jobBar">
        <span id="jobStage">-</span>
        <div class="bar"><i id="jobFill"></i></div>
        <span id="jobPct">0%</span>
      </div>
      <div class="console-body" id="consoleBody">
        <pre id="console"></pre>
      </div>
      <div class="folded-hint">控制台已折叠 · 点右上角「展开」显示</div>
      <form class="cmdrow" id="cmdForm">
        <input id="cmd" placeholder="输入服务端指令，回车发送（例如 say hello）" autocomplete="off">
        <button class="btn primary" type="submit">发送</button>
      </form>
    </section>

    <aside class="card">
      <div class="card-head">
        <h2>操作</h2>
        <div class="spacer"></div>
        <span class="tiny" id="busyTx"></span>
      </div>
      <div class="actions">
        <button class="btn primary" id="btnStart">启动</button>
        <button class="btn" id="btnRestart">重启</button>
        <button class="btn danger" id="btnStop">停止</button>
        <button class="btn primary" id="btnBackup">立即备份</button>
      </div>
      <div class="card-head" style="border-top:1px solid var(--line)">
        <h2>存档快照</h2>
        <div class="spacer"></div>
        <span class="tiny" id="bkCount"></span>
      </div>
      <div class="bk-list">
        <table>
          <thead><tr><th>时间</th><th>类型</th><th>体积</th><th></th></tr></thead>
          <tbody id="bkRows"></tbody>
        </table>
      </div>
    </aside>
  </main>
</div>
</div>

<div id="toast"></div>

<script>
const $ = id => document.getElementById(id);
let csrf = "";
let lines = [];
let renderPending = false;

function toast(msg, bad){
  const t = document.createElement("div");
  t.className = "toast" + (bad ? " bad" : "");
  t.textContent = msg;
  $("toast").appendChild(t);
  setTimeout(() => t.remove(), 3800);
}
function cookie(name){
  const parts = document.cookie.split(";");
  for (const p of parts){
    const kv = p.trim().split("=");
    if (kv[0] === name) return decodeURIComponent(kv.slice(1).join("="));
  }
  return "";
}
async function api(path, opts){
  opts = opts || {};
  const headers = Object.assign({"Content-Type":"application/json"}, opts.headers || {});
  if (csrf) headers["X-CSRF"] = csrf;
  const res = await fetch(path, Object.assign({}, opts, {headers}));
  if (res.status === 401){ showLogin(); throw new Error("unauthorized"); }
  if (res.status === 409){ toast("另一个任务正在执行", true); throw new Error("busy"); }
  return res;
}
function showLogin(){ $("login").classList.remove("hidden"); $("app").classList.add("hidden"); }
function showApp(){ $("login").classList.add("hidden"); $("app").classList.remove("hidden"); }

/* ---------------- console ---------------- */
function lineClass(text){
  if (text.indexOf("[→]") >= 0) return "cmd";
  if (text.indexOf("[wtm]") >= 0) return "sys";
  if (/\\bERROR\\b|Exception|Caused by/.test(text)) return "err";
  return "";
}
function renderConsole(){
  renderPending = false;
  const n = parseInt($("lineSel").value, 10) || 50;
  const slice = lines.slice(-n);
  const pre = $("console");
  pre.textContent = "";
  const frag = document.createDocumentFragment();
  for (const l of slice){
    const d = document.createElement("div");
    d.className = lineClass(l);
    d.textContent = l;
    frag.appendChild(d);
  }
  pre.appendChild(frag);
  pre.scrollTop = pre.scrollHeight;
  $("lineChip").textContent = "最近 " + n + " 行";
}
function scheduleRender(){
  if (renderPending) return;
  renderPending = true;
  requestAnimationFrame(renderConsole);
}
function pushLine(text){
  lines.push(text);
  if (lines.length > 2000) lines.splice(0, lines.length - 2000);
  scheduleRender();
}

/* ---------------- sse ---------------- */
let es = null;
function connectLogs(){
  if (es) es.close();
  es = new EventSource("/api/logs");
  es.onmessage = ev => pushLine(ev.data);
  es.onerror = () => { es.close(); setTimeout(connectLogs, 3000); };
}

/* ---------------- status ---------------- */
function fmtDur(s){
  if (s < 0) return "-";
  const d = Math.floor(s/86400), h = Math.floor(s%86400/3600), m = Math.floor(s%3600/60);
  if (d) return d + "d " + h + "h";
  if (h) return h + "h " + m + "m";
  return m + "m";
}
function fmtSize(b){
  const u = ["B","KB","MB","GB","TB"]; let v = b, i = 0;
  while (v >= 1024 && i < u.length-1){ v /= 1024; i++; }
  return v.toFixed(1) + " " + u[i];
}
async function refresh(){
  let s;
  try { s = await (await api("/api/status")).json(); } catch(e){ return; }
  const st = s.state;
  $("stateTx").textContent = st === "RUNNING" ? "运行中" : st === "STOPPED" ? "已停止" :
                              st === "STARTING" ? "启动中" : st === "STOPPING" ? "停止中" : st;
  $("dot").className = "dot " + (st === "RUNNING" ? "on" : (st === "STARTING" || st === "STOPPING") ? "busy" : "off");
  $("players").textContent = s.online < 0 ? "-" : s.online;
  $("tps").textContent = (s.tps !== null && s.tps !== undefined) ? Number(s.tps).toFixed(1) : "-";
  $("uptime").textContent = fmtDur(s.uptime);
  $("disk").textContent = fmtSize(s.diskFree);
  $("lastBackup").textContent = s.lastBackup || "-";
  $("btnStart").disabled = st !== "STOPPED";
  $("btnStop").disabled = st === "STOPPED";
  $("btnRestart").disabled = st === "STOPPED";
  $("btnBackup").disabled = !!s.busy;
  $("busyTx").textContent = s.busy ? "任务执行中…" : "";
  if (s.job){
    $("jobBar").classList.remove("hidden");
    $("jobStage").textContent = s.job.stage || s.job.phase;
    $("jobFill").style.width = (s.job.percent || 0) + "%";
    $("jobPct").textContent = (s.job.percent || 0) + "%";
  } else {
    $("jobBar").classList.add("hidden");
  }
  await loadBackups();
}

async function loadBackups(){
  let r;
  try { r = await (await api("/api/backups")).json(); } catch(e){ return; }
  const tb = $("bkRows");
  $("bkCount").textContent = r.items.length + " 个";
  if (!r.items.length){
    tb.innerHTML = "<tr><td colspan='4' class='empty'>还没有快照，点「立即备份」创建第一个</td></tr>";
    return;
  }
  tb.textContent = "";
  for (const b of r.items){
    const tr = document.createElement("tr");
    const td1 = document.createElement("td"); td1.textContent = b.created;
    const td2 = document.createElement("td");
    const tag = document.createElement("span"); tag.className = "tag " + b.kind; tag.textContent = b.kindLabel;
    td2.appendChild(tag);
    const td3 = document.createElement("td"); td3.textContent = b.size;
    const td4 = document.createElement("td"); td4.className = "act";
    const btn = document.createElement("button"); btn.className = "btn"; btn.textContent = "回档";
    btn.onclick = () => restore(b.id);
    td4.appendChild(btn);
    tr.append(td1, td2, td3, td4);
    tb.appendChild(tr);
  }
}

/* ---------------- actions ---------------- */
async function restore(id){
  const typed = prompt("回档到 " + id + "？\\n\\n流程：停服 → 自动备份当前存档 → 覆盖世界 → 启动 → 体检\\n预计停机 1-2 分钟。\\n\\n确认请输入 RESTORE：");
  if (typed !== "RESTORE") return;
  try { await api("/api/restore", {method:"POST", body: JSON.stringify({id})}); toast("回档任务已开始"); }
  catch(e){ toast("回档启动失败", true); }
  refresh();
}
$("btnBackup").onclick = async () => {
  try { await api("/api/backup", {method:"POST", body:"{}"}); toast("备份任务已开始"); }
  catch(e){ toast("备份启动失败", true); }
  refresh();
};
$("btnStart").onclick = () => power("start");
$("btnStop").onclick = () => power("stop");
$("btnRestart").onclick = () => power("restart");
async function power(action){
  if (action !== "start" && !confirm("确认" + (action === "stop" ? "停止" : "重启") + "服务器？")) return;
  try { await api("/api/power", {method:"POST", body: JSON.stringify({action})}); }
  catch(e){ toast("操作失败", true); }
  refresh();
}
$("cmdForm").onsubmit = async e => {
  e.preventDefault();
  const v = $("cmd").value.trim();
  if (!v) return;
  try { await api("/api/command", {method:"POST", body: JSON.stringify({line: v})}); }
  catch(err){ toast("发送失败（服务端未运行？）", true); }
  $("cmd").value = "";
};

/* ---------------- ui prefs ---------------- */
const KEY_LINES = "wtm.lines", KEY_FOLD = "wtm.folded";
const savedLines = localStorage.getItem(KEY_LINES);
if (savedLines) $("lineSel").value = savedLines;
function applyFold(fold){
  $("consoleCard").classList.toggle("folded", fold);
  $("btnFold").textContent = fold ? "展开" : "折叠";
  localStorage.setItem(KEY_FOLD, fold ? "1" : "0");
}
applyFold(localStorage.getItem(KEY_FOLD) === "1");
$("btnFold").onclick = () => applyFold(!$("consoleCard").classList.contains("folded"));
$("lineSel").onchange = () => { localStorage.setItem(KEY_LINES, $("lineSel").value); renderConsole(); };

/* ---------------- boot ---------------- */
$("loginForm").onsubmit = async e => {
  e.preventDefault();
  $("loginErr").textContent = "";
  const res = await fetch("/api/login", {
    method:"POST", headers:{"Content-Type":"application/json"},
    body: JSON.stringify({password: $("pw").value})
  });
  if (!res.ok){ $("loginErr").textContent = "口令错误，或失败次数过多"; return; }
  csrf = cookie("wtm_csrf");
  $("pw").value = "";
  showApp();
  await refresh();
  connectLogs();
  setInterval(refresh, 4000);
};

(async function boot(){
  if (location.search.indexOf("preview") >= 0){ startPreview(); return; }
  csrf = cookie("wtm_csrf");
  try {
    const res = await fetch("/api/status");
    if (res.ok){ showApp(); await refresh(); connectLogs(); setInterval(refresh, 4000); }
    else showLogin();
  } catch(e){ showLogin(); }
})();

/* 设计预览：?preview=1 用假数据渲染，不调用任何 API（只用于截图/文档） */
function startPreview(){
  showApp();
  $("stateTx").textContent = "运行中"; $("dot").className = "dot on";
  $("players").textContent = "4"; $("tps").textContent = "19.9";
  $("uptime").textContent = "6h 12m"; $("disk").textContent = "16.2 GB";
  $("lastBackup").textContent = "10-25 04:00";
  $("jobBar").classList.remove("hidden");
  $("jobStage").textContent = "创建快照"; $("jobFill").style.width = "45%"; $("jobPct").textContent = "45%";
  lines = [
    "10:31:02  [Server thread/INFO]: Starting minecraft server version 1.20.1",
    "10:31:08  [Server thread/INFO]: Preparing level world",
    "10:31:36  [Server thread/INFO]: Done (28.412s)! For help, type help",
    "10:31:36  [wtm] 服务端已就绪",
    "10:32:10  [→] gamerule keepInventory true",
    "10:32:10  [Server thread/INFO]: Gamerule keepInventory is now set to: true",
    "10:32:44  [Server thread/INFO]: zyz joined the game",
    "10:33:02  [Server thread/INFO]: dumme joined the game",
    "10:35:19  [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 2100ms behind",
    "10:36:01  [wtm] 开始备份：停止服务端",
    "10:36:01  [wtm] 创建快照 20261025-103601",
    "10:36:12  [wtm] 快照完成，启动服务端",
    "10:36:41  [Server thread/INFO]: Done (24.118s)! For help, type help"
  ];
  renderConsole();
  const tb = $("bkRows"); tb.textContent = "";
  const demo = [
    ["10-25 04:00","auto","自动","1.9 GB"],
    ["10-25 00:00","auto","自动","1.9 GB"],
    ["10-24 20:00","manual","手动","1.9 GB"]
  ];
  for (const d of demo){
    const tr = document.createElement("tr");
    tr.innerHTML = "<td>" + d[0] + "</td><td><span class='tag " + d[1] + "'>" + d[2] + "</span></td><td>" + d[3] + "</td><td class='act'><button class='btn'>回档</button></td>";
    tb.appendChild(tr);
  }
  $("bkCount").textContent = "3 个";
  connectPreviewFeed();
}
function connectPreviewFeed(){
  const pool = [
    "10:37:20  [Server thread/INFO]: <dumme> 有人在下界吗",
    "10:37:31  [Server thread/INFO]: <zyz> 我在挖远古残骸",
    "10:38:02  [wtm] 自动备份将在 04:00 执行",
    "10:38:44  [Server thread/INFO]: Elysia joined the game"
  ];
  let i = 0;
  setInterval(() => { pushLine(pool[i % pool.length]); i++; }, 2600);
}
</script>
</body>
</html>
""".replace("__APP__", appName);
    }
}
