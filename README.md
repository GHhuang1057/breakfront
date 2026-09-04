# BREAKFRONT · 破阵前线

Minecraft（Fabric **1.21.1**）32v32 大战场整合包 —— 攻防模式（Breakthrough）玩法。

> 状态：P1/P2 开发中（规则内核 + BF2042 客户端界面 + 人机增援已可实机运行）。自研部分 MIT 开源；第三方模组许可见 [`docs/third-party-licenses.md`](docs/third-party-licenses.md)（含 TaCZ 的 CC BY-NC-ND 限制提示）。

## 开发版发布（GitHub Releases）

每次推 main 自动产出（`dev-<sha>` pre-release）：

| 资产 | 用途 |
|---|---|
| `breakfront-dev-client-mods-<sha>.zip` | 客户端 `mods/`（自研双模组 + TaCZ + Fabric API） |
| `breakfront-dev-server-<sha>.zip` | **服务端一键包**：解压 → 放世界目录为 `world/` → `start.bat`（或 java -jar）→ Done 即开服 |
| `*.jar` | 自研两模组单 jar |
| `*-manifest-*.json` | 版本/sha256 全清单（客户端启动自检用） |

客户端每次启动自动向服务器更新源（`breakfront-sync/` 目录，HTTP :25610）校验更新；新版提示重启生效。`breakfront.map.external` 文件存在时跳过自建地图、使用你放入的外部世界。

## 开发指令（服务端 /bf）

```
/bf team attacker|defender        加入阵营
/bf start / stop / end            对局控制
/bf status / board / npc status   实时状态/击杀榜/人机
/bf autostart on                  双阵营就绪自动开局
/bf npc add attacker|defender <n> 增援人机
/bf anchor <i> set <x> <z>        在地图上定点配据点
/bf spawns set attacker|defender <x> <z>   覆盖出生区
```

## 设计文档

见 [`docs/bf-modpack-charter.md`](docs/bf-modpack-charter.md)。

## 仓库结构（规划）

| 路径 | 内容 |
|---|---|
| `breakfront/` | 自研核心模组（服务端权威逻辑：回合状态机/占点/票数/兵种/重生） |
| `breakfront-client/` | 自研客户端模组（BF2042 风格矢量界面与 HUD） |
| `docs/` | 立项设计文档（v0.2）与规格 |
| `pack/` | 整合包组装（客户端/服务端模组清单、配置、启动脚本） |
| `maps/` | 首发图「高架走廊」viaduct 的地图资产与配置包 |
| `.github/workflows/` | GitHub Actions：编译模组 jar / 冒烟产物 |

## 设计文档

见 [`docs/bf-modpack-charter.md`](docs/bf-modpack-charter.md)。

## 构建

- 环境：JDK 21（Temurin）+ Gradle wrapper（CI 自动拉取）
- 触发：push / PR / workflow_dispatch
