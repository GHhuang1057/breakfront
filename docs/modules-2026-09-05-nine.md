# BREAKFRONT 九项模块化改造方案（2026-09-05）

> 现状基线：本地服在跑 dev-282c195（TaCZ 服务端+客户端均就位，默认枪包 tacz_default_gun 两处运行目录齐全，伤害按原版 20HP 设计）；客户端=breakfront(+client)+TaCZ+FabricAPI+ForgeConfigAPIPort，PCL 版本隔离于 `versions\1.21.1-Fabric 0.19.5`；无 Iris/VulkanMod/shaderpack。
> 交付模式不变：本地只编辑 → push → GitHub Actions → dev 预发布 → 部署 mcserver(含 breakfront-sync) → 重启 → RCON 冒烟。客户端热更走 :25610。

---

## 0. 公共设施（保证九项协同的"接口层"，先定义后使用）

所有模块共享下列决策，任何模块不得绕过：

| 设施 | 内容 | 涉及文件 |
|---|---|---|
| 血量常量 | `PLAYER_MAX_HEALTH = 100.0` 单一来源；真人/僵尸 bot/伤害模型/客户端 HUD 全部读它 | `game/BreakthroughTuning.java` |
| 服务端配置 | `breakfront-server.properties`（runDir）新增键：`auto_fill_target`、`auto_fill_enabled`、`admin.password`、`bot.strength`、`minimap.detail` 等 | `server/BreakfrontServer`(装载) |
| 协议规则 | 新 S2C/C2S payload 一律 core 实现 + client 编译桩同步 + `net/Net.java` 注册 + client jar `exclude` 不变 | `net/*`(core+client) |
| 客户端渲染顺序 | 世界空间(rings/editor/ghost 玩家) → 屏幕空间(zone 菱形/小地图/顶部/血条/武器/击杀流)，单一 RenderStack 编排，禁互相叠绘 | `client/hud/*` |
| 客户端 mixin | client 首次引入 mixin 体系（去原生 HUD 必须），fabric.mod.json 挂载 + Loom 自动 refmap | `client/.../breakfront-client.mixins.json` |
| 版本联动 | 每阶段客户端与服务端同版构建，热更提示重启用既有链路 | `client/upd/*` |

执行顺序总原则：**对局内战斗观感（数值+HUD+入口）→ 地图呈现（标记/小地图）→ 枪械 → 管理 → AI**，每批一次 CI 闭环。

---

## M1 移除单机入口 · 纯多人 + AI 自动填充
**目标**：主菜单/流程不再存在任何"进本地世界/单人"路径；任意真人连入，未满员位置由 AI 补齐并自动开局。
**实现**
- 客户端 `ui/BreakfrontMainMenu`：删除 SOLO TRAINING 卡与顶栏「单机」；全部入口=直连 BF 服（含导航"模式"下仅 ALL-OUT WARFARE）。启动检查/热更逻辑保留。
- 服务端 `server/ServerMatch`：`auto_fill` 目标人数改读 `auto_fill_target`（默认值待用户确认，候选 16 或 31）；开局条件 = 任一真人存在 + 补 bot 满目标 + 5s。
- 防"单人漏网"：客户端 mixin 拦截 `Minecraft.startIntegratedServer`/`createWorldOpenFlows`（阶段 F 的 UI 外壳一并处理联网下禁点）。
**文件**：client `ui/BreakfrontMainMenu.java`(+mixin 包新建)；core `server/ServerMatch.java`、`server/BreakfrontServer.java`(读配置)。
**配置**：`auto_fill_enabled=true`、`auto_fill_target=<确认值>`、`auto_fill_delay=5s`（properties 持久化，现 `/bf fill` 兼容）。
**验收**：菜单只剩多人卡；单人点击全部进部署层；服务端日志无人连接时无 bot，1 真人连入 5s 后自动补满开局。

