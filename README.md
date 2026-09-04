# BREAKFRONT · 破阵前线

Minecraft（Fabric **1.21.1**）32v32 大战场整合包 —— 攻防模式（Breakthrough）玩法。

> 状态：P0 脚手架搭建中。自研部分将开源发布；第三方模组资产许可由发布方（仓库负责人）自行核对。

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
