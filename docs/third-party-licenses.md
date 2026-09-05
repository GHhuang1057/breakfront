# BREAKFRONT 第三方模组许可清单（来源: G:/bf_run/rel_8f1818d/breakfront-dev-client-mods-8f1818d.zip）

> 自动生成：pack/tools/licenses.py · 发布前请人工核对各项目页最新许可


| 文件 | 版本 | License | Authors | 主页/联系 |
|---|---|---|---|---|
| fabric-api-0.116.17+1.21.1.jar | 0.116.17+1.21.1 | Apache-2.0 | FabricMC | homepage: https://fabricmc.net, irc: irc://irc.esper.net:6667/fabric, issues: https://github.com/FabricMC/fabric/issues, |
| ForgeConfigAPIPort-v21.1.6-1.21.1-Fabric.jar | 21.1.6 | MPL-2.0 | Fuzs | homepage: https://github.com/Fuzss/forgeconfigapiport, issues: https://github.com/Fuzss/forgeconfigapiport/issues, sourc |
| TACZ-Refabricated-1.21.1-0.7.0-forge1.1.8-hotfix.jar | 0.7.0-forge1.1.8-hotfix | 代码 GPL-3.0；内置默认枪包资产 CC BY-NC-ND 4.0 | Sh1roCu（原版 TACZ Dev Team / nekocrane） | CurseForge #1334246 / Modrinth tacz-refabricated（平台均标 GPL-3.0-only） |

## 注意事项
- 第三方模组仅作「内容源」集成，其许可由各自作者所有；发布整合包前请逐项确认（尤其 TaCZ 枪械/枪包与地图素材）。
- 自研 breakfront / breakfront-client 以 MIT 发布，不属于上表。

### TaCZ 合规要点（2026-09-05 按作者官方页面核实）
- **代码层（TaCZ 及移植改动）= GPL-3.0**（CurseForge #1334246 与 Modrinth 均标 GPL-3.0-only，仓库根 LICENSE = GPLv3 全文）。
- **内置默认枪包资产 = CC BY-NC-ND 4.0**：不得销售、不得修改后当自由许可再分发；原样随包分发（非商业 + 署名）在其条款内。
- 结论性路径：
  1. 整个整合包以 **GPL-3.0 兼容**方式开源（自研代码 MIT→GPL-3.0），代码层即可合法再分发/修改 TaCZ；
  2. 默认枪包资产**原样保留**（不改其 gunpack 数据/模型），分发署名 + **完全非商业**（无销售/赞助/广告/付费服变现）；
  3. 将来若有商业变现诉求 → 联系作者另获授权，或换宽松许可枪源（Vic's Point Blank 等）。
