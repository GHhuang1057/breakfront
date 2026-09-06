# BREAKFRONT 机制调研：SBW / GD656Killicon 对照与借鉴

> 调研日期：2026-09-06
> 角色：BREAKFRONT（Fabric 1.21.1 战地模组）机制研究员
> 性质：仅调研 + 产出本文档；不写工程代码、不改任何源码（除本文档）。

---

## 0. 参考源码来源 / 许可说明

### 0.1 SBW（SuperbWarfare / 卓越前线）下载情况（如实报告）

- **预设来源 1 — Gitee 分支 zip**：`https://gitee.com/atsuishio/SuperbWarfare/repository/archive/1.21.zip`（及 `1.21-0.8.8.zip`、`1.20-0.8.8.zip`）
  经 Python `urllib` 下载（重试 ≤3 次，含 magic-byte 校验），**全部失败**：Gitee 返回的是反爬/登录 HTML 页（`<!DOCTYPE html>…zh-CN`），不是 zip；文件大小仅 ~46KB，magic 为 `!DO`。
- **预设来源 2 — cursemaven sources.jar**：`https://cursemaven.com/maven/curse.maven/superb-warfare-1218165/6911292/…-sources.jar`
  **失败**：HTTP 404（该 projectId / fileId 组合不存在；尝试多个 fileId 均为 404）。
- **改道成功 — Gitee API + raw 逐文件下载**：用 Gitee `api/v5/repos/atsuishio/SuperbWarfare/git/trees/<branch>?recursive=1` 拉取文件树，再对关键文件用 `raw/<branch>/<path>` 下载。
  - 实际落地目录：**`G:/bf_refcache/superbwarfare-src/`**
  - 分支选取说明：**`1.21`（即 1.21-0.8.8）分支本身是一个枪械/载具 Mod，并无占领圈、击杀流、小地图机制**；战地向机制（爆头盒、击杀流、雷达消息、部署器）实际在 **`sp2025` / `master` 开发分支**。因此本文以 **`sp2025` 为主、`1.21` 为辅**，并在每条引用后标注分支。
  - 已下载并精读 17 个关键 Java 文件（均落在 `superbwarfare-src/{sp2025|1.21}/…`）。

### 0.2 GD656Killicon 来源

- 本地缓存目录 **`G:/bf_refcache/gd656killicon`**（MIT 许可，含 1.3.x 源码），直接读取 `src/main/java/org/mods/gd656killicon/` 下相关文件。

### 0.3 许可与用途声明

- **SBW 代码**：GPL-3.0；**资产**：ARR（All Rights Reserved）。
- **GD656Killicon 代码**：MIT。
- **用途**：以上两者**仅供本地学习、借鉴机制思路**。本文不复制其代码、不复制其贴图/音效等资产；BREAKFRONT 如采纳思路，由本组自行实现等效逻辑（参考而非照搬）。

---

## 1. 专题实现要点对照表

> 列：机制 ｜ SBW 做法(文件) ｜ GD656 做法(文件) ｜ 我们现状(BREAKFRONT) ｜ 可借鉴的最小改法

### 专题一：据点 / 占领圈世界渲染

