# Geekhonize Auth（Rust 通用账号服务）

跨作品统一账号体系：**Geekhonize 账号**。BREAKFRONT（Minecraft）、Web 管理台、未来
其它作品共用同一套注册/登录/角色。数据保存在香港服务器（103.24.217.132，MariaDB）。

## 技术栈

Rust（axum 0.7 + tokio）+ sqlx(MySQL/MariaDB) + argon2（密码哈希）+ jsonwebtoken（HS256 无状态 JWT）。
部署为单二进制 + systemd；公网由香港服务器 openresty 反代（`auth.geekhonize.top` 或 `/api/v1/auth/*`）。

## 构建 / 测试

```bash
cargo build --release
cargo test            # 单元测试（哈希/校验/JWT，无 DB）
```

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET  | `/healthz` | 存活 |
| POST | `/api/v1/auth/register` | `{username,password,email?,display_name?,app?}`；全库首位=admin |
| POST | `/api/v1/auth/login`    | `{username,password,app?}` → `{access_token,user{id,username,roles,…}}` |
| GET  | `/api/v1/auth/me`       | `Authorization: Bearer <token>` + `{app}` → 身份/角色/过期 |
| POST | `/api/v1/auth/logout`   | 契约占位（无状态 JWT，客户端弃令牌） |

`app` 默认 `breakfront`；需在 `GEO_APPS` 白名单内（`geekhonize-portal`=网页端、`sxsm`=盛兴订货…）。

## 部署（香港服务器 103.24.217.132）

1. CI（`.github/workflows/auth.yml`）在 push `auth/**` 时自动 `cargo test` + 交叉构建
   `auth-server-linux-x86_64`，发布为 prerelease `auth-dev-<sha>`（资产 `geekhonize-auth-linux-x86_64.zip`）。
2. 服务器一次性准备：
   - 建库建用户：`CREATE DATABASE geekhonize CHARACTER SET utf8mb4;`（应用启动自动建表）
   - `mkdir -p /opt/geekhonize-auth && cd /opt/geekhonize-auth`，下载 zip 解压出 `geekhonize-auth` 二进制
   - 写 `.env`（改数据库口令与随机 GEO_JWT_SECRET）
   - systemd unit（示例 `deploy/geekhonize-auth.service`）`systemctl enable --now geekhonize-auth`
3. openresty 反代片段示例（见 `deploy/openresty-example.conf`），Cloudflare 解析
   `auth.geekhonize.top` → 香港服务器后即可对外。

> 安全提示：`GEO_JWT_SECRET` 一旦发布不可更换（否则全部令牌失效）；部署后立即改数据库口令。
> 上线前建议追加：失败限速、refresh token、邮箱验证（规划内，非 MVP）。

## 与 BREAKFRONT 的接线（后续批次）

- Java core：进服时客户端提交 `GEO token` → 服务端 `GET /auth/me` 校验 → 绑定玩家 `username`
  （替代自报名防冒名），roles 映射 `admin→管理权限`。
- Web 管理台：登录页可选「Geekhonize 账号」，服务端代理登录并校验 roles 含 admin。
- 客户端定制登录屏：BREAKFRONT 风格注册/登录页（矢量 UI），成功后本地存 token。
