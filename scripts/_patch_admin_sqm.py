#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把管理台地图编辑器的坐标系改为与 squaremap 一致，并叠加其真实俯瞰瓦片。

为什么要改：squaremap 的瓦片按世界方块轴对齐，其前端约定
    lat = -z / 2^maxZoom,  lng = x / 2^maxZoom
而旧编辑器用的是 lat=x, lng=z。两套约定下同一块瓦片落在不同位置 →
直接叠加必然错位。故统一为 squaremap 约定，并新增 LL()/pt() 两个换算入口。

用法：python scripts/_patch_admin_sqm.py [--check]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

HTML = Path(__file__).resolve().parents[1] / "core/src/main/resources/assets/bfadmin/index.html"

REPL = []


def R(old: str, new: str, tag: str):
    REPL.append((old, new, tag))


# ---------- A. 坐标工具（插在 setMapHint 之后） ----------
R(
    """function setMapHint(s){ const e=$('maphint'); if(e) e.textContent=s; }
""",
    """function setMapHint(s){ const e=$('maphint'); if(e) e.textContent=s; }

/* ================= 坐标系：与 squaremap 完全一致 =================
 * squaremap 的瓦片按世界方块轴对齐，其官方前端把方块坐标换算为
 *     lat = -z / 2^maxZoom,  lng = x / 2^maxZoom
 * 管理台沿用同一约定，才能把它渲染的真实俯瞰瓦片直接叠上去而不错位。
 * 约定：所有「方块坐标 <-> latlng」一律走 LL() / pt()，其余地方不要手写换算。
 */
let SQM_SCALE = 1 / 256;                       // 默认 1/2^8；拿到 squaremap 元数据后按其实 maxZoom 覆写
let SQM_INFO  = {ok:false, world:'world', max:8, extra:2};
function LL(x,z){ return L.latLng(-z*SQM_SCALE, x*SQM_SCALE); }
function pt(ll){ return {x: ll.lng/SQM_SCALE, z: -ll.lat/SQM_SCALE}; }
/** 视图 bounds → 方块坐标范围。 */
function ptBounds(b){
  return {x0:b.getWest()/SQM_SCALE, x1:b.getEast()/SQM_SCALE,
          z0:-b.getNorth()/SQM_SCALE, z1:-b.getSouth()/SQM_SCALE};
}
""",
    "coord-tools",
)

# ---------- B. mapState 增加 squaremap 字段 ----------
R(
    "  _ready:false, _fitDone:false, _terrainKey:'', _terrainTimer:null\n",
    "  _ready:false, _fitDone:false, _terrainKey:'', _terrainTimer:null,\n"
    "  sqmOn:true, _sqmTried:false\n",
    "mapstate-fields",
)

# ---------- C. 视角持久化改用方块坐标 ----------
R(
    """  try{ const c=mapState.lf.getCenter();
    localStorage.setItem('bfadmin_cam', JSON.stringify({cx:c.lat,cz:c.lng,zoom:mapState.lf.getZoom()}));
  }catch(e){}""",
    """  try{ const c=pt(mapState.lf.getCenter());
    localStorage.setItem('bfadmin_cam', JSON.stringify({cx:c.x,cz:c.z,zoom:mapState.lf.getZoom()}));
  }catch(e){}""",
    "savecam",
)

