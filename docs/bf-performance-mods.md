# BREAKFRONT · 性能优化模组清单（#44）

> 状态：清单核验版（2026-09-05）。所有版本钉与依赖/冲突核验见下表；**以 jar 内
> `fabric.mod.json` 的 depends/breaks 为准**（Iris/Sodium 强版本耦合教训，见下）。

## 已实装（现网客户端必备）

| 模组 | 版本钉 | 作用 | 备注 |
|---|---|---|---|
| Sodium | **0.6.13** | 区块渲染重写（帧率核心） | 必须 0.6.x；0.8.x `breaks iris <1.8.13` |
| Iris | **1.8.8** | 光影加载（ComplementaryReimagined r5.9） | 1.21.1 分支唯一 release；`depends sodium 0.6.x` |
| ComplementaryReimagined | r5.9 | 光影包（唯一渲染方案） | VulkanMod 已弃（与 TaCZ stencil FBO 不兼容） |
| Fabric API | 0.116.17+1.21.1 | 前置 | — |

## 待装候选（#44 批次推荐，逐个核验后进包）

| 模组 | 作用 | 1.21.1 注意 |
|---|---|---|
| **Lithium** | 服务端/客户端通用逻辑优化（实体/寻路/碰撞） | 与 Sodium 同作者，兼容良好 |
| **ImmediatelyFast** | 立即模式渲染批处理（HUD/方块实体） | 与 Iris 有版本耦合，需核验 beta 匹配 |
| **EntityCulling** | 屏幕外实体/方块实体跳过渲染 | 对我们的自定义 HUD 无影响；对本项目大量 NPC 有利 |
| **FerriteCore** | 内存占用下降（区块状态/资源） | 32 人服客户端内存友好 |
| **ModernFix** | 启动/内存/运行时综合优化 | 注意其 `Dynamic Resources` 与部分模组冲突面 |

> ⚠️ 落地规则：每装一个都要过一遍其 jar `fabric.mod.json` 的 `depends`/`breaks`
> 再锁版本钉写入 gradle.properties / 发布清单；**宁缺毋滥**，性能模组不参与 core 规则层。

## 反优化清单（不装）

- **VulkanMod** —— 与 TaCZ `enableStencil` FBO 不兼容，启动即崩（2026-09-05 定论）。
- Sodium 0.8.x —— 破坏 Iris 1.8.x（1.21.1 无对应新 Iris）。

## 落地后续动作

1. 本机 PCL 客户端逐个手动加装（Li/IF/EC/FC/ModernFix 任一组合）跑一轮帧率基线；
2. 确认无崩溃、光影正常后再纳入 dev 客户端包发布；
3. 服务器端优化：view-distance/模拟距离按 32v32 目标回调，列入调参表。