## M2 TaCZ 枪械集成 + 100HP 数值适配
**目标**：按兵种发真枪真弹（TaCZ 物品），击杀归属/击杀流沿用桥接；枪伤值从 20HP 口径重定为 100HP 口径（战地 TTK 手感）。
**现状**：枪包数据在 `mcserver/tacz/tacz_default_gun`（服）与客户端 gameDir `tacz/tacz_default_gun`（两端同一份默认包）；`tacz:ak47`(9/600rpm)、aug(7/710)、awp(42/171)、aa12(30×弹丸)…，命名空间 `tacz`；core 尚未给过任何枪。
**实现**
1. **发枪**：`server/TeamManager`(兵种套件) 增加枪械清单表：兵种→主武器+弹匣弹药若干（例 突击=ak47/hk416d、支援=机枪、侦察=awp/kar98、工程=aa12/fn_fal 等）。`SetClassPayload` 现有链路复用；出生/重生给枪+弹药+副武器；防重复发放用 `player.getInventory()` 检测已持有。
2. **数值适配（枪包调参脚本）**：新增仓库脚本 `scripts/tune_tacz_pack.py`——按武器族做确定性重写（**只改服务端/客户端运行目录的枪包 JSON，不入库**，库内只留脚本+目标表，公开版另议许可）：
   - 步枪 600-800rpm → 单发 16~22（≈5-6 发击杀/0.4s）；SMG → 11~15；DMR → 26~34（2-3 发）；栓狙 → 胸 70~85/头 1 发（配合 TaCZ 爆头倍率）；霰弹单弹丸 6~9；手枪 18~26。
   - 输入=运行目录路径，输出=改写后 JSON + 对照表日志；服/客两端各跑一次保证一致（伤害以服务端为准，客户端主要用于表现）。
3. **击杀归属**：既有 KillListener(v1 vanilla)+TaCzKillAdapter(v2,`EntityKillByGunEvent`) 已能拿"击杀者+武器+爆头"；新增按武器族归类（突击/狙击…）供击杀流/计分展示，改动集中在 adapter 内部解析 `tacz:<id>`。
**文件**：core `server/TeamManager`、`server/TaCzKillAdapter`、`game/BreakthroughTuning`(新增 GUN_BALANCE 参照注释)；新 `scripts/tune_tacz_pack.py`；两端 tacz 运行目录 JSON（不入库）。
**配置**：`tacz-common.toml` 不须动；properties 无新增。
**验收**：`/bf team attacker` + start 后按兵种有枪有弹可开火；打靶击杀进击杀流且带武器名/爆头；AK 类 100HP 下约 5 发击杀、栓狙贴脸不死只残。

## M3 光影默认启用（待澄清后实施）
**现状**：客户端无 Iris/VulkanMod/shaderpack——"默认光影无法加载"大概率指某套未装入或装后报错的包。
**实现（按确认口径分支）**
- 口径 A（装渲染增强+光影包）：把 Iris(或按既定方向 VulkanMod)+Sodium 与指定光影包加入客户端 mods/版本隔离目录；`options.txt`/Iris `shaderpacks` 配置默认勾选；breakfront 启动检查阶段**自动写 Iris 配置使指定包默认启用**；服务端无关。
- 口径 B（现象排查）：需要用户提供启动日志报错段（游戏内"光影无法加载/黑屏/崩溃"截图或 `logs/latest.log` 关键行），按报错修（多为驱动/渲染器兼容）。
- 口径 C（非着色器=画面风格）：客户端后期处理（对比度/泛光）用矢量帧内实现，不依赖 shader 包。
**文件**：按口径 A：`scripts/pack_client_extras.py`(装配 mods+包)、BreakfrontClient 启动写配置；口径 B：日志分析；口径 C：`client/hud` 后期滤镜。
**验收**：进游戏即生效、无报错、帧率可玩。

