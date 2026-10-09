const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname,'../web/app.js'),'utf8');
class Element {
  constructor(tag) { this.tagName=tag; this.children=[]; this.hidden=false; this.isConnected=true; this.text=''; }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children=nodes; this.text=''; }
  setAttribute() {}
  set textContent(value) { this.text=String(value); this.children=[]; }
  get textContent() { return this.text+this.children.map(item=>item.textContent).join(''); }
}
const connection = state => ({state,title:state,detail:'Connection fixture',ready:state!=='offline',granted:state==='connecting'||state==='ready',connected:state==='ready',uid:2000,mode:'ADB'});
const flush = () => new Promise(resolve=>setImmediate(resolve));
async function setup(initial='unauthorized') {
  const elements = new Map();
  const get = id => { if(!elements.has(id)) elements.set(id,new Element('div')); return elements.get(id); };
  get('content').hidden=true;
  const native = {connection:connection(initial),permissionError:null,completePermissionImmediately:false};
  const identity = {pluginId:'test.shizuku',sessionId:'test-session'};
  const transport = {onmessage:null,postMessage(raw) {
    const message=JSON.parse(raw);
    queueMicrotask(()=>{
      if(message.kind==='hello') {transport.onmessage({data:JSON.stringify({...identity,kind:'ready',payload:{}})});return;}
      let result={};
      if(message.method==='app.getSession') result={hasReturnTarget:false};
      else if(message.method==='shizuku.getConnection') result=native.connection;
      else if(message.method==='shizuku.requestPermission') {
        if(native.permissionError) {
          transport.onmessage({data:JSON.stringify({...identity,kind:'response',requestId:message.requestId,ok:false,error:native.permissionError})});
          return;
        }
        result={requested:true};
        if(native.completePermissionImmediately) native.connection=connection('ready');
      } else if(message.method==='shizuku.connect') result={connecting:true};
      transport.onmessage({data:JSON.stringify({...identity,kind:'response',requestId:message.requestId,ok:true,result})});
    });
  }};
  const document = {querySelector:selector=>({content:selector.includes('ats-plugin-id')?identity.pluginId:identity.sessionId}),getElementById:get,
    createElement:tag=>new Element(tag),createElementNS:(_,tag)=>new Element(tag),createTextNode:text=>({textContent:text})};
  const context={document,window:{atsTransport:transport},setTimeout,clearTimeout,Map,Promise,JSON};
  vm.createContext(context);vm.runInContext(source,context);await flush();
  function find(root,label) { if(root.tagName==='button'&&root.textContent===label)return root;for(const child of root.children||[]){const result=find(child,label);if(result)return result;} }
  return {native,get,button:label=>find(get('steps'),label),event:async(name,payload={})=>{
    transport.onmessage({data:JSON.stringify({...identity,kind:'event',event:name,payload})});await flush();
  }};
}
test('授权等待提示在真实桥接状态事件确认ready后消失',async()=>{
  const ui=await setup();
  await ui.button('请求授权').onclick();
  assert.match(ui.get('message').textContent,/授权窗口确认/);
  ui.native.connection=connection('ready');
  await ui.event('shizuku.connectionChanged',{state:'ready'});
  assert.equal(ui.get('message').hidden,true);
  assert.equal(ui.get('metrics').hidden,false);
});
test('请求返回后的状态回读已就绪时不留下等待或额外成功提示',async()=>{
  const ui=await setup();ui.native.completePermissionImmediately=true;
  await ui.button('请求授权').onclick();
  assert.equal(ui.get('message').hidden,true);
  assert.equal(ui.get('status-title').textContent,'ready');
});
test('连接中的提示在后台收到相关状态变化后也清除',async()=>{
  const ui=await setup('connecting');
  await ui.button('连接服务').onclick();
  assert.match(ui.get('message').textContent,/正在连接/);
  await ui.event('app.visibilityChanged',{visible:false});
  await ui.event('shizuku.connectionChanged',{state:'ready'});
  assert.equal(ui.get('message').hidden,true);
});
test('真实操作错误不被后续成功状态回读或事件误清',async()=>{
  const ui=await setup();ui.native.permissionError={code:'CONSENT_REQUIRED',message:'授权请求失败'};
  await ui.button('请求授权').onclick();
  ui.native.connection=connection('ready');
  await ui.event('shizuku.connectionChanged',{state:'ready'});
  assert.equal(ui.get('message').hidden,false);
  assert.equal(ui.get('message').textContent,'授权请求失败');
});
test('手动刷新结果保留，其他能力权限事件不改变本连接提示',async()=>{
  const ui=await setup('ready');
  await ui.get('refresh').onclick();
  assert.equal(ui.get('message').textContent,'连接状态已更新');
  await ui.event('shizuku.connectionChanged',{state:'ready'});
  await ui.event('app.permissionsChanged',{capability:'app',granted:true});
  assert.equal(ui.get('message').hidden,false);
  assert.equal(ui.get('message').textContent,'连接状态已更新');
});