# ---------- D. lfInit：缩放范围/初始视图/pane/layers ----------
R(
    """  const lf=L.map('lfmap',{
    // wheelPxPerZoomLevel 调大 → 滚轮每格缩放更平缓（默认 60 在 CRS.Simple 下过于跳跃）；
    // minZoom 放到 -4：地图跨度可达数千米，需要更远的视野才能一眼看全
    crs:L.CRS.Simple, minZoom:-4, maxZoom:5, zoomSnap:0.25, zoomDelta:0.5,
    wheelPxPerZoomLevel:90, wheelDebounceTime:60,
    attributionControl:true, zoomControl:true, doubleClickZoom:false
  });
  lf.setView([0,0],0);""",
    """  const lf=L.map('lfmap',{
    // 缩放级别与 squaremap 瓦片金字塔一致（0 .. zoom.max+extra），不再使用负 zoom。
    // wheelPxPerZoomLevel 调大 → 滚轮每格缩放更平缓。
    crs:L.CRS.Simple, minZoom:0, maxZoom:SQM_INFO.max+SQM_INFO.extra,
    zoomSnap:1, zoomDelta:1, wheelPxPerZoomLevel:90, wheelDebounceTime:60,
    attributionControl:true, zoomControl:true, doubleClickZoom:false
  });
  lf.setView(LL(0,0),0);""",
    "lfinit-map",
)

R(
    """  // pane 顺序：地形底 < 网格 < 其它扇区 < 编辑扇区 < 出生点 < 手柄
  lf.createPane('pTerrain').style.zIndex=200;""",
    """  // pane 顺序：squaremap 真实瓦片 < 自采样地形 < 网格 < 其它扇区 < 编辑扇区 < 出生点 < 手柄
  lf.createPane('pSqm').style.zIndex=180;
  lf.createPane('pTerrain').style.zIndex=200;""",
    "lfinit-pane",
)

R(
    """  mapState.layers={
    terrain:L.layerGroup([],{pane:'pTerrain'}).addTo(lf),""",
    """  mapState.layers={
    sqm:null,
    terrain:L.layerGroup([],{pane:'pTerrain'}).addTo(lf),""",
    "lfinit-layers",
)

# ---------- E. lfFit ----------
R(
    "  if(s) s.zones.forEach(z=>{ pts.push([z.x-z.r,z.z-z.r],[z.x+z.r,z.z+z.r]); });",
    "  if(s) s.zones.forEach(z=>{ pts.push(LL(z.x-z.r,z.z-z.r),LL(z.x+z.r,z.z+z.r)); });",
    "lffit-zones",
)
R(
    """    if(m&&m.spawns.attacker) pts.push([m.spawns.attacker.x,m.spawns.attacker.z]);
    if(m&&m.spawns.defender) pts.push([m.spawns.defender.x,m.spawns.defender.z]);
  }
  if(!pts.length){ lf.setView([0,0],0); saveCam(); return; }""",
    """    if(m&&m.spawns.attacker) pts.push(LL(m.spawns.attacker.x,m.spawns.attacker.z));
    if(m&&m.spawns.defender) pts.push(LL(m.spawns.defender.x,m.spawns.defender.z));
  }
  if(!pts.length){ lf.setView(LL(0,0),0); saveCam(); return; }""",
    "lffit-spawns",
)

# ---------- F. lfDrawGrid ----------
R(
    """  const b=lf.getBounds(); if(!b.isValid()) return;
  const x0=b.getSouth(), x1=b.getNorth(), z0=b.getWest(), z1=b.getEast();
  const span=Math.max(x1-x0, z1-z0);""",
    """  const b=lf.getBounds(); if(!b.isValid()) return;
  const rb=ptBounds(b);
  const x0=rb.x0, x1=rb.x1, z0=rb.z0, z1=rb.z1;
  const span=Math.max(x1-x0, z1-z0);""",
    "grid-bounds",
)
R(
    """  for(let z=Math.floor(z0/step)*step; z<=z1; z+=step){
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
  }""",
    """  for(let z=Math.floor(z0/step)*step; z<=z1; z+=step){
    (Math.abs(z)<1e-6?mkAxis:mkLine)(LL(x0,z),LL(x1,z));
  }
  for(let x=Math.floor(x0/step)*step; x<=x1; x+=step){
    (Math.abs(x)<1e-6?mkAxis:mkLine)(LL(x,z0),LL(x,z1));
  }
  for(let x=Math.floor(x0/step)*step; x<=x1; x+=step){
    L.marker(LL(x,z0),{interactive:false,pane:'pGrid',
      icon:L.divIcon({className:'lf-spawn-label',html:'x'+fmt(x),iconSize:null})}).addTo(g);
  }
  for(let z=Math.floor(z0/step)*step; z<=z1; z+=step){
    L.marker(LL(x0,z),{interactive:false,pane:'pGrid',
      icon:L.divIcon({className:'lf-spawn-label',html:'z'+fmt(z),iconSize:null})}).addTo(g);
  }""",
    "grid-lines",
)

