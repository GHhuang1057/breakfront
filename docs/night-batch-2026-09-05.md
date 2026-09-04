# 夜间批次验收清单 · 2026-09-05

> 说明：本批次由 AI 在用户就寝期间连续执行，每块完成门槛 = CI 全绿 + 服务端冒烟。
> 验收方法：游戏内升级客户端 → 按条目逐项实测 → 勾选「通过 / 待调」。

## 本批已完成（代码/CI/服务端均已就绪，待你真机勾选）
- CI 构建：A/B = `4d9adb3`，D/E = `e6d1465`（当前最新 release `dev-e6d1465`）
- 本地服务端已热更至 `e6d1465`，更新源 manifest 已同步（core babcf44a / client ff6bf229）
- RCON 冒烟已验证：`round over (DEFENDER_WIN)` → 8s → `auto started next round`（服务端日志 00:33:22→00:33:30）

## 0. 升级方式
- GitHub Releases → 最新 `dev-xxxxxx` → 下载 `breakfront-dev-client-mods-*.zip` 覆盖 mods（或启动游戏让「启动时更新检查」自动拉取+重启）
- 服务端：使用同一 dev 版本的 jar（一键包 `breakfront-dev-server-*.zip` 或本地服热更）

## 1. 启动与更新链路
- [ ] 启动后主菜单出现，右上状态徽章最终停在「已是最新」（绿点）
- [ ] 若曾下发新包：弹出「待重启应用」提示并可一键退出/应用（bfupdate\apply-update.bat）
- [ ] PLAY → 进入「正在连接服务器」加载层（扫动进度条）→ 进服（不再出现「该服务器不支持转移」，服务端已 accepts-transfers=true）
- [ ] 开屏为 BREAKFRONT 品牌图

## 2. 主菜单（BF2042）
- [ ] 顶栏导航 PLAY / 单机 / 设置 / 退出；PLAY 有黄色下划线
- [ ] ALL-OUT WARFARE 主卡：背景城市天际线视差、黄底黑字「部署」按钮 hover 反白
- [ ] Portal / 危险区「即将推出」小卡、底部键位提示条
- [ ] 菜单 hover / 进出场动画顺滑

## 3. 游戏内 HUD v2
- [ ] 左上 OBJECTIVE：据点字母胶囊，占领色（黄=攻/蓝=守/争夺闪烁）
- [ ] 右上 TIME 倒计时 + DEPLOY 攻方部署资源（低到 ≤10 变红）
- [ ] 中上「战斗中 · 扇区 x/y」；底部各据点推进条（含 已占领/推进%/防守中 状态）
- [ ] 击杀流：右上出现、行淡入上滑、6 秒后淡出；爆头金标、攻方减员红字
- [ ] 世界内据点菱形字母标记 + 地面描边圆环仍在

## 4. 部署界面（回合开始）
- [ ] /bf start 后（部署倒计时）自动弹出全屏部署页（倒计时大字 + 目标点列表 + 进度）
- [ ] 进入 BATTLE 自动关闭；ESC 可提前关闭且不会反复弹出
- [ ] 部署页视觉：标题黄字、深色渐变、菱形字母行

## 5. 回合自动循环 / 服务端
- [ ] /bf end 结算 → 约 8 秒后自动进入下一局倒计时（队伍/锚点保持）
- [ ] /bf stop → 回大厅；/bf status 各阶段显示正常
- [ ] 服务端 RCON 冒烟：`scripts/smoke_round.sh` 全程 SMOKE OK

## 6. 服务端一键包（运营/公测准备）
- [ ] Release 内含 `breakfront-dev-server-*.zip`（fabric-server-launch.jar + 全量 mods + server.properties 模板 + start.bat + 外部地图标记）
- [ ] 解压 → 放 world/（Metro）→ start.bat 启动 → Done → 客户端可连
- [ ] 更新源 :25610 manifest 随包在服务器目录自动就绪

## 7. 待办/未纳入（下批）
- 重生-部署选择链路（死亡回部署页 + 据点重生，需服务端规则+可能 mixin）
- 兵种/装备发放（TaCZ 枪械上阵）与部署界面选兵种
- TAB 计分板 / 对局结算 MVP 动画
- 协议版本号软提示（当前由更新包双 jar 同推兜底）
- 地图内容继续推进需另行决策（当前用 Operation Metro 外部图）

## 第二波（01:00-01:20，用户追加需求后的执行批次）
已完成（CI 全绿 + 服务端实测）：
- **S1 出生/自动赛程**：进服自动补少人队；开局全员部署到己方出生区（攻=首据点外沿、守=尾据点外沿，`/bf spawns set` 可覆盖）；战中死亡自动钉重生点=己方部署区；`/bf autostart on` 大厅双阵营就绪 5s 自动开局
- **S2 战绩统计**：ScoreKeeper（纯 Java+单测）/`ScoreboardPayload`(1s)/`/bf board`/客户端接收
- **C1 结算卡**：ROUND_END 全屏“ROUND OVER”（比分+MVP 行）· **C2**：战斗中顶部“攻 x:y 守”比分带
- **B1 增援 NPC v0.5**：`/bf npc add attacker|defender <n>`；按缺员补位；向目标据点推进/驻守（占点）；可被杀计入击杀者战绩；**实测：无真人服务器中攻方 NPC 将 A1 占领至 100%**；连修 3 坑（zombie 自燃→防火再生；屋顶穿墙闷杀→地形跟随+轴分离避障；无人服未加载区块→forceload）
- **G1 枪包调研**：tacz-refab 无内置枪数据；1.21.1 需外部 gunpack（用 pack upgrader 转 NeoForge 兼容，源在 TaCZ Discord 社区展示）；机制已通，包需用户侧提供（同地图授权模式）

### 状态
- 最新 dev：`dev-5e8a45f`（全绿，已热更本地服 + 更新源 sync）
- 待用户醒后：装新客户端包 → 多人开服 `/bf npc add` 看 bot 交战；验收清单第一波条目并行勾选