| 机制 | SBW 做法（文件） | GD656 做法（文件） | 我们现状（BREAKFRONT） | 可借鉴的最小改法 |
|---|---|---|---|---|
| 据点范围可视化 | **无占领圈机制**。`1.21` 分支是枪械/载具 Mod。最接近的是 `sp2025/…/client/renderer/block/ChargingStationBlockEntityRenderer.java`：用 `BlockEntityRenderer` 画 **AABB 线框盒**（`new AABB(pos).inflate(CHARGE_RADIUS)`），`CustomRenderType.BLOCK_OVERLAY`（POSITION_COLOR / 无纹理 / 半透明 / 无光照 / NO_CULL）渲染，绿 `(0,1,0)` **alpha 0.2**，6 面（24 顶点）**线框**；`shouldRenderOffScreen()=true` 使远处也可见。是 3D 盒，非贴地圆。 | 无世界渲染（纯 HUD 叠层 Mod）。仅消费征服类模组的占领事件作 kill icon 触发（`common/KillType.java` 中 `CAPTURE=6`），不绘制圈。 | **`client/hud/WorldZoneRings.java` 已实现且更先进**：贴地**外接正方形**（对应服务端方形判定 `|dx|≤r && |dz|≤r`），本地高度图 `Heightmap.MOTION_BLOCKING` 取地表 Y+1、每 ~1s 刷新、区块未加载回退 payload `groundY`；多层：内部淡色方坪(alpha≈38)、高亮边带(boundary..+0.75 贴地,alpha210)、外晕(+0.75..+1.25 呼吸)、边带内缘 2px 亮线、体积墙(0..WALL_H 2.4m 半透)、四角柱(2.6m+顶块)、中心光柱(7.5m 半透+柱头)；颜色按 owner 攻绿/守蓝/争夺青白呼吸；vanilla immediate `LINES`+`QUADS`，`AFTER_TRANSLUCENT` 阶段，兼容 Sodium/Iris。 | 我们已超 SBW。可补两点（均小改）：① 占领进度环——参考 SBW `client/RenderHelper.java:renderCircularRing`（TRIANGLE_STRIP 画 2D 圆环，背景环+进度环），在据点中心光柱头叠加进度环；② 低配备选：加一个"整盒半透明线框"开关（模仿 SBW 的 `BLOCK_OVERLAY` 盒），供低端机/远距降级。 |
| 贴地高度取法 | 不贴地（盒体按 `blockPos` 立方 inflate，Y 从方块底到顶，是方块级而非地形跟随）。 | 不涉及。 | `WorldZoneRings.refreshHeights()`：中心 + 四边各 `perEdge` 点（每 ~1.5m 一段）用 `world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz)+1`，`+0.16` 防 z-fighting；区块未加载 → `Double.NaN` → 回退 `zone.groundY()`。 | 维持现状（已优于 SBW）。可把 `STEP=1.5` 提到 `2.0`、缓动高度变化，减少大据点时的顶点抖动（性能/观感微调）。 |

### 专题二：击杀反馈 / 击杀流 / 受击方向（爆头判定与反馈）

