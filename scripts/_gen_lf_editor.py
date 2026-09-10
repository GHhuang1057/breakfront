#!/usr/bin/env python3
"""生成 Leaflet 版地图编辑器 JS 段，替换 index.html 中的旧 Canvas 实现（2026-09-10）。"""
import pathlib

HTML = pathlib.Path(r"G:/BF_MC/core/src/main/resources/assets/bfadmin/index.html")
src = HTML.read_text(encoding="utf-8")

start_marker = "/* ================= 地图俯瞰可视化编辑器 ================= */"
end_marker = "function switchTab(name){"
i = src.index(start_marker)
j = src.index(end_marker)

NEW_JS = r"""/* ================= 地图俯瞰编辑器（Leaflet + CRS.Simple，2026-09-10 重写） =================
 * 世界坐标映射：MC 的 (x, z) 直接作为 CRS.Simple 的 (lat, lng)。
 * CRS.Simple 默认变换 (1,0,-1,0)：lat 向下为负、lng 向右为正 —— 与 MC 的
 * +X 向东、+Z 向南完全一致，因此无需任何坐标翻转。
 *
 * 交互模型（明确、不靠猜）：
 *   - 每个据点 = L.rectangle(bounds)，bounds 由 中心(x,z) ± 半径r 得到（radius 是半边长）
 *   - 编辑扇区的据点：中心挂一个「不可见可拖拽 marker」作移动把手，四角挂缩放手柄
 *   - 移动/缩放过程中只改本地 z.x/z.z/z.r 并重画该据点的 bounds，拖拽结束才下发服务端
 *   - 其它扇区的据点：淡显、只读
 * ============================================================ */
const mapState = {
  raw:null, model:null, selId:null,
  terrainOn:true, lf:null, layers:null,
  zoneLayer:new Map(),      // zoneId -> {rect, center, handles:[]}
  _ready:false, _fitDone:false
};
let undoStack = [];
const SEC_COLORS = ['#4da6ff','#f5cd54','#6fe873','#ff5a52','#b78bff','#ff9d4d','#4dd0e1'];
const r1 = v => Math.round(v*10)/10;
const clamp = (v,a,b)=> Math.max(a, Math.min(b, v));
const fmt = v => (Math.round(v*100)/100).toString();
function setMapHint(s){ const e=$('maphint'); if(e) e.textContent=s; }

/* 视角持久化 */
function saveCam(){
  if(!mapState.lf) return;
  try{ const c=mapState.lf.getCenter();
    localStorage.setItem('bfadmin_cam', JSON.stringify({cx:c.lat,cz:c.lng,zoom:mapState.lf.getZoom()}));
  }catch(e){}
}
function loadCam(){
  try{ const s=localStorage.getItem('bfadmin_cam'); if(!s) return null;
    const o=JSON.parse(s); return (o&&typeof o.zoom==='number')?o:null;
  }catch(e){ return null; }
}

async function getMapRaw(){ return await api('/bfadmin/api/map?token='+encodeURIComponent(TOKEN)); }

/* 归一化：兼容 sectors[].zones[]{id,x,z,r} 与顶层 zones[]{zoneId,letter,x,z,radius} */
function normalizeMap(r){
  const out={sectors:[],editorSectorIdx:0,spawns:{}};
  if(!r) return out;
  out.sectors=(r.sectors||[]).map(s=>({
    id:s.id, name:s.name||('扇区'+((s.id!=null)?s.id:'?')),
    zones:(s.zones||[]).map(z=>({id: z.id!=null?z.id:z.zoneId, letter:z.letter,
      x:+z.x, z:+z.z, r:+(z.r!=null?z.r:z.radius)||0}))
  }));
  let eidx=(r.editorSectorIdx!=null)?r.editorSectorIdx:-1;
  if(eidx<0||eidx>=out.sectors.length) eidx=0;
  out.editorSectorIdx=eidx;
  if(r.zones && r.zones.length && !out.sectors.some(s=>s.zones&&s.zones.length)){
    const zs=r.zones.map(z=>({id:z.zoneId!=null?z.zoneId:z.id, letter:z.letter,
      x:+z.x, z:+z.z, r:+(z.radius!=null?z.radius:z.r)||0}));
    if(out.sectors.length) out.sectors[out.editorSectorIdx].zones=zs;
    else { out.sectors=[{id:0,name:'扇区1',zones:zs}]; out.editorSectorIdx=0; }
  }
  const sp=r.spawns||{};
  out.spawns.attacker=Array.isArray(sp.attacker)?{x:+sp.attacker[0],z:+sp.attacker[2]}
    :(sp.attacker&&sp.attacker.x!=null?{x:+sp.attacker.x,z:+sp.attacker.z}:null);
  out.spawns.defender=Array.isArray(sp.defender)?{x:+sp.defender[0],z:+sp.defender[2]}
    :(sp.defender&&sp.defender.x!=null?{x:+sp.defender.x,z:+sp.defender.z}:null);
  return out;
}
function findZone(id){
  if(!mapState.model||id==null) return null;
  for(const s of mapState.model.sectors){
    const z=s.zones.find(z=>String(z.id)===String(id));
    if(z) return z;
  }
  return null;
}
function selZone(){ return findZone(mapState.selId); }
function zoneBounds(x,z,r){ return [[x-r, z-r],[x+r, z+r]]; }   // [[lat1,lng1],[lat2,lng2]]

/* ---- 初始化 ---- */
function lfInit(){
  if(mapState._ready) return true;
  if(window.__leafletFailed || typeof L==='undefined'){
    setMapHint('地图库（Leaflet）未加载：请检查网络，或改用「控制台」页的表单编辑');
    const box=$('lfmap');
    if(box) box.innerHTML='<div style="padding:24px;color:#7c8a99;font-size:13px;line-height:1.9">'
      +'<b style="color:#f5cd54">地图引擎未加载</b><br>'
      +'Leaflet 未能从 CDN 载入（离线或被网络策略拦截）。<br>'
      +'如需完全离线部署，可将 leaflet.js / leaflet.css 放入服务端资源改为本地引用；<br>'
      +'在此之前，控制台页的表格表单仍可新增 / 移动 / 删除据点。</div>';
    return false;
  }
  const lf=L.map('lfmap',{
    crs:L.CRS.Simple, minZoom:-3, maxZoom:6, zoomSnap:0.25, zoomDelta:0.5,
    attributionControl:true, zoomControl:true, doubleClickZoom:false
  });
  lf.setView([0,0],0);
  lf.attributionControl.setPrefix('BREAKFRONT 地图编辑器');
  lf.attributionControl.addAttribution('x/z = MC 世界坐标');

  mapState.lf=lf;
  // pane 顺序：地形底 < 网格 < 其它扇区 < 编辑扇区 < 出生点 < 手柄
  lf.createPane('pTerrain').style.zIndex=200;
  lf.createPane('pGrid').style.zIndex=210;
  lf.createPane('pOther').style.zIndex=300;
  lf.createPane('pEdit').style.zIndex=400;
  lf.createPane('pSpawn').style.zIndex=500;
  lf.createPane('pHandles').style.zIndex=600;
  mapState.layers={
    terrain:L.layerGroup([],{pane:'pTerrain'}).addTo(lf),
    grid:L.layerGroup([],{pane:'pGrid'}).addTo(lf),
    other:L.layerGroup([],{pane:'pOther'}).addTo(lf),
    edit:L.layerGroup([],{pane:'pEdit'}).addTo(lf),
    spawn:L.layerGroup([],{pane:'pSpawn'}).addTo(lf),
    handles:L.layerGroup([],{pane:'pHandles'}).addTo(lf)
  };
  lf.on('moveend zoomend', ()=>{ saveCam(); lfDrawGrid(); });
  lf.on('click', ()=>{ mapState.selId=null; lfRender(); showZoneInfo(); });
  lf.on('dblclick', onLfDbl);
  mapState._ready=true;
  return true;
}

/* ---- 视角适配 ---- */
function lfFit(){
  if(!lfInit()) return;
  const lf=mapState.lf, m=mapState.model;
  const pts=[];
  const s=m&&m.sectors?m.sectors[m.editorSectorIdx]:null;
  if(s) s.zones.forEach(z=>{ pts.push([z.x-z.r,z.z-z.r],[z.x+z.r,z.z+z.r]); });
  if(m&&m.spawns.attacker) pts.push([m.spawns.attacker.x,m.spawns.attacker.z]);
  if(m&&m.spawns.defender) pts.push([m.spawns.defender.x,m.spawns.defender.z]);
  if(!pts.length){ lf.setView([0,0],0); saveCam(); return; }
  lf.fitBounds(L.latLngBounds(pts).pad(0.25),{maxZoom:2});
  saveCam();
}

/* ---- 网格 ---- */
function lfDrawGrid(){
  const lf=mapState.lf; if(!lf||!mapState.layers) return;
  const g=mapState.layers.grid; g.clearLayers();
  const b=lf.getBounds(); if(!b.isValid()) return;
  const x0=b.getSouth(), x1=b.getNorth(), z0=b.getWest(), z1=b.getEast();
  const span=Math.max(x1-x0, z1-z0);
  let step=512;
  for(const t of [8,16,32,64,128,256,512]){ if(span/t<=26){ step=t; break; } }
  const mkLine=(a,b2)=>L.polyline([a,b2],{color:'#1b2632',weight:1,interactive:false,opacity:0.85}).addTo(g);
  const mkAxis=(a,b2)=>L.polyline([a,b2],{color:'#3a4a5e',weight:1.6,interactive:false}).addTo(g);
  for(let z=Math.floor(z0/step)*step; z<=z1; z+=step){
    (Math.abs(z)<1e-6?mkAxis:mkLine)([x0,z],[x1,z]);
  }
  for(let x=Math.floor(x0/step)*step; x<=x1; x+=step){
    (Math.abs(x)<1e-6?mkAxis:mkLine)([x,z0],[x,z1]);
  }
  for(let x=Math.floor(x0/step)*step; x<=x1; x+=step){
    L.marker([x,z0],{interactive:false,pane:'pGrid',
      icon:L.divIcon({className:'lf-spawn-label',html:'x'+fmt(x),iconSize:null})}).addTo(g);
  }
  for(let z=Math.floor(z0/step)*step; z<=z1; z+=step){
    L.marker([x0,z],{interactive:false,pane:'pGrid',
      icon:L.divIcon({className:'lf-spawn-label',html:'z'+fmt(z),iconSize:null})}).addTo(g);
  }
}

/* ---- 全量渲染 ---- */
function lfRender(){
  const lf=mapState.lf; if(!lf||!mapState.layers) return;
  const Ls=mapState.layers;
  ['other','edit','spawn','handles'].forEach(k=>Ls[k].clearLayers());
  mapState.zoneLayer.clear();
  const m=mapState.model; if(!m) return;

  m.sectors.forEach((s,si)=>{
    const isEdit=(si===m.editorSectorIdx);
    const pane=isEdit?'pEdit':'pOther';
    const col=SEC_COLORS[si%SEC_COLORS.length];
    s.zones.forEach(z=>{
      const rect=L.rectangle(zoneBounds(z.x,z.z,z.r),{
        pane, color:isEdit?'#f5cd54':'#33414f', weight:isEdit?2:1,
        opacity:isEdit?1:0.5, fillColor:col, fillOpacity:isEdit?0.40:0.13,
        interactive:true, bubblingMouseEvents:false
      });
      rect.on('mousedown', ev=>{
        if(ev.originalEvent) ev.originalEvent._bfStop=true;
        if(isEdit && mapState.selId!==z.id){
          mapState.selId=z.id; lfRender(); showZoneInfo();
        } else if(!isEdit){
          setMapHint('据点 '+z.id+' 属于扇区 '+(si+1)+'，请先切换扇区再编辑');
          mapState.selId=z.id; lfRender(); showZoneInfo();
        }
        L.DomEvent.stopPropagation(ev);
      });
      rect.addTo(isEdit?Ls.edit:Ls.other);

      if(isEdit && z.r>0){
        L.marker([z.x,z.z],{interactive:false,pane:'pEdit',
          icon:L.divIcon({className:'lf-zone-label',html:String(z.letter!=null?z.letter:z.id),iconSize:null})})
          .addTo(Ls.edit);
      }
      mapState.zoneLayer.set(String(z.id),{rect,zone:z,isEdit,si});
    });
  });

  if(m.spawns.attacker) lfSpawnMarker(m.spawns.attacker,'攻','att');
  if(m.spawns.defender) lfSpawnMarker(m.spawns.defender,'守','def');

  lfRenderSelection();
}

/* 出生点标记 */
function lfSpawnMarker(p,label,cls){
  const c=(cls==='att')?'#4da6ff':'#ff5a52';
  L.circle([p.x,p.z],{pane:'pSpawn',radius:2.5,color:c,fillColor:c,fillOpacity:0.95,weight:1,interactive:false})
    .addTo(mapState.layers.spawn);
  L.marker([p.x,p.z],{pane:'pSpawn',icon:L.divIcon({className:'lf-spawn-label '+cls,html:'<b>'+label+'</b>',iconSize:null})})
    .addTo(mapState.layers.spawn);
}

/* 选中态：高亮 + 移动把手（中心）+ 四角缩放手柄 */
function lfRenderSelection(){
  const Ls=mapState.layers; if(!Ls) return;
  Ls.handles.clearLayers();
  const z=selZone();
  if(!z) return;
  const entry=mapState.zoneLayer.get(String(z.id));
  if(entry&&entry.isEdit){
    // 中心移动把手：不可见但可拖拽的 marker
    const center=L.marker([z.x,z.z],{
      draggable:true, pane:'pHandles', opacity:0, zIndexOffset:900,
      icon:L.divIcon({className:'bf-move-grip',html:'',iconSize:[26,26]})
    });
    center._st=null;
    center.on('dragstart', ()=>{ center._st={x:z.x,z:z.z,r:z.r}; });
    center.on('drag', ()=>{
      const ll=center.getLatLng();
      const dx=ll.lat-center._st.x, dz=ll.lng-center._st.z;
      z.x=Math.round((center._st.x+dx)*10)/10;
      z.z=Math.round((center._st.z+dz)*10)/10;
      entry.rect.setBounds(zoneBounds(z.x,z.z,z.r));
      const lbl=entry.label;
      showZoneInfo();
    });
    center.on('dragend', ()=>{
      const st=center._st; center._st=null;
      if(!st) return;
      if(Math.abs(z.x-st.x)>0.05||Math.abs(z.z-st.z)>0.05){
        applyEdit('move',{id:z.id,x:r1(z.x),z:r1(z.z)});
      } else { lfRender(); }
    });
    center.addTo(Ls.handles);

    // 四角缩放手柄：拖角 → 对角固定，半径与中心同步变化
    const corners=[
      {tag:'nw', fx:-1, fz:-1},
      {tag:'ne', fx: 1, fz:-1},
      {tag:'sw', fx:-1, fz: 1},
      {tag:'se', fx: 1, fz: 1}
    ];
    corners.forEach(c=>{
      const hx=z.x+c.fx*z.r, hz=z.z+c.fz*z.r;
      const mk=L.marker([hx,hz],{draggable:true,pane:'pHandles',zIndexOffset:1000,
        icon:L.divIcon({className:'lf-handle',iconSize:[10,10]})});
      // 固定对角点
      const anchor={x:z.x-c.fx*z.r, z:z.z-c.fz*z.r};
      mk._st=null;
      mk.on('dragstart', ()=>{ mk._st={x:z.x,z:z.z,r:z.r}; });
      mk.on('drag', ()=>{
        const ll=mk.getLatLng();
        const nr=Math.max(1, Math.max(Math.abs(ll.lat-anchor.x), Math.abs(ll.lng-anchor.z))/2);
        z.r=Math.round(nr*10)/10;
        z.x=Math.round((anchor.x+c.fx*nr)*10)/10;
        z.z=Math.round((anchor.z+c.fz*nr)*10)/10;
        entry.rect.setBounds(zoneBounds(z.x,z.z,z.r));
        showZoneInfo();
      });
      mk.on('dragend', ()=>{
        const st=mk._st; mk._st=null;
        if(!st) return;
        const moved=Math.abs(z.x-st.x)>0.05||Math.abs(z.z-st.z)>0.05||Math.abs(z.r-st.r)>0.05;
        if(moved){
          const target={x:r1(z.x),z:r1(z.z),r:r1(z.r)};
          applyEditMulti(z.id, target, st);
        } else { lfRender(); }
      });
      mk.addTo(Ls.handles);
    });
  } else if(entry){
    // 非编辑扇区：只给虚线提示
    L.rectangle(zoneBounds(z.x,z.z,z.r),{pane:'pHandles',color:'#f5cd54',weight:2,dashArray:'5,4',fill:false,interactive:false})
      .addTo(Ls.handles);
  }
}

/* 一次性提交 move+resize（顺序：先 resize 再 move，服务端各自独立） */
async function applyEditMulti(id, target, before){
  const ops=[];
  if(Math.abs(target.r-before.r)>0.05) ops.push(['resize',{id,r:target.r}]);
  if(Math.abs(target.x-before.x)>0.05||Math.abs(target.z-before.z)>0.05) ops.push(['move',{id,x:target.x,z:target.z}]);
  if(mapState.raw) undoStack.push(JSON.parse(JSON.stringify(mapState.raw)));
  for(const [op,f] of ops) await sendEdit(op,f,false);
  await mapLoad(true);
}

/* 双击空白新建据点 */
function onLfDbl(e){
  if(!mapState.model) return;
  if(e.originalEvent && e.originalEvent._bfStop) return;
  mapState.selId=null;
  applyEdit('add',{x:r1(e.latlng.lat), z:r1(e.latlng.lng), r:6});
}

/* ---- 地形底图 ---- */
async function loadTerrain(){
  if(!mapState.lf||!mapState.model||!mapState.terrainOn) return;
  const lf=mapState.lf;
  const b=lf.getBounds();
  if(!b.isValid()) return;
  const cx=Math.round((b.getNorth()+b.getSouth())/2);
  const cz=Math.round((b.getWest()+b.getEast())/2);
  const half=Math.min(240, Math.max(64, Math.ceil(Math.max(
    (b.getNorth()-b.getSouth())/2, (b.getEast()-b.getWest())/2)*1.25)));
  let r;
  try{ r=await api('/bfadmin/api/mapterrain?cx='+cx+'&cz='+cz+'&half='+half+'&step=2'); }
  catch(e){ return; }
  if(!r||!r.ok||!r.b64) return;
  try{
    const w=r.w,h=r.h,step=r.step;
    const bin=atob(r.b64);
    const cv=document.createElement('canvas'); cv.width=w; cv.height=h;
    const c2=cv.getContext('2d');
    const img=c2.createImageData(w,h); const d=img.data;
    for(let i=0;i<w*h;i++){
      d[i*4]=bin.charCodeAt(i*3); d[i*4+1]=bin.charCodeAt(i*3+1); d[i*4+2]=bin.charCodeAt(i*3+2); d[i*4+3]=255;
    }
    c2.putImageData(img,0,0);
    mapState.layers.terrain.clearLayers();
    L.imageOverlay(cv.toDataURL('image/png'),
      [[r.x0, r.z0],[r.x0+w*step, r.z0+h*step]],
      {opacity:0.9,interactive:false,pane:'pTerrain'}).addTo(mapState.layers.terrain);
  }catch(e){ /* 底图失败不影响编辑 */ }
}
function toggleTerrain(){
  mapState.terrainOn=!mapState.terrainOn;
  const b=$('btn-terrain'); if(b) b.textContent='地形底图 '+(mapState.terrainOn?'ON':'OFF');
  if(mapState.layers&&mapState.layers.terrain){
    if(mapState.terrainOn) loadTerrain(); else mapState.layers.terrain.clearLayers();
  }
}

/* ---- 读取并刷新 ---- */
async function mapLoad(keepUndo){
  if(!TOKEN) return;
  const r=await getMapRaw();
  if(!r.ok){ setMapHint(r.msg||'读取失败'); return; }
  mapState.raw=r; mapState.model=normalizeMap(r);
  if(!keepUndo){ undoStack=[]; mapState.selId=null; }
  const sp=mapState.model.spawns||{};
  if(sp.attacker){ $('s-ax').value=sp.attacker.x; $('s-az').value=sp.attacker.z; }
  if(sp.defender){ $('s-dx').value=sp.defender.x; $('s-dz').value=sp.defender.z; }
  setMapHint('共 '+r.totalZones+' 个据点 · '+(r.sectors||[]).length+' 扇区 · 编辑中：扇区 '
    +(mapState.model.editorSectorIdx+1));
  loadMap();
  renderSectorList();
  if(!lfInit()) return;
  if(!mapState._fitDone){
    const saved=loadCam();
    mapState._fitDone=true;
    if(saved) mapState.lf.setView([saved.cx,saved.cz], saved.zoom); else lfFit();
  }
  lfRender(); lfDrawGrid(); showZoneInfo(); loadTerrain();
}

/* ---- 侧栏扇区列表 ---- */
function escHtml(s){ return String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
function renderSectorList(){
  const box=$('sectorlist'); if(!box) return;
  box.innerHTML='';
  const m=mapState.model; if(!m) return;
  m.sectors.forEach((s,si)=>{
    const d=el('div','sectorrow'+(si===m.editorSectorIdx?' cur':''));
    d.innerHTML='<b>'+(si+1)+'. '+escHtml(s.name)+'</b><br>'+s.zones.length+' 个据点'
      +(si===m.editorSectorIdx?' <span style="color:#f5cd54">◂ 编辑中</span>':'');
    d.onclick=()=>jumpToSector(si);
    box.appendChild(d);
  });
}
async function jumpToSector(si){
  const m=mapState.model; if(!m||si===m.editorSectorIdx) return;
  const steps=si-m.editorSectorIdx, op=steps>0?'sectorNext':'sectorPrev';
  setMapHint('切换扇区…');
  for(let i=0;i<Math.abs(steps);i++) await sendEdit(op,{},false);
  await mapLoad(true);
  setTimeout(()=>lfFit(),60);
}

/* ---- 编辑下发 ---- */
async function applyEdit(op, fields){
  if(!TOKEN){ expireAuth(); return {ok:false, code:401}; }
  if(mapState.raw) undoStack.push(JSON.parse(JSON.stringify(mapState.raw)));
  if(undoStack.length>80) undoStack.shift();
  const r=await sendEdit(op, fields, false);
  if(r.ok){ await mapLoad(true); }
  else setMapHint('操作失败：'+(r.msg||r.code));
  return r;
}
async function sendEdit(op, fields, doReload){
  if(!TOKEN){ expireAuth(); return {ok:false, code:401}; }
  const payload=Object.assign({}, fields||{});
  const body={token:TOKEN, op:op, name:op, side:(fields&&fields.side)||''};
  for(const k in fields) body[k]=fields[k];
  body.payload=payload;                    // 双份下发：服务端 payload 优先
  const r=await api('/bfadmin/api/mapedit',{method:'POST',
    headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
  log((r.ok?'✓ ':'✗ ')+op+(r.msg?' — '+r.msg:''));
  return r;
}
async function mapOp(op, side){
  const fields={};
  if(op==='spawn') fields.side=side||'attacker';
  const r=await sendEdit(op, fields, true);
  if(r.ok || ['sectorNext','sectorPrev','load'].includes(op)) await mapLoad(true);
}

/* 撤销：把当前编辑扇区还原到上一个快照 */
async function doUndo(){
  if(!undoStack.length){ setMapHint('没有可撤销的操作'); return; }
  const snap=undoStack.pop();
  const cur=await getMapRaw();
  if(!cur.ok){ setMapHint('撤销失败：读取失败'); return; }
  const snapM=normalizeMap(snap), curM=normalizeMap(cur);
  const si=curM.editorSectorIdx;
  const sZ=(snapM.sectors[si]||{zones:[]}).zones, cZ=(curM.sectors[si]||{zones:[]}).zones;
  for(const c of cZ){ if(!sZ.find(z=>String(z.id)===String(c.id))) await sendEdit('remove',{id:c.id},false); }
  for(const z of sZ){ if(!cZ.find(c=>String(c.id)===String(z.id))) await sendEdit('add',{x:z.x,z:z.z,r:z.r},false); }
  for(const z of sZ){
    const c=cZ.find(c=>String(c.id)===String(z.id));
    if(!c) continue;
    if(Math.abs(c.x-z.x)>0.05||Math.abs(c.z-z.z)>0.05) await sendEdit('move',{id:z.id,x:z.x,z:z.z},false);
    if(Math.abs(c.r-z.r)>0.05) await sendEdit('resize',{id:z.id,r:z.r},false);
  }
  await mapLoad(true);
  setMapHint('已撤销一步');
}

/* ---- 侧栏信息 ---- */
function showZoneInfo(){
  const z=selZone();
  if(!z){ $('zoneinfo').innerHTML='未选中据点<br><span class="hint">点击地图中的方块进行选中</span>';
    $('sideedit').style.display='none'; return; }
  $('zoneinfo').innerHTML='已选中：<b>'+(z.letter!=null?z.letter:'#'+z.id)+'</b> （id='+z.id+'）'
    +'<br><span class="hint">中心 (x,z) 即方块中心，半径 = 半边长</span>';
  $('sideedit').style.display='block';
  $('s-letter').value=z.letter!=null?z.letter:z.id;
  $('s-x').value=r1(z.x); $('s-z').value=r1(z.z); $('s-r').value=r1(z.r);
  updateSideCalc();
}
function updateSideCalc(){
  const x=+$('s-x').value||0, z=+$('s-z').value||0, r=+$('s-r').value||0;
  $('sidecalc').textContent='方块范围 X['+fmt(x-r)+','+fmt(x+r)+']  Z['+fmt(z-r)+','+fmt(z+r)+']';
}
async function applySideEdit(){
  const z=selZone(); if(!z) return;
  const nx=+$('s-x').value, nz=+$('s-z').value, nr=+$('s-r').value;
  if(isNaN(nx)||isNaN(nz)||isNaN(nr)||nr<1){ setMapHint('坐标/半径无效'); return; }
  if(nx!==z.x||nz!==z.z) await applyEdit('move',{id:z.id,x:r1(nx),z:r1(nz)});
  if(nr!==z.r) await applyEdit('resize',{id:z.id,r:r1(nr)});
}
async function applySpawn(side){
  const x=side==='attacker'?(+$('s-ax').value):(+$('s-dx').value);
  const z=side==='attacker'?(+$('s-az').value):(+$('s-dz').value);
  if(isNaN(x)||isNaN(z)){ setMapHint('出生点坐标无效'); return; }
  await applyEdit('spawn',{side, x:r1(x), z:r1(z)});
}
function delSelected(){
  if(mapState.selId==null) return;
  const id=mapState.selId; mapState.selId=null;
  applyEdit('remove',{id});
}

/* 键盘 */
window.addEventListener('keydown', e=>{
  if(e.target&&(e.target.tagName==='INPUT'||e.target.tagName==='SELECT'||e.target.tagName==='TEXTAREA')) return;
  const pane=$('tab-map'); if(!pane||pane.style.display==='none') return;
  if((e.key==='Delete'||e.key==='Backspace')&&mapState.selId!=null){ e.preventDefault(); delSelected(); }
  if((e.ctrlKey||e.metaKey)&&(e.key==='z'||e.key==='Z')){ e.preventDefault(); doUndo(); }
  if(e.key==='Escape'){ mapState.selId=null; lfRender(); showZoneInfo(); }
});

"""

src = src[:i] + NEW_JS + src[j:]
HTML.write_text(src, encoding="utf-8")
print("replaced OK; new size:", len(src))