## M4 地图导航标记重写（据点字母 + 地圈高亮）
**目标**：根治"A/B/C/D 字母乱、不可读、地面圈不亮"。根因组合：默认布局原点在 viaduct，Metro 实际坐标在远处→旧圆环投影在世界原点附近看不见；屏幕投影未做遮挡/聚类/深度排序→字母叠成一团。
**实现**
- 服务端：坐标唯一来源已是 SectorLayout(/bfs)，本次加**布局校验**（圆心在已加载区块 100 格内才显示，离玩家 > 512m 自动折叠为屏幕边缘箭头，不再投影真坐标）。
- 客户端 `hud/ZoneMarkers` 重写：
  - 距离分档：近(<120m)真实 3D→屏幕投影，字母带描边+所属色块(攻方蓝/守方红)+易读字号；中距离固定字母不缩放；远距离→屏幕边缘菱形指示(现有形态保留但加"指向线+距离数字")。
  - 聚类去重：同屏 N 个字母间做排斥布局（简易网格冲突解决），按被占优先级给"A1→C3"。
  - 高度矫正：字母锚点从 zone.groundY 投影，防止高架/室内时钻入建筑——采用"取准星视线与目标连线最近可视高度采样"的简化版本（按区块高度图采样一次）。
- `hud/WorldZoneRings`：圆环绘制随 SectorLayout 坐标修正（编辑器已能圈，重点解决"初始看不见"：进入对局/apply 后服务端向客户端补发一次全量坐标，客户端仅在 zone 已装载时画圈）；圆环分段加"攻防进度弧"，白色=争夺中、实色=已占。
**文件**：client `hud/ZoneMarkers`、`hud/WorldZoneRings`、`state/ClientMatchState`(补全量 zone 事件)；core `server/ServerMatch`(zone 广播去重/补发)、`map/SectorLayout`(校验)。
**验收**：任意距离都能清晰读到据点字母；地圈始终可见且颜色表达占领进度；Metro 上用 /bfs 圈点后立即正确显示。

## M5 100HP 战斗数值体系 + 去除原生 HUD
**目标**：传统 20 心机制→100HP 体系；游戏内不出现任何原版 HUD 元素（心/饥饿/经验/物品栏/准星由 BF HUD 取代）。
**实现（服务端数值）**
- `ServerMatch.onPlayerJoin/respawn`：`GenericAttributes.MAX_HEALTH` 基值改 100（同时覆盖 bot：`NpcSquad` 现硬编码 40 → 改 PLAYER_MAX_HEALTH），全量 heal 到 100。
- 伤害模型 `game/DamageModel`（新，服务端）：非枪械来源（玩家近战/坠落/溺水/环境/生物）按旧 20HP 等价 TTK ×5 折算，保持"原版威胁感"不因血量×5 而蒸发；枪伤由 M2 枪包重定值直接生效，不再二次换算；伤害源白名单可配。
- 复生保护/回血节律给 Battlefield 式（停顿 4s 后不回血，击杀回血不走）。
**实现（客户端去原生 HUD）**
- 新增 client mixin（`client/mixins.json` 挂 fabric.mod.json）：mixin `InGameHudMixin`：cancel 原版 `renderHearts/Experience/ItemName` 等全部原生叠加层（保留：聊天气泡、bossbar 不用即关）；准星改由 BF 十字线绘制；`HotbarMixin` 关闭（武器信息进右下角 M6）。debug 屏(F3)保留以便调参。
**文件**：core `game/BreakthroughTuning`、新 `game/DamageModel`、`server/ServerMatch`、`server/NpcSquad`；client 新 `mixin/*`、`resources/breakfront-client.mixins.json`、`fabric.mod.json`。
**配置**：`damage.scale.legacy=5.0` 等（properties）。
**验收**：HUD 无任何原版元素；受击/回血读数 100 制；坠落/近战与原版手感相当。