# ---------- G. lfRender 标签 ----------
R(
    """        L.marker([z.x,z.z],{interactive:false,pane:'pEdit',""",
    """        L.marker(LL(z.x,z.z),{interactive:false,pane:'pEdit',""",
    "render-label",
)

# ---------- H. 出生点标记 ----------
R(
    """  L.circle([p.x,p.z],{pane:'pSpawn',radius:2.5,color:c,fillColor:c,fillOpacity:0.95,weight:1,interactive:false})
    .addTo(mapState.layers.spawn);
  L.marker([p.x,p.z],{pane:'pSpawn',icon:L.divIcon({className:'lf-spawn-label '+cls,html:'<b>'+label+'</b>',iconSize:null})})""",
    """  L.circle(LL(p.x,p.z),{pane:'pSpawn',radius:2.5*SQM_SCALE,color:c,fillColor:c,fillOpacity:0.95,weight:1,interactive:false})
    .addTo(mapState.layers.spawn);
  L.marker(LL(p.x,p.z),{pane:'pSpawn',icon:L.divIcon({className:'lf-spawn-label '+cls,html:'<b>'+label+'</b>',iconSize:null})})""",
    "spawn-marker",
)

# ---------- I/J. 移动与缩放手柄 ----------
R(
    """    const center=L.marker([z.x,z.z],{""",
    """    const center=L.marker(LL(z.x,z.z),{""",
    "sel-center-marker",
)
R(
    """      const ll=center.getLatLng();
      const dx=ll.lat-center._st.x, dz=ll.lng-center._st.z;""",
    """      const pb=pt(center.getLatLng());
      const dx=pb.x-center._st.x, dz=pb.z-center._st.z;""",
    "sel-center-drag",
)
R(
    """      const mk=L.marker([hx,hz],{draggable:true,pane:'pHandles',zIndexOffset:1000,""",
    """      const mk=L.marker(LL(hx,hz),{draggable:true,pane:'pHandles',zIndexOffset:1000,""",
    "sel-corner-marker",
)
R(
    """        const ll=mk.getLatLng();
        const nr=Math.max(1, Math.max(Math.abs(ll.lat-anchor.x), Math.abs(ll.lng-anchor.z))/2);""",
    """        const pb=pt(mk.getLatLng());
        const nr=Math.max(1, Math.max(Math.abs(pb.x-anchor.x), Math.abs(pb.z-anchor.z))/2);""",
    "sel-corner-drag",
)

# ---------- K. 双击新建 ----------
R(
    "  applyEdit('add',{x:r1(e.latlng.lat), z:r1(e.latlng.lng), r:6});",
    "  const pb=pt(e.latlng);\n  applyEdit('add',{x:r1(pb.x), z:r1(pb.z), r:6});",
    "dblclick-add",
)

