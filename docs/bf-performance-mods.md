# BREAKFRONT · 性能优化模组清单（#44）

> 状态：清单核验版（2026-09-05）。所有版本钉与依赖/冲突核验见下表；**以 jar 内
> `fabric.mod.json` 的 depends/breaks 为准**（Iris/Sodium 强版本耦合教训，见下）。

## 已实装（现网客户端必备）

| 模组 | 版本钉 | 环境 | 作用 | 备注 |
|---|---|---|---|---|
| Sodium | **0.6.13** | client | 区块渲染重写（帧率核心） | 必须 0.6.x；0.8.x `breaks iris <1.8.13` |
| Iris | **1.8.8** | client | 光影加载（ComplementaryReimagined r5.9） | 1.21.1 分支唯一 release；`depends sodium 0.6.x` |
| ComplementaryReimagined | r5.9 | client | 光影包（唯一渲染方案） | VulkanMod 已弃（与 TaCZ stencil FBO 不兼容） |
| Fabric API | 0.116.17+1.21.1 | both | 前置 | — |
| **Lithium** | **mc1.21.1-0.15.4-fabric** | both | 服务端/客户端通用逻辑优化（实体/寻路/碰撞） | `depends fabricloader>=0.15.1`；`breaks optifabric`（与 Sodium 同作者，兼容良好） |
| **FerriteCore** | **7.0.3-fabric** | both | 内存占用下降（区块状态/资源） | `depends fabricloader>=0.14.21`；`breaks hydrogen`；32 人服客户端/服务端均友好 |
| **ModernFix** | **5.25.1+mc1.21.1** | both | 启动/内存/运行时综合优化 | `depends fabricloader>=0.16.10`；`breaks dashloader<5.0.0-beta.1`；其 `Dynamic Resources` 与部分模组冲突面，需 PCL 实测 |
| **EntityCulling** | **1.10.5** | client | 屏幕外实体/方块实体跳过渲染 | `depends fabric-api`；`breaks tlskincape`；对本项目大量 NPC 有利 |
| **ImmediatelyFast** | **1.6.13+1.21.1-fabric** | client | 立即模式渲染批处理（HUD/方块实体） | `depends java>=21, fabricloader>=0.16.0`；`breaks vulkanmod` |
| **Natural Motion Blur** | **1.3.0** | client | 动态模糊（motion blur，1.21.1 下载量最高者） | `depends cloth-config + satin`（由 assemble 脚本递归拉取）；`breaks` 无 |

> 版本钉来源：Modrinth API 于 1.21.1 + fabric 过滤下取 release 最新版，见 `pack/tools/mod_pins.json`。
> 客户端包 = 上述全部；**服务端包仅放 env=both 项**（Lithium / FerriteCore / ModernFix + 自研 breakfront core），
> EntityCulling / ImmediatelyFast / Natural Motion Blur 及其 cloth-config / satin 依赖不进服务端。

## 待装候选（已核验、暂未实装）

_（#44 批次五项性能模组 + 动态模糊已全数实装并钉版本，无剩余候选。）_

> ⚠️ 落地规则：每装一个都要过一遍其 jar `fabric.mod.json` 的 `depends`/`breaks`
> 再锁版本钉写入 gradle.properties / 发布清单；**宁缺毋滥**，性能模组不参与 core 规则层。

## 反优化清单（不装）

- **VulkanMod** —— 与 TaCZ `enableStencil` FBO 不兼容，启动即崩（2026-09-05 定论）。
- Sodium 0.8.x —— 破坏 Iris 1.8.x（1.21.1 无对应新 Iris）。

## 落地后续动作

1. ⚠️ **Natural Motion Blur 已移除（2026-09-06）**：其 post shader 找不到 uniform
   （projection/InSize/view_pixel_size，见客户端日志 `No uniform found with name … in shader naturalmotionblur:shaders/post/motion_blur.json`），
   与 Iris 1.8.8 + Complementary 光影后处理链冲突 → 光影下画面异常。动态模糊改用光影自身选项
   （Complementary 设置内 Motion Blur / TAA）或不开；如后续要动糊须改用与 Iris 兼容的实现。
2. 本机 PCL 客户端逐个手动加装（Li/IF/EC/FC/ModernFix 任一组合）跑一轮帧率基线；
3. 确认无崩溃、光影正常后再纳入 dev 客户端包发布；
4. 服务器端优化：view-distance/模拟距离按 32v32 目标回调，列入调参表。
