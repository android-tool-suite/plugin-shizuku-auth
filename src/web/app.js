(() => {
  const pluginId = document.querySelector('meta[name="ats-plugin-id"]')?.content;
  const sessionId = document.querySelector('meta[name="ats-session-id"]')?.content;
  const transport = window.atsTransport;
  if (!pluginId || !sessionId || !transport) return;

  let nextId = 1;
  let readyResolve, readyReject;
  const ready = new Promise((resolve,reject) => { readyResolve = resolve; readyReject = reject; });
  const readyTimeout = setTimeout(() => readyReject(new Error('无法连接宿主，请返回后重新打开工具。')),5000);
  const pending = new Map();
  transport.onmessage = event => {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }
    if (message.pluginId !== pluginId || message.sessionId !== sessionId) return;
    if (message.kind === 'ready') { clearTimeout(readyTimeout); readyResolve(message.payload); return; }
    if (message.kind === 'event') { runtimeEvent(message); return; }
    if (message.kind !== 'response') return;
    const request = pending.get(message.requestId);
    if (!request) return;
    pending.delete(message.requestId);
    clearTimeout(request.timeout);
    if (message.ok) request.resolve(message.result || {});
    else request.reject(Object.assign(new Error(message.error?.message || '操作失败'), message.error || {}));
  };
  transport.postMessage(JSON.stringify({protocol:'2.0',kind:'hello',pluginId,sessionId,requestId:'0',payload:{supportedProtocols:['2.0'],features:[]}}));

  async function call(method, payload = {}, deadlineMs = 30000) {
    await ready;
    const requestId = String(nextId++);
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { pending.delete(requestId); reject(new Error('操作超时')); }, deadlineMs);
      pending.set(requestId, {resolve, reject, timeout});
      transport.postMessage(JSON.stringify({protocol:'2.0',kind:'request',pluginId,sessionId,requestId,method,payload,deadlineMs}));
    });
  }

  const ids = ['content','status-title','status-detail','status-mark','metrics','mode','steps','return-notice','return-task','refresh','progress','message'];
  const elements = Object.fromEntries(ids.map(id => [id,document.getElementById(id)]));
  const state = {connection:null,hasReturnTarget:false,busy:false,visible:true,messageKind:''};
  let refreshPromise,progressTimer;
  const iconPaths = {shield:'M12 3 4 6v6c0 5 8 9 8 9s8-4 8-9V6l-8-3zm-4 9 3 3 5-6',warning:'M12 3 2 21h20L12 3zm0 6v5m0 3v.1',check:'m5 12 4 4L19 6'};
  function node(tag,className,text) {
    const item = document.createElement(tag); if (className) item.className = className;
    if (text != null) item.textContent = text; return item;
  }
  function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg','svg'); svg.setAttribute('viewBox','0 0 24 24'); svg.setAttribute('aria-hidden','true');
    const path = document.createElementNS('http://www.w3.org/2000/svg','path'); path.setAttribute('d',iconPaths[name]); svg.append(path); return svg;
  }
  function message(value,kind = 'result') {
    state.messageKind = value ? kind : '';
    elements.message.textContent = value || ''; elements.message.hidden = !value;
  }
  function clearPendingMessage(connection = null) {
    const kind = state.messageKind;
    if (kind === 'permission-pending' && (!connection || connection.granted === true || connection.state !== 'unauthorized')
      || kind === 'connection-pending' && (!connection || connection.connected === true || connection.state !== 'connecting')) message('');
  }
  function step(index,title,detail,done,label,method) {
    const item = node('div',`connection-step${done ? ' done' : ''}`);
    const number = node('span','step-number'); number.setAttribute('aria-label',done ? '已完成' : '步骤 ' + index);
    number.append(done ? icon('check') : document.createTextNode(String(index)));
    const copy = node('div','step-copy'); copy.append(node('strong','',title),node('p','',detail));
    if (label) {
      const button = node('button','button secondary',label); button.type = 'button'; button.disabled = state.busy;
      button.onclick = () => action(method); copy.append(button);
    }
    item.append(number,copy); return item;
  }
  function render() {
    const connection = state.connection || {state:'unknown',title:'连接状态尚未确认',detail:'重新检查连接后可以继续。'};
    const ready = connection.state === 'ready' && connection.connected === true;
    const running = connection.ready === true,granted = connection.granted === true;
    elements['status-title'].textContent = connection.title;
    elements['status-detail'].textContent = connection.detail;
    elements['status-mark'].className = 'mark' + (ready ? ' ready' : '');
    elements['status-mark'].replaceChildren(icon(ready ? 'shield' : 'warning'));
    elements.metrics.hidden = !ready;
    elements.mode.textContent = ready ? connection.mode || (connection.uid === 0 ? 'Root' : connection.uid === 2000 ? 'ADB' : 'UID ' + connection.uid) : '';
    const steps = node('div','connection-steps card');steps.append(node('h3','steps-heading','连接步骤'));
    steps.append(
      step(1,'启动 Shizuku',running ? '服务已运行。' : '在 Shizuku 应用内完成启动。',running,!running ? '打开 Shizuku' : '', 'shizuku.openApp'),
      step(2,'允许本应用连接',granted ? '已经获得连接授权。' : '由你在授权窗口确认。',granted,running && !granted ? '请求授权' : '', 'shizuku.requestPermission'),
      step(3,'建立系统连接',ready ? '连接可用 · ' + elements.mode.textContent + ' 模式' : '完成前两步后继续连接。',ready,granted && !ready ? '连接服务' : '', 'shizuku.connect')
    );
    elements.steps.replaceChildren(steps);
    if (connection.state === 'denied') {
      const button = node('button','button','查看插件权限'); button.type = 'button'; button.disabled = state.busy; button.onclick = () => action('app.openPermissions');
      elements.steps.replaceChildren(button);
    }
    elements['return-notice'].hidden = !state.hasReturnTarget;
    elements.refresh.disabled = state.busy;
  }
  function setBusy(value) {
    state.busy = value; clearTimeout(progressTimer);
    if (value && !elements.content.hidden) progressTimer = setTimeout(() => { elements.progress.hidden = false; },200);
    else elements.progress.hidden = true;
    render();
  }
  async function refresh(userInitiated = false) {
    if (refreshPromise) return refreshPromise;
    refreshPromise = (async () => {
      try {
        state.connection = await call('shizuku.getConnection',{},5000);
        clearPendingMessage(state.connection);
        if (userInitiated) message('连接状态已更新');
      } catch (error) {
        state.connection = {state:error.code === 'PERMISSION_DENIED' ? 'denied' : 'error',title:error.code === 'PERMISSION_DENIED' ? '尚未允许管理 Shizuku 连接' : '无法检查连接',detail:error.message || '请检查服务后重试。',ready:false,granted:false,connected:false};
        message(error.message || '无法读取连接状态','error');
      } finally { render(); }
    })();
    try { await refreshPromise; } finally { refreshPromise = null; }
  }
  async function action(method) {
    if (state.busy) return;
    setBusy(true); message('');
    try {
      const result = await call(method,{},5000);
      if (method === 'shizuku.requestPermission' && result.requested) message('已提交授权请求，请在 Shizuku 授权窗口确认。','permission-pending');
      if (method === 'shizuku.connect' && result.connecting) message('正在连接系统服务。','connection-pending');
      if (method.startsWith('shizuku.') && method !== 'shizuku.openApp') await refresh();
    } catch (error) { message(error.message || '操作未完成','error'); await refresh(); }
    finally { setBusy(false); }
  }
  function runtimeEvent(event) {
    if (event.event === 'app.visibilityChanged') {
      state.visible = event.payload?.visible !== false;
      if (state.visible) refresh();
    }
    if (event.event === 'shizuku.connectionChanged'
      || event.event === 'app.permissionsChanged' && event.payload?.capability === 'shizuku.control') {
      // Never keep a confirmed connection after a lifecycle/permission change.
      clearPendingMessage();
      state.connection = null; render(); if (state.visible) refresh();
    }
  }
  elements.refresh.onclick = async () => { setBusy(true); await refresh(true); setBusy(false); };
  elements['return-task'].onclick = () => action('app.close');
  (async () => {
    try {
      await ready;
      const session = await call('app.getSession',{},5000); state.hasReturnTarget = session.hasReturnTarget === true;
      await refresh();
    } catch (error) { message(error.message || '无法连接宿主','error'); }
    finally { render(); elements.content.hidden = false; }
  })();
})();