# ---------- L. loadTerrain：坐标换算 + 像素重排 ----------
R(
    """  const b=lf.getBounds();
  if(!b.isValid()) return;
  const cx=Math.round((b.getNorth()+b.getSouth())/2);
  const cz=Math.round((b.getWest()+b.getEast())/2);
  // 地图跨度可达数千米，故放宽到与服务端一致的上限 1024
  const half=Math.min(1024, Math.max(32, Math.ceil(Math.max(
    (b.getNorth()-b.getSouth())/2, (b.getEast()-b.getWest())/2)*1.2)));""",
    """  const b=lf.getBounds();
  if(!b.isValid()) return;
  const rb=ptBounds(b);
  const cx=Math.round((rb.x0+rb.x1)/2);
  const cz=Math.round((rb.z0+rb.z1)/2);
  // 地图跨度可达数千米，故放宽到与服务端一致的上限 1024
  const half=Math.min(1024, Math.max(32, Math.ceil(Math.max(
    (rb.x1-rb.x0)/2, (rb.z1-rb.z0)/2)*1.2)));""",
    "terrain-bounds",
)
R(
    """    for(let py=0; py<N; py++){                    // py：目标行 → lat(x) 由大到小
      for(let px=0; px<N; px++){                  // px：目标列 → lng(z) 由小到大
        const srcRow=px;                          // 源行 = z 索引
        const srcCol=N-1-py;                      // 源列 = x 索引（顶部对应最大 x）
        const si=(srcRow*N+srcCol)*3;
        const di=(py*N+px)*4;""",
    """    for(let py=0; py<N; py++){                    // py：目标行 → z 由小到大（新约定下自上而下即 z 递增）
      for(let px=0; px<N; px++){                  // px：目标列 → x 由小到大
        const si=(py*N+px)*3;                     // 源/目标同序：行=z 索引、列=x 索引
        const di=(py*N+px)*4;""",
    "terrain-pixels",
)
R(
    """    L.imageOverlay(cv.toDataURL('image/png'),
      [[r.x0, r.z0],[r.x0+N*step, r.z0+N*step]],""",
    """    L.imageOverlay(cv.toDataURL('image/png'),
      [LL(r.x0, r.z0), LL(r.x0+N*step, r.z0+N*step)],""",
    "terrain-overlay-bounds",
)

# ---------- M. lfWorld ----------
R(
    "  mapState.lf.fitBounds(L.latLngBounds([[g.x0,g.z0],[g.x1,g.z1]]).pad(0.05));",
    "  mapState.lf.fitBounds(L.latLngBounds([LL(g.x0,g.z0),LL(g.x1,g.z1)]).pad(0.05));",
    "world-fit",
)