| 机制 | SBW 做法（文件） | GD656 做法（文件） | 我们现状（BREAKFRONT） | 可借鉴的最小改法 |
|---|---|---|---|---|
| 击杀流（killfeed） | `sp2025/…/client/overlay/KillMessageOverlay.java`：右对齐击杀提示，渲染 attacker/target 名（带队伍色）、**枪械图标**（`preciseBlit`）、伤害类型图标（`BEAST`）；入场平移、4s 后开始消失、淡出用 `pow(tick,5)` 缓动；队列 `freeze`/`fastRemove` 处理连续击杀。推进由 `sp2025/…/event/KillMessageHandler.java` 客户端 tick 累加 `record.tick`（>100 移除）。数据载体 `sp2025/…/tools/PlayerKillRecord.java`（attacker/target/stack/**headshot**/damageType）。 | `network/packet/KillIconPacket.java`：server→client 携带 `category/name/killType/comboCount/victimId/distance/hasHelmet/bonusMultiplier`；`HudElementManager.trigger(...)` 驱动各 HUD 元素（击杀图标/连杀图标等）；车辆击杀优先级延迟 700ms。`common/KillType.java` 枚举丰富：`HEADSHOT=1/EXPLOSION=2/CRIT=3/ASSIST=4/DESTROY_VEHICLE=5/CAPTURE=6/RESCUE=10` 等；`SoundTriggerManager` 触发音效。 | `client/hud/BreakfrontHud.java:renderKillFeed`：右上 "**爆头** X 击杀 Y" 文本，淡入 240ms / 淡出 700ms，最多 5 条；数据来自 `client/state/ClientMatchState.killFeed()`（由 `net/KillFeedPayload.java` 驱动，已含 `headshot` 字段）。 | 吸收 GD656 的"**killType 分类 + 图标 + 音效 + 连杀(combo)**"组合：① 给 `KillFeedPayload` 增 `killType` 字段（爆头/载具/救援），渲染时按类型换色/加图标；② 击杀行补图标（`preciseBlit` 风格）。改动面：`KillFeedPayload.java` + `BreakfrontHud.renderKillFeed`。 |
| 受击方向 / hitmarker | **无经典准星 hitmarker**（SBW 自身不画）。`1.21/…/tools/HitboxHelper.java` 仅做**命中箱缓存**（按 ping 回退历史 AABB/速度，用于命中判定公平），非显示层。 | `client/render/impl/HitInfoRenderer.java`（`element subtitle/hit_info`）：显示"对目标累积伤害量"，单行或**分层（每实体一行）**，击杀时切击杀色，数字滚动(QUINTIC_OUT 断点续滚)、淡入淡出、发光(8 向偏移副本)、对齐——这是**伤害字幕**而非方向箭头；方向由 TACZ/SBW 集成提供，GD656 不画箭头。 | `client/hud/BreakfrontHud.java:renderHitMarkers`：准星四角斜线，`kind` 0=命中(白)/2=击杀(红)/3=爆头击杀(绿)，pop 70ms→停留→淡出 120ms；由 `net/HitMarkerPayload.java`(kind) 驱动。 | ① 补"对目标累积伤害字幕"（吸收 GD656 `HitInfoRenderer`）：新增 `client/hud/HitDamageSubtitle.java`，需服务端发增量伤害（或复用 HitMarker 触发）。改动面：新增类 + `ClientMatchState` 增量事件 + 服务端伤害包（若 core 未发）。② 受击方向箭头：若要做，参考 GD656 仅作"字幕"，方向需服务端在伤害包带 `yaw` 差值。 |
| 爆头判定与反馈 | 服务端判定：`sp2025/…/headshot/BoundingBoxManager.java` 按 `EntityType` 注册**头部 AABB 盒**（几十种生物；`BasicHeadshotBox`/`ChildHeadshotBox`/`RotatedHeadshotBox`），命中位置落在头部盒内即爆头；`PlayerKillRecord.headshot` 携带标志，击杀提示据此换图标。`sp2025/…/headshot/BasicHeadshotBox.java` 给 `headYOffset`(如玩家 24*0.0625) 与头部半宽。 | 客户端不判定；由 SBW/TACZ 服务端检测后以 `killType==HEADSHOT` 下发；`client/render/effect/IconRingEffect.java` 给击杀图标加**光环**。 | 服务端已判定（计分板 `ScoreboardPayload` 含 `headshots`）；客户端仅 `HitMarker kind==3`(绿) 与 KillFeed "爆头" 文字。 | 吸收 GD656「爆头=独立视觉+光环+音效」：① `kind==3` 时叠加一次短促爆头音效（需客户端 sound 资源，自行制作）；② 击杀图标/字加光环（`IconRingEffect` 思路：8 向偏移亮副本）。改动面：`BreakfrontHud.renderHitMarkers` + 可选 `sounds/`。 |

### 专题三：AI 兵 / 部署重生选择 / 小地图

