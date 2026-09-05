# BREAKFRONT · 战斗事件总线与计分（#47，对齐 GD656 事件驱动）

> 参考：GD656Killicon 1.3.x（MIT，缓存 `/g/bf_refcache/gd656killicon`）的
> ServerCombatEngine / BonusEngine / conquest 适配器分层思想。**只借鉴机制不抄代码**。

## 已落地（本批，加法式）

`com.breakfront.combat`（纯 Java，JUnit 覆盖）：

| 类 | 职责 | 对齐 656 |
|---|---|---|
| `CombatEventType` | 11 类战斗事件（击杀/爆头/助攻/占点/失守/标记/压制/载具/救援/连杀） | 可配置计分事件枚举 |
| `CombatEvent` | 统一事件载体（killer/victim/side/zone/headshot/时间戳），含工厂方法 | Attribution 归属载体 |
| `CombatBus` | 发布-订阅总线（CopyOnWriteArrayList，单监听异常隔离） | ServerCombatEngine 事件流 |
| `BonusTable` | 默认 BF 加分 + `scoring.*` 属性覆盖 + multiplier | BonusEngine 可配置加分 |

默认加分：击杀 100 · 爆头 +25 · 助攻 50 · 占点 150 · 标记 10 · 压制 5 ·
载具摧毁 200 · 救援 20 · 连杀 50（乘数 `scoring.multiplier`）。

**当前为加法接入**：总线与积分表已就位但击杀/占点链路尚未改接——避免在战场稳定
验证完成前动伤害/击杀主干（改接 = KillListener/TaCzKillAdapter/ScoreKeeper/
HUD 击杀流全部转为 CombatBus 消费端）。

## 后续改接步骤（稳定窗口执行）

1. **击杀归属发布**：KillListener（vanilla AFTER_DEATH）+ TaCzKillAdapter
   （EntityKillByGunEvent）在完成归属判定后统一 `CombatBus.publish(kill(...))`，
   爆头标志沿用现有 KillEntry.headshot；删除重复扣票副作用，票数改由消费端计算。
2. **计分迁移**：ScoreKeeper 保持 KDA 记账，新增 score 字段由
   `BonusTable.killScore(headshot)` 写入；占点成功在 ZoneEvent CAPTURED 处
   `publish(zone(CAPTURE,...))` 计分 150。
3. **HUD 消费**：客户端击杀流/命中反馈/「+分数」弹出改为订阅总线（S2C 广播增量包
   或合并进现有 KillFeedPayload——选型：保留现推送，服务端分数合并进 MatchState
   Payload 的 scoreboard 行）。
4. **占点/压制/标记事件源**：占点由 BreakthroughGame ZoneEvent 桥接；压制由伤害
   事件累加器产出；标记待 #46 认证 + 队伍协作功能就绪后接。

## 矢量 killicon（并入 #44 UI 体系）

对齐 656 预设 json 的元素化组织，但**纯几何矢量（无贴图）**：

- 元素 = 图层列表（icon 形状 + 色 + 动画），预设 = 元素序列（如击杀 → 武器图标 →
  爆头修饰 → 连杀标记），导出 bfpack 兼容 gdpack 心智；
- 客户端先行：击穿现有击杀流条目为「矢量元素」渲染（BF2042 风）；
- 数据模型放 client hud，不做服务端下发（与 656 的服务器仅计分、客户端渲染一致）。