## M6 BF2042 HUD v3（左下血量 · 右下武器 · 顶部战况 · 小地图）
**目标**：M5 基础上做完整 BF 布局。
**实现（客户端重构 `BreakfrontHud` 为编排器，拆四个独立 widget，互不覆盖）**
- `BfHudHealth`(左下)：数字 100 制 + 战地式血条（白→低血红闪+低频心跳音效可选），呼吸回血态显示。
- `BfHudWeapon`(右下)：当前手持物品名（TaCZ 枪显示枪名/弹药 9mm/5.56+剩余弹匣——弹药数尽量由 TaCZ item nbt 或缓存提供，不可得则显示族名）；换弹/空仓显示状态。
- `BfHudTopBar`(顶部中央)：攻守双方站点控制条——按扇区序列分段，每段显示该据点当前归属色与占领进度弧；两侧大号票数(攻/守)；颜色沿用红=守、蓝=攻的语义(服务端已有)。替换旧顶部据点进度条/圆点，避免重复绘制（旧逻辑从 BreakfrontHud 摘除）。
- `BfHudMinimap`(右上圆/方雷达，矢量主体)：
  - 主体=纯矢量：自机箭头居中、旋转随朝向；据点=字母菱形(同 M4 色)；**友方队员点**（新增 S2C `PlayerPosPayload`(id,team,x,z,alive)，2Hz，仅同队）+ 争夺弧。
  - 地形层（可选，`minimap.detail=terrain` 才开）：运行时按区块采样方块颜色低分辨率画入离屏画布（运行时生成、非素材贴图；与"禁像素素材"红线不冲突，如需严格矢量可关）。
- 击杀流/命中反馈/结算卡等既有控件随新编排器挂载，不另起炉灶。
**文件**：client 新 `hud/BfHudHealth/BfHudWeapon/BfHudTopBar/BfHudMinimap` + 改 `hud/BreakfrontHud`、`state/ClientMatchState`；core 新 `net/PlayerPosPayload` + 注册 + `server/ServerMatch` 广播；client `net/PlayerPosPayload` 桩。
**验收**：四个区域齐全不互相遮挡、与 M4 字母无叠字；小地图转向正确、友军点不闪烁；票数随攻防实时变。

## M7 原生 UI 外壳重构（主菜单/设置/计分板/暂停/死亡…全部覆盖）
**目标**：游戏内可触达的原版界面全部换成 BF 风格壳。
**实现（分两波，避免与 M1/M6 抢冲突面）**
- 波 1（本批次内）：主菜单已是自研（M1 收口）；计分板已由 TAB 自研替换；新增：暂停菜单(PauseScreen)与"选项-常规页"用 BfUi 组件重绘（列表外壳=矢量卡，内部仍回调原版 options 处理器以保留功能性）；设置/视频等深层页暂以"BF 风格的容器 + 原版控件列表"呈现，保证不露原版皮。
- 波 2（后续批次）：死亡/重生、服务器连接失败、资源包/语言/辅助功能等全部走同一 BfUiShell 绘制管线。
**实现方式（关键）**：客户端 mixin `ScreenMixin` 拦截 `render`：凡 instanceOf 我们接管名单 → 调 `BfUiShell`；入口统一注册表 `client/ui/BfScreenRegistry`（screen class → 自定义布局），新界面只需注册，不再改 vanilla 逻辑。
**文件**：client 新 `ui/BfScreenRegistry`、`ui/BfUiShell`(容器/滚动/按钮/滑杆矢量组件库)、`ui/BfPauseOverlay`、mixin；`ui/BreakfrontMainMenu`(菜单项归类)。
**验收**：主菜单、暂停、设置入口、TAB 全部无原版视觉；功能键（调视频/音频/键位）可用。
> 说明：此模块面最宽，波 2 未完成前不阻塞其它模块；"去原生皮"判定=截图上无 MC 风格控件。