| 机制 | SBW 做法（文件） | GD656 做法（文件） | 我们现状（BREAKFRONT） | 可借鉴的最小改法 |
|---|---|---|---|---|
| AI 兵 / 敌人 targeting | `1.21/…/entity/goal/GunShootGoal.java`：`Mob` 的 AI goal——`getTarget()` 取目标；`getSensing().hasLineOfSight()` 视线判定；距离 > `shootDistance()` 则 `getNavigation().moveTo(target)` 靠近，否则停下；`lookAt(target,30,30)`；`aimTime` 蓄力（丢失视线可清零/递减）；用 `gunData.shoot(...)` 按 `RPM/60` 算**开火冷却(ms)**，半自动/连发加额外间隔；`shouldStartReloading/startBolt` 处理换弹；**低帧补偿**（`do{shoot}while` 追平 cooldown）。`1.21/…/mixins/MobMixin.java` 微调。无独立 bot 实体（复用 Mob）。 | 无 AI（HUD Mod）。 | `client/render/BotSoldierRenderer.java`：服务端 bot = `ZombieEntity`（标签 `breakfront.npc`，命令驱动位移/占点/可击杀），客户端替换为 `PlayerEntityModel`+史蒂夫皮肤+主手武器(`HeldItemFeatureRenderer`)，位移/转身/受击白闪沿用原版实体管线。**行为在服务端**（NpcSquad 等），渲染已完备。 | 行为层参考 SBW `GunShootGoal`：补"**视线判定 + 蓄力 + 按 RPM 冷却 + 低帧补偿 + 超程导航靠近**"到本组 bot AI（目前渲染有了，行为可更聪明）。改动面：core 的 `NpcSquad`/AI goal（参考 GunShootGoal，非 client）。 |
| 部署 / 重生选择 | `sp2025/…/item/MortarDeployer.java`、`TargetDeployer.java`：物品 `useOn` 在点击方块生成实体（迫击炮/标靶 dummy），`getYOffset` 按碰撞形状算放置高度——属"**部署实体**"，非"重生点选择界面"。`sp2025/…/network/message/RadarSetPosMessage.java` 等仅设雷达坐标/目标（UI 未在此分支完整实现）。 | 无部署/重生界面（HUD Mod）。 | `client/ui/BfDeployScreen.java`：**全窗口战区俯瞰**（真实地形色 `TerrainOverview` + 网格），自动 fit 据点+出生区包围盒 8% 边距、可拖拽/滚轮缩放（以光标为锚）；底部 4 兵种菱形卡 + 武器 ◀▶ 切换；部署目标=出生区 + 当前扇区据点（按阵营过滤合法点），点击/方向键选点；部署 → `/bf deploy` + `requestRespawn`；阵亡模式必须点击不可 ESC。已较完整。 | 维持。可吸收 SBW「部署实体带高度自适应」思路到出生点合法性校验（避免卡墙/悬空）：`BfDeployScreen` 选点后调用类似 `getYOffset` 的安全性检查。改动面：`BfDeployScreen.java`（选点校验）。 |
| 小地图 / 雷达渲染 | `sp2025/…/network/message/RadarSetPosMessage.java` / `RadarSetTargetMessage.java` 仅**网络消息**（设雷达坐标/目标到 `FuMO25Menu`），此分支未见完整雷达渲染类（雷达 UI 可能未实现/在别处）。 | 无小地图。 | `client/hud/BfMinimap.java`：右上矢量雷达，自机箭头固定朝上（画面随 `yaw` 旋转），据点=菱形/圆点+字母+争夺白闪，友军青色点(阵亡灰)，`RANGE=90m`/半径 46px；**绘制用逐像素扫描线**（`disc` 逐行 fill、`tri` 扫描线填充）——无贴图但较重。 | 吸收 SBW `RenderHelper.renderCircularRing` 的 **TRIANGLE_STRIP 几何画法**改写 `disc`/`tri`，去掉逐像素 `ctx.fill` 扫描线，提升大窗口性能；并补"据点/目标点随距离衰减 + 屏幕外边缘箭头"（`ZoneMarkers` 已有进场逻辑可复用）。改动面：`BfMinimap.java`。 |

---

## 2. 下一步可执行借鉴清单（BREAKFRONT）

> 每条标注**改动面（文件级）**，按"先小后大"排序。均为参考思路、自行实现，不复制 SBW/GD656 代码或资产。

### 清单 1 — 占领进度环（吸收 SBW `RenderHelper.renderCircularRing`）
- 内容：在每个据点中心光柱头叠加一个 2D 进度环（背景环 + 当前占领进度环，TRIANGLE_STRIP 几何），让"推进度 %"在世界中一目了然（替代/补充现有顶栏文字）。
- 改动面（client，小）：`client/hud/WorldZoneRings.java`（在 `beaconInto` 处叠加环）；如需复用 2D 环工具，在 `client/bf/BfDraw.java` 加 `ring(...)`（参考 SBW `renderCircularRing` 的 `TRIANGLE_STRIP` 画法，自写）。

### 清单 2 — 爆头反馈组合（吸收 GD656 `KillType` + `IconRingEffect` + `SoundTriggerManager`）
- 内容：把"爆头"从"绿色 hitmarker + 文字"升级为组合反馈：① `HitMarkerPayload` 增加细分 killType（爆头/载具/救援），`kind==3` 时叠加一次短促爆头音效（自行制作 sound 资源）；② 击杀流对应行加光环/图标（8 向偏移亮副本，仿 `IconRingEffect`）。
- 改动面（client，中）：`net/HitMarkerPayload.java`（加字段）、`client/hud/BreakfrontHud.java:renderHitMarkers` + `renderKillFeed`、`net/KillFeedPayload.java`（加 `killType`）、可选 `client/sounds/` 资产 + 触发点。

