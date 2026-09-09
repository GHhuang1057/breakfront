# Geekhonize 账号平台 + 专属启动器 —— 规划（2026-09-06 定）

目标：把 Geekhonize 账号做成产品级跨作品账号体系，并让玩家**用账号直接登录并拉起 BREAKFRONT 客户端**。

## 决策（用户拍板）
- 节奏：**两线并行**（后端/门户批 ∥ .NET 启动器批；UI 改版穿插）。
- 启动器：**.NET（Avalonia UI）单代码库 → Windows + Linux + Android**。
  > 说明：MAUI 无 Linux 桌面官方目标（仅 Win/macOS/iOS/Android），无法满足「Linux 桌面」硬需求；
  > 故采用 Avalonia(.NET 8)：Win 桌面包 / Linux 桌面包(AppImage/tar.gz) / Android APK 同一套 C#。
- 邮件：**香港服务器(132) 自建邮件发送**（Postfix 直发/中继 + SPF/DKIM 记录），后端 SMTP 配置化；
  开发期 `GEO_MAIL_MODE=stub`（验证码写日志 + 响应回 dev_code），生产 `smtp`。
- 平台功能：**全都要**——邮箱验证码注册/找回/换绑、昵称与资料、用户管理与风控、多作品角色视图。

## 架构
```
┌────────────────────────────────────────────────────────────┐
│ 专属启动器 .NET/Avalonia (Win·Linux·Android)                 │
│  登录 Geekhonize → 保存 token → 校验/下载整合包 → 写 GeoSession│
│  → 启动 Minecraft(Fabric 1.21.1) → 客户端进服自动绑定账号     │
└───────────────┬──────────────────────────────┬─────────────┘
                │ https                       │ 预置 auth.token
┌───────────────▼──────────────┐   ┌──────────▼───────────────┐
│ Auth 后端(Rust, 132)          │   │ 网页账号中心(auth.portal) │
│ register(邮箱码)/login/me/    │◄──┤ 登录·注册·找回·资料·管理  │
│ reset/rebind/change_password │   │ 响应式/PWA(手机可用)      │
│ verification_codes 表 + SMTP │   └──────────────────────────┘
└───────┬──────────────────────┘
        │ SMTP
┌───────▼──────────────────────┐
│ 132 Postfix(自建发信) + 邮箱  │
└──────────────────────────────┘
```

## 里程碑
- **A1 后端邮箱验证码批（本批）**：`verification_codes` 表、`users.email_verified`、
  `POST /send_code{purpose:register|reset|rebind}`（60s 限频、stub 回 dev_code）、
  register 强制 email+code、`/reset_password`、`/rebind_email`、SMTP 传输(env 配置)；
  me/login 带 email_verified；单测补齐。
- **A2 网页账号中心 v2（本批）**：响应式(手机优先)+PWA；注册(邮箱码)/登录/找回/资料昵称/
  改密/换绑/多作品角色视图/管理员用户管理与风控 页。
- **A3 客户端联动（本批）**：BfGeoLoginScreen 增加 邮箱+验证码 注册（/geo register <u> <p> <email> <code>，
  /geo sendcode <email>），登录/绑定链路不变。
- **B1 启动器骨架（.NET/Avalonia）**：launcher/ 解决方案、Auth REST 客户端、登录 UI、
  token 落盘(GeoSession 同格式)、启动 BF 客户端管线桩；CI(launcher.yml) 三平台构建矩阵。
- **B2 启动器完整化**：整合包下载/自更新/实例管理/离线启动/Java 自动寻址（后续批次）。
- **C1 132 邮件落地**：Postfix 安装配置 + geekhonize.top SPF/DKIM(Cloudflare) + 发信冒烟(QQ/163)。

## 状态跟踪
- [x] A1 后端（2026-09-06 完成，生产运行）
- [x] A2 门户 v2（完成上线）
- [x] A3 客户端联动（完成发布）
- [x] B1 启动器（完成：桌面 Win/Linux 登录+会话；工程拆分 Desktop/App/Shared）
- [~] B1.5 Android APK（完成：CI 产出 arm64 apk 并发布，官网可下载）
- [~] C1 132 邮件（完成 Postfix+OpenDKIM 安装与 smtp 模式，**待用户在 Cloudflare 加 SPF/DKIM TXT 后真收件验证**）
- [ ] B2 启动器完整化（整合包装配/自更新/Java 寻址，后续批次）