# ---------- N. 图层开关 ----------
R(
    """function toggleTerrain(){
  mapState.terrainOn=!mapState.terrainOn;
  const b=$('btn-terrain'); if(b) b.textContent='地形底图 '+(mapState.terrainOn?'ON':'OFF');
  if(mapState.layers&&mapState.layers.terrain){
    if(mapState.terrainOn){ mapState._terrainKey=''; loadTerrain(); }
    else { mapState.layers.terrain.clearLayers(); }
  }
}""",
    """function toggleTerrain(){
  mapState.terrainOn=!mapState.terrainOn;
  const b=$('btn-terrain'); if(b) b.textContent='自采样底图 '+(mapState.terrainOn?'ON':'OFF');
  if(mapState.layers&&mapState.layers.terrain){
    if(mapState.terrainOn){ mapState._terrainKey=''; loadTerrain(); }
    else { mapState.layers.terrain.clearLayers(); }
  }
}

/** 真实俯瞰瓦片（squaremap）开关。 */
function toggleSqm(){
  mapState.sqmOn=!mapState.sqmOn;
  const b=$('btn-sqm'); if(b) b.textContent='真实地图 '+(mapState.sqmOn?'ON':'OFF');
  installSqmLayer();
}

/**
 * 拉取 squaremap 的世界/缩放元数据（经管理台代理，不额外暴露 8080）。
 * 失败就退回默认比例：此时只有自采样底图可用，地图功能不受影响。
 */
async function ensureSqm(){
  if(mapState._sqmTried) return;
  mapState._sqmTried=true;
  try{
    const s=await api('/bfadmin/api/sqm/tiles/settings.json');
    if(!s||!s.worlds||!s.worlds.length) return;
    const w=s.worlds[0];
    let max=(w.zoom&&w.zoom.max!=null)?w.zoom.max:8;
    let extra=(w.zoom&&w.zoom.extra!=null)?w.zoom.extra:2;
    try{
      const ws=await api('/bfadmin/api/sqm/tiles/'+encodeURIComponent(w.name)+'/settings.json');
      if(ws&&ws.zoom){ if(ws.zoom.max!=null) max=ws.zoom.max; if(ws.zoom.extra!=null) extra=ws.zoom.extra; }
    }catch(e){}
    SQM_INFO={ok:true, world:w.name, max:max, extra:extra};
    SQM_SCALE=1/Math.pow(2,max);
    setMapHint('squaremap 可用：世界 '+w.name+'，zoom 0-'+max+'（真实俯瞰图已接入）');
  }catch(e){ /* 未装/未渲染：保持默认比例 */ }
}

/** 安装 squaremap 真实俯瞰瓦片层（参数与 squaremap 官方前端一致）。 */
function installSqmLayer(){
  const lf=mapState.lf; if(!lf||!mapState.layers) return;
  if(mapState.layers.sqm){ lf.removeLayer(mapState.layers.sqm); mapState.layers.sqm=null; }
  if(!SQM_INFO.ok||!mapState.sqmOn) return;
  const url='/bfadmin/api/sqm/tiles/'+encodeURIComponent(SQM_INFO.world)
    +'/{z}/{x}_{y}.png?token='+encodeURIComponent(TOKEN);
  const layer=L.tileLayer(url,{pane:'pSqm',tileSize:512,minNativeZoom:0,
    maxNativeZoom:SQM_INFO.max,noWrap:true,attribution:'squaremap'});
  layer.addTo(lf);
  mapState.layers.sqm=layer;
  // 真实底图已就位 → 关掉自采样色块，避免两层互相干扰
  mapState.terrainOn=false;
  const b=$('btn-terrain'); if(b) b.textContent='自采样底图 OFF';
}""",
    "toggle-and-install",
)

# ---------- P. mapLoad 接入 ----------
R(
    """async function mapLoad(keepUndo){
  if(!TOKEN) return;
  const r=await getMapRaw();""",
    """async function mapLoad(keepUndo){
  if(!TOKEN) return;
  await ensureSqm();              // 先取 squaremap 的 zoom.max → 统一坐标比例后再建图
  const r=await getMapRaw();""",
    "mapload-ensuresqm",
)
R(
    """    if(saved) mapState.lf.setView([saved.cx,saved.cz], saved.zoom); else lfFit();
  }
  lfRender(); lfDrawGrid(); showZoneInfo();""",
    """    if(saved) mapState.lf.setView(LL(saved.cx,saved.cz), saved.zoom); else lfFit();
  }
  installSqmLayer();
  lfRender(); lfDrawGrid(); showZoneInfo();""",
    "mapload-install",
)

# ---------- Q. 按钮 ----------
R(
    """            <button class="btn" onclick="toggleTerrain()" id="btn-terrain">地形底图 ON</button>""",
    """            <button class="btn" onclick="toggleSqm()" id="btn-sqm">真实地图 ON</button>
            <button class="btn" onclick="toggleTerrain()" id="btn-terrain">自采样底图 ON</button>""",
    "btn-sqm",
)


def main() -> int:
    src = HTML.read_text(encoding="utf-8")
    problems = []
    for old, new, tag in REPL:
        n = src.count(old)
        if n != 1:
            problems.append(f"[{tag}] 命中 {n} 次（应为 1）")
            continue
        src = src.replace(old, new, 1)
    if problems:
        print("替换失败：")
        for p in problems:
            print("  -", p)
        return 1
    if "--check" in sys.argv:
        print(f"校验通过：{len(REPL)} 处替换全部可命中（未写盘）")
        return 0
    HTML.write_text(src, encoding="utf-8")
    print(f"已应用 {len(REPL)} 处替换 → {HTML}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