### 清单 3 — 对目标伤害字幕（吸收 GD656 `HitInfoRenderer`）
- 内容：新增"对当前目标累积伤害量"字幕（单行或按实体分层），击杀切色 + 数字滚动(QUINTIC_OUT) + 发光；填补本组"只有攻击者 hitmarker、无伤害量反馈"的空白。
- 改动面（client + 需服务端，中）：新增 `client/hud/HitDamageSubtitle.java`；`client/state/ClientMatchState.java` 加增量伤害事件；core 侧发"本玩家造成伤害量"包（或复用 `HitMarkerPayload` 携带 `damage` 字段）。

### 清单 4 — bot AI 行为参考 SBW `GunShootGoal`（服务端）
- 内容：把 SBW 的"视线判定 + 蓄力(aimTime) + 按 RPM 算开火冷却 + 低帧补偿 + 超射程导航靠近"移植/对照到本组 bot 行为，使 AI 增援更像样（现有 `BotSoldierRenderer` 只解决"看起来像士兵"，行为在服务端 `NpcSquad`）。
- 改动面（core，较大，非 client）：`core/.../NpcSquad` 或新增 AI goal 类（对照 `1.21/…/entity/goal/GunShootGoal.java` 自写，Fabric 用 `Goal`）。

### 清单 5 — 小地图绘制性能化（吸收 SBW `renderCircularRing` 几何画法）
- 内容：将 `BfMinimap` 的逐像素扫描线 `disc`/`tri` 改写为 TRIANGLE_STRIP 几何（圆环/三角一次成型），去掉每帧大量 `ctx.fill`；并补"据点/敌人在屏幕外时边缘箭头指示"（方向数据 `ZoneMarkers` 已有）。
- 改动面（client，小—中）：`client/hud/BfMinimap.java`（重写 `disc`/`tri` 为几何绘制）。

---

## 3. 关键结论

1. **据点圈**：BREAKFRONT 的 `WorldZoneRings` 已是贴地多层发光方坪 + 角柱 + 光柱，**实现完整度超过 SBW**（SBW 仅有不贴地的方块线框盒）。主要可补"占领进度环"与"低配线框降级"。
2. **击杀反馈**：SBW 偏"带枪械图标的击杀提示"、GD656 偏"分类 killType + 图标光环 + 连杀 + 音效 + 伤害字幕"。本组已有基础 killfeed/hitmarker/爆头判定，**最值得补的是 GD656 式的"爆头组合反馈"与"对目标伤害字幕"**。
3. **AI / 部署 / 小地图**：本组 `BotSoldierRenderer` + `BfDeployScreen` + `BfMinimap` 均已落地；AI 行为可参考 SBW `GunShootGoal`，小地图绘制可参考 SBW 的 TRIANGLE_STRIP 几何画法做性能优化。

---

## 4. 汇报

- **下载是否成功**：Gitee 分支 zip 与 cursemaven sources.jar **均失败**（如实）；改用 **Gitee API + raw 逐文件下载成功**，落地 17 个 SBW 关键 Java 文件于 `G:/bf_refcache/superbwarfare-src/`（分支 `sp2025` 与 `1.21`）。GD656 源码直接读本地缓存 `G:/bf_refcache/gd656killicon`。
- **解压/落地路径**：`G:/bf_refcache/superbwarfare-src/`（注：因 zip 下载失败，未走 zipfile 解压，改用 raw 文件落地；若坚持 zipfile 路径约定，可在 Gitee 放行后补下载 `1.21.zip` 并 `zipfile` 解压至此目录）。
- **文档路径**：`G:/BF_MC/docs/bf-sbw-gd656-research-2026-09-06.md`。
- **清单条数**：5 条（见第 2 节，均标注文件级改动面）。
- 全程**未执行 git / gradle**。