## M8 管理员模式（组合键+密码 · 据点可视化管控）
**目标**：游戏内任意时刻组合键呼出管理员入口，密码校验后进入可视化据点管理（看位置/状态、改圈、传送查验）。
**实现**
- 服务端 `server/ServerMatch`：`admin.password`（properties，默认 `breakfront`）；新增 C2S `AdminLoginPayload(password)` → 校验通过把 uuid 记入 `adminSessions`（不提升原版 op，避免越权），超时 30min 失效；管理动作走既有 /bfs 语义但放宽到 admin 会话（`SectorEditCommands` 增加"权限=op 或 admin 会话"判定）。
- 客户端：
  - 热键默认 `Ctrl+Shift+F8`（`options.txt`/properties 可改）→ 弹 `BfAdminGateScreen`（BF 风密码输入）。
  - 通过后进入 `BfAdminPanel`（可随时热键开关的矢量面板）：左侧实时据点列表（id/所属/进度/半径/距玩家距离），右侧世界视图复用 M4 圈+M8 高亮：点选据点→面板显示详情；按钮：传送到该点、here 移动该点圆心到脚下、resize、remove、undo、save、apply；以上均走 C2S AdminAction 载荷或直发 /bfs 命令文本（复用既有解析，零新协议成本——采用后者，客户端合成 command 由服务端 `ServerCommandSource` 以玩家身份执行）。
**文件**：core `server/ServerMatch`(admin 会话)、`server/SectorEditCommands`(权限判定)、新 `net/AdminLoginPayload`+注册；client 新 `ui/admin/BfAdminGateScreen`、`ui/admin/BfAdminPanel`、`net/AdminLoginPayload` 桩、`state/AdminState`、`BreakfrontClient`(热键注册)。
**配置**：`admin.password`、`admin.key`(组合键名)、`admin.timeout=1800`。
**验收**：游戏内 Ctrl+Shift+F8→输错拒绝、输对进面板；面板数字与 M4/M6 圈一致；对点"移动到这里"后圈即时跟随并在 save/apply 后生效（重启后仍在=sectors.json）。

## M9 AI v2（Steve 外观 · 持枪 · 占点 · 交战 · 强度逼近真人）
**目标**：机器人：默认史蒂夫皮肤外观；自主前往据点占领（推进米数）；发现敌人→持当前枪"射击"并移动规避；配合 /bf fill 数量机制；强度分档可调。
**实现（服务端行为引擎，替换旧"僵尸+NoAI+火烧回血"hack）**
- 保留僵尸实体底（服务器实体、碰撞/路径经原版），加 `NpcBrain`（新）每 tick 驱动：状态机 `MOVE_TO_ZONE → ENGAGE → CAPTURE/REGROUP`；用 `MobEntity` 的 `navigation` 寻路至目标 zone 内随机点；视觉/听觉检测玩家（距离+视线 raycast，server 端 `world.raycast`/实体碰撞盒采样）。
- 战斗：近距(<8m)近战攻击；远距持枪——由服务端模拟射击：冷却=枪族 rpm 换算、散布=强度档参数、命中=射线与玩家 AABB 相交判定 + 头部高度判定爆头；伤害走 `DamageModel`（M5）以维持 100HP 数值一致；开枪方向/换弹不依赖客户端表现（客户端仅"看到它举枪/枪口朝向玩家"）。
- 表现/皮肤：服务端每 5Hz 广播 bot 姿态 `BotStatePayload`(id,x,y,z,yaw,pitch,heldGun,anim:aim|fire)；**客户端幽灵渲染** `BotGhostRenderer`：用本地玩家模型（Steve 皮肤，调用玩家渲染器）在 bot 位置绘制替身（真僵尸实体同时 `setInvisible(true)`+熄灭交互），手持枪械物品由服务端在 bot 手上挂真实 item（同时服务端实体可见项给幽灵同步）。效果=看到"史蒂夫士兵"持枪跑动射击。
- 占点：NpcBrain 驱动 bot 进入 zone 半径即计入 ZoneState 争夺人数（走与真人相同 counting 逻辑），撤离人数不足自然回落——代码上把"人数计数"抽到 `game/ZoneState` 的公共接口，真人与 bot 统一调用。
- 强度分档：`bot.strength=rookie|regular|veteran` 控制命中散布/反应延迟/规避走位；默认 regular；veteran 命中率与真人常客相当，rookie 供训练。
**文件**：core 新 `server/NpcBrain`、`server/BotRoster`(fill 与实体生命周期管理,替换 NpcSquad 的生成部)、`game/ZoneState`(计数接口)、`server/ServerMatch`(接入)；新 `net/BotStatePayload`+注册；client 新 `hud/BotGhostRenderer`(玩家模型渲染)、`state/BotGhostState`、`net/BotStatePayload` 桩、`BreakfrontClient`(世界渲染钩子)。
**配置**：`bot.strength`、`bot.roster_per_side`(与 M1 auto_fill_target 联动)、`bot.ghost=true`(幽灵皮肤开关；假若客户端渲染不稳可退回"显示 bot 名牌+僵尸本体不可见"模式)。
**验收**：单机进入：全场为史蒂夫士兵；它们主动向 A/B/C 推进且圈进度随人数涨跌；玩家现身即被攻击（有弹道观感/受击/死亡进击杀流）；与 bot 对枪有压力；关闭 fill 后清场。
> 说明：此模块是九项中工程量最大、最容易滚雪球的一项，独立成最后批次，且保留"普通僵尸+持枪"降级开关，不阻塞前八项上线。

