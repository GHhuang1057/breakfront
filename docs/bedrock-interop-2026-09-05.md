# BE×JE 互通可行性评估（2026-09-05）

> 请求来源：用户要求推进九项文档后续工作的同时「确认一下互通的可能性（BE 和 JE）」。
> 结论先行：**技术上可"连通"，但对 BREAKFRONT 无实质意义——不做。** 本项目维持 Java Edition 独占。

## 互通的技术路径（业界现状）

让基岩版（BE）玩家进入 Java 版（JE）服务器的标准方案是 **GeyserMC**（协议翻译网关，监听 UDP 19132，
把 BE 流量翻译成 JE 协议交给后端），配合 **Floodgate** 处理 BE 无 Java 正版账号的认证。
- Geyser 官方：支持 Fabric/NeoForge 端装载（Geyser-Fabric 需额外 Fabric API）。
- Geyser 官方**只翻译"服务端内容"**：`Mods that require a client-side install will not work!`；
  资源包格式 BE/JE 不互通（需另备 .mcpack）；自定义物品需逐个手工 JSON 映射 + BE 资源包才能"显示"。

## 为什么对 BREAKFRONT 不成立

本项目是**重度客户端模组依赖**的内容包，BE 客户端无法加载任何 JE 模组：

| 依赖面 | 载体 | BE 端表现 |
|---|---|---|
| 去原生 HUD / 自研矢量 HUD（血卡/武器/顶栏/雷达） | client mixin + 渲染代码 | 无——BE 走自己的原生 UI，看不到 BF 界面 |
| 据点地面描边/菱形带/光柱 | client 世界渲染 | 无 |
| 枪械（tacz:modern_kinetic_gun）与射击/换弹/弹道/伤害 | TaCZ mod（客户端渲染+逻辑，服务端弹道实体） | 物品不可用/不可见；射击玩法整体失效 |
| 击杀归属/命中反馈/击杀流 | core mod + 自研 S2C | 无 |
| 热更新 | client mod | 无 |
| AI bot 幽灵替身（Steve 持枪） | client 渲染 | 无（只能看到裸僵尸） |

- 服务端侧玩法（100HP 数值、攻防回合、兵种发装、管理员会话）理论上可服务端化，
  但 BE 玩家进入后**无枪可打、无 HUD 可看、占点提示缺失**——不是"降级体验"，是基本不可玩。
- 业界对同型判断的共识表述：*"For a 300-mod content pack, cross-play is not realistic today."*
  我们的枪械/界面依赖面与之同类。
- Geyser-Fabric 官方支持面只覆盖最新协议（文档以 1.21.11+ / 26.x 为现行支持线）；
  对 1.21.1 后端还需要叠加 ViaVersion/ViaBackwards 中转，多一跳协议栈与故障面。

## 若未来真要覆盖 BE 玩家（暂不投入）

1. 另做一套 **BE 原生行为包/Addon**：枪械、HUD、占点玩法全部用 BE 生态实现——工程量近似另一个整合包，与当前 Fabric 技术栈完全不共享。
2. 玩法内容改为 **纯原版兼容**（去 mod 枪、去自研 HUD、去客户端依赖）：与项目既定路线（TaCZ 枪械 + BF2042 矢量界面）直接冲突。

两条路都意味着把 BREAKFRONT 重新做一遍。**建议：公开版面向 JE 玩家；BE 需求出现明确受众后再立项评估。**

## 决策

- 本项目**不做** BE×JE 互通，维持 JE（Fabric 1.21.1）独占。
- 不引入 Geyser/Floodgate/ViaVersion 相关依赖与文档承诺。