---

## 冲突与协同矩阵（要点）

| 潜在冲突面 | 解决方案 |
|---|---|
| M5(100HP) × M2(枪伤) × M9(bot 伤害) | 一律走 `DamageModel`+`PLAYER_MAX_HEALTH`；枪伤仅由 M2 枪包数值表达，不再叠加换算 |
| M5(去 HUD) × M6(自研 HUD) | mixin 只关原生层；自研四 widget 全挂 `BreakfrontHud` 编排器统一绘制，顺序固定 |
| M6(顶部战况) × 旧顶部进度条 | 旧逻辑从 BreakfrontHud 删除，避免双绘 |
| M4(字母) × M6(小地图字母) × M8(面板) | 颜色/字形走 `BfTheme` 单一令牌；字母形态由 ZoneMarkers 输出、小地图内用轻量变体 |
| M1(去单机) × M7(壳) × M9(单机人机语义) | M1 语义=纯多人+自动补 AI（人机由"连服+fill"实现）；M7 只做视觉壳不引入单人路径；M9 承接 fill 的角色与行为 |
| M2(发枪) × 既有兵种套件 × M9(bot 持枪) | 发枪表放 `TeamManager` 单一数据；bot 的 gun 由 `BotRoster` 按同一表生成；真人无重复发放判定 |
| 新旧 payload | 每阶段列新增载荷与两端版本同步表（SectorEdit、PlayerPos、AdminLogin、BotState…）；旧载荷字段只加不改，客户端桩同步 |
| 服务端与客户端枪包数值 | 统一由 `scripts/tune_tacz_pack.py` 双端写入，库内留脚本+目标表（许可红线留给公版阶段） |
| "No entity was found" 刷屏 | M9 重构 bot 生命周期时顺带消除（僵尸实体被查空引用），列为 M9 验收项之一 |

## 建议执行顺序（每批 1-2 次 CI，做完一批可玩一批）
1. **批 A（可玩里程碑）**：M1 入口收口 + M5 数值/去原生 HUD + M6 HUD v3（血/武器/顶部先上，小地图同批）。→ 1-2 CI
2. **批 B**：M4 标记重写 + M6 小地图（含 PlayerPos 载荷）。→ 1 CI
3. **批 C**：M2 枪械（tune 脚本+发枪+适配 100HP 校验）。→ 1-2 CI
4. **批 D**：M8 管理员模式。→ 1 CI
5. **批 E**：M3 光影（按确认口径）。→ 0-1 CI
6. **批 F**：M9 AI v2（最大件）。→ 2-3 CI（先行为后皮肤，ghost 不稳有降级）
7. **批 G**：M7 UI 外壳波 1/波 2 收尾。

每批结束 = push → Actions → dev 发布 → 部署 mcserver → 重启 → RCON 冒烟 → 交付验收条目。
