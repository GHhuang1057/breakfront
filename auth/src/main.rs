//! Geekhonize Auth —— 通用账号认证服务（跨作品统一账号）。
//!
//! 用途：为 BREAKFRONT(Minecraft)/Web 管理台/未来其它作品 提供一套「Geekhonize 账号」：
//! 注册（邮箱验证码）/ 登录（JWT）/ 身份查询 / 角色 / 找回密码 / 换绑邮箱。
//! 离线 MC 客户端持账号令牌进服，服务端经本服务校验。
//!
//! 运行：读取环境变量（见 .env.example），依赖 MySQL/MariaDB。
//!   GEO_DATABASE_URL    mysql://user:pass@host:3306/geekhonize
//!   GEO_JWT_SECRET      >=32 字符随机串
//!   GEO_APPS            应用白名单，逗号分隔（默认 breakfront,geekhonize-portal,sxsm）
//!   GEO_PORT/GEO_BIND   监听（默认 8787 / 127.0.0.1）
//!   邮件（验证码）：
//!   GEO_MAIL_MODE       stub|smtp（默认 stub：验证码写日志并在响应回 dev_code，便于联调；生产用 smtp）
//!   GEO_SMTP_HOST       默认 127.0.0.1（132 自建 Postfix）
//!   GEO_SMTP_PORT       默认 25
//!   GEO_SMTP_USER/PASS  中继需认证时填（可空）
//!   GEO_SMTP_FROM       发件人（默认 no-reply@geekhonize.top）

use argon2::password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use argon2::Argon2;
use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use sqlx::mysql::MySqlPoolOptions;
use sqlx::MySqlPool;
use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tower_http::cors::CorsLayer;
use tracing::{error, info};
use tracing_subscriber::EnvFilter;

use argon2::password_hash::rand_core::RngCore;

// ---------------- 配置 ----------------

#[derive(Clone)]
struct Cfg {
    db_url: String,
    jwt_secret: String,
    apps: HashSet<String>,
    token_ttl_secs: i64,
    mail_mode: String, // stub | smtp
    smtp_host: String,
    smtp_port: u16,
    smtp_user: String,
    smtp_pass: String,
    smtp_from: String,
}

fn env_or(key: &str, dflt: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| dflt.to_string())
}

fn cfg_from_env() -> Cfg {
    let apps = env_or("GEO_APPS", "breakfront,geekhonize-portal,sxsm")
        .split(',')
        .map(|s| s.trim().to_lowercase())
        .filter(|s| !s.is_empty())
        .collect();
    let smtp_port: u16 = env_or("GEO_SMTP_PORT", "25").parse().unwrap_or(25);
    Cfg {
        db_url: env_or("GEO_DATABASE_URL", "mysql://geekhonize:geekhonize@127.0.0.1:3306/geekhonize"),
        jwt_secret: env_or("GEO_JWT_SECRET", ""),
        apps,
        token_ttl_secs: env_or("GEO_TOKEN_TTL", "604800").parse().unwrap_or(604800),
        mail_mode: env_or("GEO_MAIL_MODE", "stub").trim().to_lowercase(),
        smtp_host: env_or("GEO_SMTP_HOST", "127.0.0.1"),
        smtp_port,
        smtp_user: env_or("GEO_SMTP_USER", ""),
        smtp_pass: env_or("GEO_SMTP_PASS", ""),
        smtp_from: env_or("GEO_SMTP_FROM", "no-reply@geekhonize.top"),
    }
}

// ---------------- 状态 ----------------

#[derive(Clone)]
struct AppState {
    cfg: Cfg,
    pool: MySqlPool,
}

// ---------------- 模型 ----------------

#[derive(Debug, Serialize)]
struct UserOut {
    id: u64,
    username: String,
    display_name: Option<String>,
    email: Option<String>,
    email_verified: bool,
    roles: Vec<String>,
}

#[derive(Deserialize)]
struct RegisterReq {
    username: String,
    password: String,
    email: Option<String>,
    code: Option<String>,
    #[serde(default)]
    display_name: Option<String>,
    #[serde(default = "default_app")]
    app: String,
}

#[derive(Deserialize)]
struct LoginReq {
    username: String,
    password: String,
    #[serde(default = "default_app")]
    app: String,
}

fn default_app() -> String {
    "breakfront".to_string()
}

#[derive(Serialize)]
struct LoginOut {
    ok: bool,
    access_token: String,
    token_type: &'static str,
    expires_in: i64,
    user: UserOut,
}

#[derive(Serialize)]
struct ApiOk<T: Serialize> {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    data: Option<T>,
    #[serde(skip_serializing_if = "Option::is_none")]
    msg: Option<String>,
}

#[derive(Serialize, Deserialize, Clone)]
struct Claims {
    sub: String,       // username
    uid: u64,          // user id
    roles: Vec<String>,
    app: String,
    iat: i64,
    exp: i64,
}

#[derive(Deserialize)]
struct SendCodeReq {
    email: String,
    #[serde(default = "default_purpose")]
    purpose: String, // register | reset | rebind
}

fn default_purpose() -> String {
    "register".to_string()
}

#[derive(Deserialize)]
struct ResetPwReq {
    email: String,
    code: String,
    new_password: String,
}

#[derive(Deserialize)]
struct RebindEmailReq {
    new_email: String,
    code: String,
}

// ---------------- 错误 ----------------

#[derive(Debug)]
struct AppErr(StatusCode, String);

impl IntoResponse for AppErr {
    fn into_response(self) -> Response {
        (self.0, Json(ApiOk::<()> { ok: false, data: None, msg: Some(self.1) })).into_response()
    }
}

impl<E: std::fmt::Display> From<E> for AppErr
where
    E: std::error::Error,
{
    fn from(e: E) -> Self {
        error!("internal: {}", e);
        AppErr(StatusCode::INTERNAL_SERVER_ERROR, "服务内部错误".into())
    }
}

// ---------------- 工具 ----------------

fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as i64
}

fn normalize_username(raw: &str) -> String {
    raw.trim().to_lowercase().chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-' || *c == '.')
        .collect()
}

fn validate_password(pw: &str) -> Result<(), AppErr> {
    if pw.len() < 6 || pw.len() > 128 {
        return Err(AppErr(StatusCode::BAD_REQUEST, "密码长度需 6-128 字符".into()));
    }
    Ok(())
}

fn valid_email(raw: &str) -> bool {
    let t = raw.trim();
    if t.len() < 5 || t.len() > 160 {
        return false;
    }
    match t.find('@') {
        Some(i) => i > 0 && i + 1 < t.len() && t[i + 1..].contains('.'),
        None => false,
    }
}

fn hash_password(pw: &str) -> Result<String, AppErr> {
    let salt = SaltString::generate(&mut OsRng);
    let h = Argon2::default()
        .hash_password(pw.as_bytes(), &salt)
        .map_err(|e| AppErr(StatusCode::INTERNAL_SERVER_ERROR, format!("hash error: {e}")))?;
    Ok(h.to_string())
}

fn verify_password(pw: &str, hash: &str) -> bool {
    PasswordHash::new(hash)
        .map(|ph| Argon2::default().verify_password(pw.as_bytes(), &ph).is_ok())
        .unwrap_or(false)
}

/// 6 位数字验证码。
fn gen_code() -> String {
    format!("{:06}", OsRng.next_u32() % 1_000_000)
}

fn issue_token(cfg: &Cfg, u: &UserOut) -> Result<String, AppErr> {
    let now = now();
    let claims = Claims {
        sub: u.username.clone(),
        uid: u.id,
        roles: u.roles.clone(),
        app: "geekhonize".into(), // 跨作品统一主体；aud=app 语义由调用方按需校验
        iat: now,
        exp: now + cfg.token_ttl_secs,
    };
    encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(cfg.jwt_secret.as_bytes()),
    )
    .map_err(|e| AppErr(StatusCode::INTERNAL_SERVER_ERROR, format!("jwt: {e}")))
}

fn decode_token(cfg: &Cfg, token: &str) -> Result<Claims, AppErr> {
    let data = decode::<Claims>(
        token,
        &DecodingKey::from_secret(cfg.jwt_secret.as_bytes()),
        &Validation::default(),
    )
    .map_err(|_| AppErr(StatusCode::UNAUTHORIZED, "登录已过期，请重新登录".into()))?;
    Ok(data.claims)
}

async fn ensure_tables(pool: &MySqlPool) -> Result<(), sqlx::Error> {
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS users (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            username VARCHAR(40) NOT NULL UNIQUE,
            pass_hash VARCHAR(255) NOT NULL,
            email VARCHAR(160) NULL,
            display_name VARCHAR(64) NULL,
            roles VARCHAR(120) NOT NULL DEFAULT 'player',
            status TINYINT NOT NULL DEFAULT 1,
            email_verified TINYINT NOT NULL DEFAULT 0,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    )
    .execute(pool)
    .await?;
    // 兼容旧库补列（已存在则忽略）
    let _ = sqlx::query("ALTER TABLE users ADD COLUMN email_verified TINYINT NOT NULL DEFAULT 0")
        .execute(pool)
        .await;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS apps (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            app_key VARCHAR(40) NOT NULL UNIQUE,
            display_name VARCHAR(80) NOT NULL DEFAULT '',
            enabled TINYINT NOT NULL DEFAULT 1,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    )
    .execute(pool)
    .await?;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS verification_codes (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            email VARCHAR(160) NOT NULL,
            purpose VARCHAR(16) NOT NULL,
            code CHAR(6) NOT NULL,
            used TINYINT NOT NULL DEFAULT 0,
            expires_at BIGINT NOT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            KEY idx_email_purpose (email, purpose, used)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    )
    .execute(pool)
    .await?;
    Ok(())
}

// ---------------- 验证码 / 邮件 ----------------

/// 发码频控（email|purpose → 上次发送秒）。60 秒一次。
static CODE_RATE: OnceLock<Mutex<HashMap<String, i64>>> = OnceLock::new();

fn rate_key(email: &str, purpose: &str) -> String {
    format!("{}|{}", email.trim().to_lowercase(), purpose)
}

fn rate_allowed(key: &str) -> bool {
    let m = CODE_RATE.get_or_init(|| Mutex::new(HashMap::new()));
    let mut g = m.lock().unwrap_or_else(|e| e.into_inner());
    let last = g.get(key).copied().unwrap_or(0);
    if now() - last < 60 {
        return false;
    }
    g.insert(key.to_string(), now());
    true
}

async fn insert_code(pool: &MySqlPool, email: &str, purpose: &str, code: &str) -> Result<(), AppErr> {
    let e = email.trim().to_lowercase();
    sqlx::query("DELETE FROM verification_codes WHERE email=? AND purpose=? AND used=0")
        .bind(&e)
        .bind(purpose)
        .execute(pool)
        .await?;
    sqlx::query("INSERT INTO verification_codes (email, purpose, code, expires_at) VALUES (?,?,?,?)")
        .bind(&e)
        .bind(purpose)
        .bind(code)
        .bind(now() + 300)
        .execute(pool)
        .await?;
    Ok(())
}

/// 校验并消费验证码（一次性；成功置 used=1）。
async fn consume_code(pool: &MySqlPool, email: &str, purpose: &str, code: &str) -> Result<bool, AppErr> {
    let e = email.trim().to_lowercase();
    let c = code.trim().to_string();
    let row: Option<(u64,)> = sqlx::query_as(
        "SELECT id FROM verification_codes WHERE email=? AND purpose=? AND code=? AND used=0 AND expires_at>=? ORDER BY id DESC LIMIT 1",
    )
    .bind(&e)
    .bind(purpose)
    .bind(&c)
    .bind(now())
    .fetch_optional(pool)
    .await?;
    match row {
        Some((id,)) => {
            sqlx::query("UPDATE verification_codes SET used=1 WHERE id=?")
                .bind(id)
                .execute(pool)
                .await?;
            Ok(true)
        }
        None => Ok(false),
    }
}

fn purpose_label(purpose: &str) -> String {
    match purpose {
        "reset" => "找回密码".to_string(),
        "rebind" => "换绑邮箱".to_string(),
        _ => "注册账号".to_string(),
    }
}

fn base64_std(data: &[u8]) -> String {
    const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(T[(n >> 18 & 63) as usize] as char);
        out.push(T[(n >> 12 & 63) as usize] as char);
        if chunk.len() > 1 {
            out.push(T[(n >> 6 & 63) as usize] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(T[(n & 63) as usize] as char);
        } else {
            out.push('=');
        }
    }
    out
}

/// 读取 SMTP 多行响应直到 `prefix ` 结尾；非 2xx/3xx 立即报错。
async fn read_reply<B: tokio::io::AsyncBufRead + Unpin>(
    rd: &mut B,
    prefix: &str,
    line: &mut String,
) -> Result<String, String> {
    loop {
        line.clear();
        let n = rd.read_line(line).await.map_err(|e| format!("smtp recv: {e}"))?;
        if n == 0 {
            return Err("smtp closed".into());
        }
        let t = line.trim_end().to_string();
        if t.starts_with(prefix) {
            let sep = t.as_bytes().get(3).copied();
            if sep == Some(b' ') {
                return Ok(t);
            }
            continue; // 250-xxx 续行
        }
        if t.len() >= 3 && !t.starts_with('2') && !t.starts_with('3') {
            return Err(format!("smtp: {t}"));
        }
        // 其它 2xx/3xx 行（非目标前缀）继续读，防乱序
    }
}

/// 轻量 SMTP 客户端（EHLO / [AUTH LOGIN] / MAIL / RCPT / DATA），面向本地自建 Postfix 或开放中继。
async fn smtp_deliver(cfg: &Cfg, to: &str, code: &str, purpose: &str) -> Result<(), String> {
    let addr = format!("{}:{}", cfg.smtp_host, cfg.smtp_port);
    let stream = tokio::net::TcpStream::connect(&addr)
        .await
        .map_err(|e| format!("smtp connect: {e}"))?;
    let (r, mut w) = stream.into_split();
    let mut rd = BufReader::new(r);
    let mut line = String::new();

    let _greet = read_reply(&mut rd, "220", &mut line).await?;
    w.write_all(b"EHLO geekhonize.top\r\n").await.map_err(|e| format!("smtp write: {e}"))?;
    let _ehlo = read_reply(&mut rd, "250", &mut line).await?;

    if !cfg.smtp_user.is_empty() {
        w.write_all(b"AUTH LOGIN\r\n").await.map_err(|e| format!("smtp write: {e}"))?;
        let _u1 = read_reply(&mut rd, "334", &mut line).await?;
        w.write_all(base64_std(cfg.smtp_user.as_bytes()).as_bytes())
            .await
            .map_err(|e| format!("smtp write: {e}"))?;
        w.write_all(b"\r\n").await.map_err(|e| format!("smtp write: {e}"))?;
        let _u2 = read_reply(&mut rd, "334", &mut line).await?;
        w.write_all(base64_std(cfg.smtp_pass.as_bytes()).as_bytes())
            .await
            .map_err(|e| format!("smtp write: {e}"))?;
        w.write_all(b"\r\n").await.map_err(|e| format!("smtp write: {e}"))?;
        let _u3 = read_reply(&mut rd, "235", &mut line).await?;
    }

    w.write_all(format!("MAIL FROM:<{}>\r\n", cfg.smtp_from).as_bytes())
        .await
        .map_err(|e| format!("smtp write: {e}"))?;
    let _mf = read_reply(&mut rd, "250", &mut line).await?;
    w.write_all(format!("RCPT TO:<{}>\r\n", to.trim()).as_bytes())
        .await
        .map_err(|e| format!("smtp write: {e}"))?;
    let _rc = read_reply(&mut rd, "250", &mut line).await?;
    w.write_all(b"DATA\r\n").await.map_err(|e| format!("smtp write: {e}"))?;
    let _d1 = read_reply(&mut rd, "354", &mut line).await?;

    let label = purpose_label(purpose);
    let subject = format!("【Geekhonize】{label}验证码：{code}");
    let body = format!(
        "您正在{label}。\n\n验证码：{code}\n有效期 5 分钟，请勿泄露给他人。\n\n如果不是您本人操作，请忽略本邮件。\n—— Geekhonize 账号中心",
        label = label
    );
    let msg = format!(
        "From: <{}>\r\nTo: <{}>\r\nSubject: {}\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n{}\r\n.\r\n",
        cfg.smtp_from, to.trim(), subject, body
    );
    w.write_all(msg.as_bytes()).await.map_err(|e| format!("smtp write: {e}"))?;
    let _d2 = read_reply(&mut rd, "250", &mut line).await?;
    let _ = w.write_all(b"QUIT\r\n").await;
    Ok(())
}

/// 发送验证码。stub 模式：日志 + 由调用方把 dev_code 返回给响应（便于联调/冒烟）。
async fn deliver_code(cfg: &Cfg, to: &str, code: &str, purpose: &str) -> Result<(), AppErr> {
    if cfg.mail_mode == "smtp" {
        smtp_deliver(cfg, to, code, purpose)
            .await
            .map_err(|e| AppErr(StatusCode::BAD_GATEWAY, format!("邮件发送失败: {e}")))?;
    } else {
        info!("[mail-stub] to={to} purpose={purpose} code={code}");
    }
    Ok(())
}

// ---------------- Handlers ----------------

async fn health() -> Json<ApiOk<serde_json::Value>> {
    Json(ApiOk {
        ok: true,
        data: Some(serde_json::json!({"service": "geekhonize-auth", "ts": now()})),
        msg: None,
    })
}

async fn root() -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "service": "geekhonize-auth",
        "version": "0.2.0",
        "message": "Geekhonize 通用账号认证服务（邮箱验证码版）",
        "endpoints": [
            "GET  /healthz",
            "POST /api/v1/auth/send_code   {email,purpose:register|reset|rebind}",
            "POST /api/v1/auth/register    {username,password,email,code}",
            "POST /api/v1/auth/login",
            "GET  /api/v1/auth/me",
            "POST /api/v1/auth/logout",
            "POST /api/v1/auth/change_password",
            "POST /api/v1/auth/reset_password {email,code,new_password}",
            "POST /api/v1/auth/rebind_email   {new_email,code} (Bearer)",
            "POST /api/v1/auth/update_profile {display_name} (Bearer)",
            "GET  /api/v1/auth/admin/users    ?q= (admin)",
            "POST /api/v1/auth/admin/user     {id,roles?,status?} (admin)"
        ],
        "apps": ["breakfront", "geekhonize-portal", "sxsm"],
        "mail_mode": "stub"
    }))
}

async fn send_code(
    State(st): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<SendCodeReq>,
) -> Result<Json<serde_json::Value>, AppErr> {
    let purpose = req.purpose.trim().to_lowercase();
    if !matches!(purpose.as_str(), "register" | "reset" | "rebind") {
        return Err(AppErr(StatusCode::BAD_REQUEST, "purpose 需为 register/reset/rebind".into()));
    }
    let email = req.email.trim().to_lowercase();
    if !valid_email(&email) {
        return Err(AppErr(StatusCode::BAD_REQUEST, "邮箱格式不正确".into()));
    }
    // rebind 需登录
    if purpose == "rebind" {
        let token = bearer(&headers).ok_or_else(|| AppErr(StatusCode::UNAUTHORIZED, "缺少令牌".into()))?;
        let _claims = decode_token(&st.cfg, &token)?;
    }
    // register：目标邮箱已被占用则直接拒绝
    if purpose == "register" {
        let dup: Option<(u64,)> = sqlx::query_as("SELECT id FROM users WHERE email=? LIMIT 1")
            .bind(&email)
            .fetch_optional(&st.pool)
            .await?;
        if dup.is_some() {
            return Err(AppErr(StatusCode::BAD_REQUEST, "该邮箱已被注册".into()));
        }
    }
    let key = rate_key(&email, &purpose);
    if !rate_allowed(&key) {
        return Err(AppErr(StatusCode::TOO_MANY_REQUESTS, "发送过于频繁，请 60 秒后再试".into()));
    }
    let code = gen_code();
    insert_code(&st.pool, &email, &purpose, &code).await?;
    deliver_code(&st.cfg, &email, &code, &purpose).await?;
    let mut data = serde_json::json!({"email": email, "purpose": purpose});
    if st.cfg.mail_mode != "smtp" {
        data["dev_code"] = code.into(); // 仅 stub 联调模式回显
    }
    Ok(Json(serde_json::json!({"ok": true, "data": data, "msg": "验证码已发送"})))
}

async fn register(State(st): State<AppState>, Json(req): Json<RegisterReq>) -> Result<Response, AppErr> {
    let app = req.app.trim().to_lowercase();
    if !st.cfg.apps.contains(&app) {
        return Err(AppErr(StatusCode::BAD_REQUEST, format!("未知应用: {app}")));
    }
    let username = normalize_username(&req.username);
    if username.len() < 3 || username.len() > 32 {
        return Err(AppErr(StatusCode::BAD_REQUEST, "用户名需 3-32 位字母/数字/_-.".into()));
    }
    validate_password(&req.password)?;
    let email = req.email.as_deref().map(str::trim).unwrap_or("").to_lowercase();
    if !valid_email(&email) {
        return Err(AppErr(StatusCode::BAD_REQUEST, "注册需绑定有效邮箱".into()));
    }
    let code = req.code.as_deref().unwrap_or("").trim();
    if code.is_empty() || !consume_code(&st.pool, &email, "register", code).await? {
        return Err(AppErr(StatusCode::BAD_REQUEST, "邮箱验证码错误或已过期".into()));
    }
    let hash = hash_password(&req.password)?;
    let (cnt,): (i64,) = sqlx::query_as("SELECT COUNT(*) FROM users")
        .fetch_one(&st.pool).await?;
    let roles = if cnt == 0 { "player,admin,builder".to_string() } else { "player".to_string() };
    let display = req.display_name.clone().unwrap_or_else(|| username.clone());
    let res = sqlx::query(
        "INSERT INTO users (username, pass_hash, email, display_name, roles, email_verified) VALUES (?,?,?,?,?,1)",
    )
    .bind(&username)
    .bind(&hash)
    .bind(&email)
    .bind(&display)
    .bind(&roles)
    .execute(&st.pool)
    .await;
    let uid = match res {
        Ok(r) => r.last_insert_id(),
        Err(e) => {
            let es = format!("{e}");
            let msg = if es.contains("Duplicate") || es.contains("1062") {
                if es.to_lowercase().contains("email") {
                    "该邮箱已被注册"
                } else {
                    "用户名已存在"
                }
            } else {
                return Err(e.into());
            };
            return Err(AppErr(StatusCode::CONFLICT, msg.into()));
        }
    };
    let user = UserOut {
        id: uid,
        username,
        display_name: Some(display),
        email: Some(email),
        email_verified: true,
        roles: roles.split(',').map(str::to_string).collect(),
    };
    let token = issue_token(&st.cfg, &user)?;
    Ok(Json(LoginOut {
        ok: true,
        access_token: token,
        token_type: "Bearer",
        expires_in: st.cfg.token_ttl_secs,
        user,
    })
    .into_response())
}

async fn login(State(st): State<AppState>, Json(req): Json<LoginReq>) -> Result<Response, AppErr> {
    let app = req.app.trim().to_lowercase();
    if !st.cfg.apps.contains(&app) {
        return Err(AppErr(StatusCode::BAD_REQUEST, format!("未知应用: {app}")));
    }
    let username = normalize_username(&req.username);
    let row: Option<(u64, String, String, Option<String>, Option<String>, String, i8)> = sqlx::query_as(
        "SELECT id, username, pass_hash, email, display_name, roles, email_verified FROM users WHERE username=? AND status=1",
    )
    .bind(&username)
    .fetch_optional(&st.pool)
    .await?;
    let (id, uname, phash, email, dname, roles, everified) = match row {
        Some(r) => r,
        None => return Err(AppErr(StatusCode::UNAUTHORIZED, "账号或密码错误".into())),
    };
    if !verify_password(&req.password, &phash) {
        return Err(AppErr(StatusCode::UNAUTHORIZED, "账号或密码错误".into()));
    }
    let roles: Vec<String> = roles.split(',').map(str::to_string).collect();
    let user = UserOut {
        id,
        username: uname,
        display_name: dname,
        email,
        email_verified: everified != 0,
        roles,
    };
    let token = issue_token(&st.cfg, &user)?;
    Ok(Json(LoginOut {
        ok: true,
        access_token: token,
        token_type: "Bearer",
        expires_in: st.cfg.token_ttl_secs,
        user,
    })
    .into_response())
}

async fn me(State(st): State<AppState>, headers: HeaderMap) -> Result<Json<serde_json::Value>, AppErr> {
    let token = bearer(&headers).ok_or_else(|| AppErr(StatusCode::UNAUTHORIZED, "缺少令牌".into()))?;
    let claims = decode_token(&st.cfg, &token)?;
    let row: Option<(Option<String>, i8, Option<String>, String)> = sqlx::query_as(
        "SELECT email, email_verified, display_name, roles FROM users WHERE id=? AND username=? AND status=1",
    )
    .bind(claims.uid)
    .bind(&claims.sub)
    .fetch_optional(&st.pool)
    .await?;
    let (email, everified, dname, roles) = match row {
        Some(r) => r,
        None => return Err(AppErr(StatusCode::UNAUTHORIZED, "账号不存在或已停用".into())),
    };
    let role_list: Vec<String> = roles.split(',').filter(|s| !s.is_empty()).map(str::to_string).collect();
    Ok(Json(serde_json::json!({
        "ok": true,
        "data": {
            "username": claims.sub,
            "uid": claims.uid,
            "display_name": dname,
            "email": email,
            "email_verified": everified != 0,
            "roles": role_list,
            "app": "geekhonize",
            "exp": claims.exp,
        }
    })))
}

async fn logout() -> Json<ApiOk<()>> {
    // 无状态 JWT：客户端自行删除；此处仅作契约占位
    Json(ApiOk { ok: true, data: None, msg: Some("已退出（请客户端丢弃令牌）".into()) })
}

#[derive(Deserialize)]
struct ChangePwReq {
    old_password: String,
    new_password: String,
}

/// 修改密码（需 Bearer 令牌）：验证旧密码 → 写新哈希。改密后旧令牌仍有效至过期（无状态 JWT 约定）。
async fn change_password(
    State(st): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<ChangePwReq>,
) -> Result<Json<ApiOk<()>>, AppErr> {
    let token = bearer(&headers).ok_or_else(|| AppErr(StatusCode::UNAUTHORIZED, "缺少令牌".into()))?;
    let claims = decode_token(&st.cfg, &token)?;
    validate_password(&req.new_password)?;
    let row: Option<(String,)> = sqlx::query_as(
        "SELECT pass_hash FROM users WHERE id=? AND username=? AND status=1",
    )
    .bind(claims.uid)
    .bind(&claims.sub)
    .fetch_optional(&st.pool)
    .await?;
    let (old_hash,) = match row {
        Some(r) => r,
        None => return Err(AppErr(StatusCode::UNAUTHORIZED, "账号不存在或已停用".into())),
    };
    if !verify_password(&req.old_password, &old_hash) {
        return Err(AppErr(StatusCode::BAD_REQUEST, "当前密码不正确".into()));
    }
    let new_hash = hash_password(&req.new_password)?;
    sqlx::query("UPDATE users SET pass_hash=? WHERE id=?")
        .bind(&new_hash)
        .bind(claims.uid)
        .execute(&st.pool)
        .await?;
    Ok(Json(ApiOk { ok: true, data: None, msg: Some("密码已更新，请重新登录".into()) }))
}

/// 忘记密码：邮箱验证码重置（不需要旧密码）。
async fn reset_password(
    State(st): State<AppState>,
    Json(req): Json<ResetPwReq>,
) -> Result<Json<ApiOk<()>>, AppErr> {
    let email = req.email.trim().to_lowercase();
    if !valid_email(&email) {
        return Err(AppErr(StatusCode::BAD_REQUEST, "邮箱格式不正确".into()));
    }
    validate_password(&req.new_password)?;
    if !consume_code(&st.pool, &email, "reset", &req.code).await? {
        return Err(AppErr(StatusCode::BAD_REQUEST, "验证码错误或已过期".into()));
    }
    let new_hash = hash_password(&req.new_password)?;
    let r = sqlx::query("UPDATE users SET pass_hash=? WHERE email=? AND status=1")
        .bind(&new_hash)
        .bind(&email)
        .execute(&st.pool)
        .await?;
    if r.rows_affected() == 0 {
        return Err(AppErr(StatusCode::NOT_FOUND, "未找到绑定该邮箱的账号".into()));
    }
    Ok(Json(ApiOk { ok: true, data: None, msg: Some("密码已重置，请使用新密码登录".into()) }))
}

/// 换绑邮箱（需 Bearer + 新邮箱验证码）。
async fn rebind_email(
    State(st): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<RebindEmailReq>,
) -> Result<Json<ApiOk<()>>, AppErr> {
    let token = bearer(&headers).ok_or_else(|| AppErr(StatusCode::UNAUTHORIZED, "缺少令牌".into()))?;
    let claims = decode_token(&st.cfg, &token)?;
    let new_email = req.new_email.trim().to_lowercase();
    if !valid_email(&new_email) {
        return Err(AppErr(StatusCode::BAD_REQUEST, "邮箱格式不正确".into()));
    }
    if !consume_code(&st.pool, &new_email, "rebind", &req.code).await? {
        return Err(AppErr(StatusCode::BAD_REQUEST, "验证码错误或已过期".into()));
    }
    let dup: Option<(u64,)> = sqlx::query_as("SELECT id FROM users WHERE email=? AND id<>? LIMIT 1")
        .bind(&new_email)
        .bind(claims.uid)
        .fetch_optional(&st.pool)
        .await?;
    if dup.is_some() {
        return Err(AppErr(StatusCode::BAD_REQUEST, "该邮箱已被其它账号使用".into()));
    }
    sqlx::query("UPDATE users SET email=?, email_verified=1 WHERE id=?")
        .bind(&new_email)
        .bind(claims.uid)
        .execute(&st.pool)
        .await?;
    Ok(Json(ApiOk { ok: true, data: None, msg: Some("邮箱已更新".into()) }))
}

// ---------------- 资料与管理员 ----------------

#[derive(Deserialize)]
struct UpdateProfileReq {
    #[serde(default)]
    display_name: Option<String>,
}

/// 更新个人资料（昵称）。
async fn update_profile(
    State(st): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<UpdateProfileReq>,
) -> Result<Json<ApiOk<()>>, AppErr> {
    let token = bearer(&headers).ok_or_else(|| AppErr(StatusCode::UNAUTHORIZED, "缺少令牌".into()))?;
    let claims = decode_token(&st.cfg, &token)?;
    let dn = req.display_name.map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    let dn = match dn {
        Some(s) if s.chars().count() <= 64 => s,
        _ => return Err(AppErr(StatusCode::BAD_REQUEST, "昵称需 1-64 字符".into())),
    };
    sqlx::query("UPDATE users SET display_name=? WHERE id=?")
        .bind(&dn)
        .bind(claims.uid)
        .execute(&st.pool)
        .await?;
    Ok(Json(ApiOk { ok: true, data: None, msg: Some("昵称已更新".into()) }))
}

#[derive(Deserialize)]
struct UsersQuery {
    #[serde(default)]
    q: Option<String>,
}

async fn require_admin(
    st: &AppState,
    headers: &HeaderMap,
) -> Result<Claims, AppErr> {
    let token = bearer(headers).ok_or_else(|| AppErr(StatusCode::UNAUTHORIZED, "缺少令牌".into()))?;
    let claims = decode_token(&st.cfg, &token)?;
    if !claims.roles.iter().any(|r| r == "admin") {
        return Err(AppErr(StatusCode::FORBIDDEN, "需要管理员权限".into()));
    }
    Ok(claims)
}

/// 管理员：用户列表（支持用户名/邮箱模糊搜索）。
async fn admin_users(
    State(st): State<AppState>,
    headers: HeaderMap,
    Query(q): Query<UsersQuery>,
) -> Result<Json<serde_json::Value>, AppErr> {
    let _actor = require_admin(&st, &headers).await?;
    let qf = q.q.unwrap_or_default().trim().to_lowercase();
    let rows: Vec<(u64, String, Option<String>, Option<String>, String, i8, String)> = if qf.is_empty() {
        sqlx::query_as(
            "SELECT id, username, email, display_name, roles, status, DATE_FORMAT(created_at,'%Y-%m-%d %H:%i') FROM users ORDER BY id DESC LIMIT 200",
        )
        .fetch_all(&st.pool)
        .await?
    } else {
        let like = format!("%{qf}%");
        sqlx::query_as(
            "SELECT id, username, email, display_name, roles, status, DATE_FORMAT(created_at,'%Y-%m-%d %H:%i') FROM users WHERE username LIKE ? OR email LIKE ? ORDER BY id DESC LIMIT 200",
        )
        .bind(&like)
        .bind(&like)
        .fetch_all(&st.pool)
        .await?
    };
    let users: Vec<serde_json::Value> = rows
        .into_iter()
        .map(|(id, username, email, dname, roles, status, created)| {
            let role_list: Vec<String> = roles.split(',').filter(|s| !s.is_empty()).map(str::to_string).collect();
            serde_json::json!({
                "id": id, "username": username, "email": email,
                "display_name": dname, "roles": role_list, "status": status, "created_at": created
            })
        })
        .collect();
    Ok(Json(serde_json::json!({"ok": true, "data": {"users": users}})))
}

#[derive(Deserialize)]
struct AdminUserReq {
    id: u64,
    #[serde(default)]
    roles: Option<Vec<String>>,
    #[serde(default)]
    status: Option<i8>,
}

/// 管理员：改角色 / 封禁解封（禁操作自己；角色白名单校验）。
async fn admin_user(
    State(st): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<AdminUserReq>,
) -> Result<Json<ApiOk<()>>, AppErr> {
    let actor = require_admin(&st, &headers).await?;
    if req.id == actor.uid {
        return Err(AppErr(StatusCode::BAD_REQUEST, "不能操作自己的账号".into()));
    }
    if let Some(roles) = &req.roles {
        let allowed = ["player", "admin", "builder"];
        if roles.is_empty() || roles.iter().any(|r| !allowed.contains(&r.as_str())) {
            return Err(AppErr(StatusCode::BAD_REQUEST, "角色只能包含 player/admin/builder".into()));
        }
        let csv = roles.join(",");
        sqlx::query("UPDATE users SET roles=? WHERE id=?")
            .bind(&csv)
            .bind(req.id)
            .execute(&st.pool)
            .await?;
    }
    if let Some(status) = req.status {
        if status != 0 && status != 1 {
            return Err(AppErr(StatusCode::BAD_REQUEST, "status 只能为 0/1".into()));
        }
        sqlx::query("UPDATE users SET status=? WHERE id=?")
            .bind(status)
            .bind(req.id)
            .execute(&st.pool)
            .await?;
    }
    Ok(Json(ApiOk { ok: true, data: None, msg: Some("已更新".into()) }))
}

fn bearer(headers: &HeaderMap) -> Option<String> {
    headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|h| h.strip_prefix("Bearer "))
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

// ---------------- 启动 ----------------

#[tokio::main]
async fn main() {
    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new("geekhonize_auth=info,tower_http=info,axum=info"));
    tracing_subscriber::fmt().with_env_filter(filter).init();

    let cfg = cfg_from_env();
    if cfg.jwt_secret.len() < 32 {
        error!("GEO_JWT_SECRET 未设置或过短（需 >=32 字符随机串）");
        std::process::exit(1);
    }
    let pool = MySqlPoolOptions::new()
        .max_connections(10)
        .connect(&cfg.db_url)
        .await
        .unwrap_or_else(|e| {
            error!("数据库连接失败: {e}（GEO_DATABASE_URL）");
            std::process::exit(1);
        });
    ensure_tables(&pool).await.unwrap_or_else(|e| {
        error!("建表失败: {e}");
        std::process::exit(1);
    });
    // 白名单应用登记
    for app in &cfg.apps {
        let _ = sqlx::query("INSERT IGNORE INTO apps (app_key, display_name) VALUES (?, ?)")
            .bind(app)
            .bind(app)
            .execute(&pool)
            .await;
    }

    let state = AppState { cfg, pool };
    let cors = CorsLayer::very_permissive();
    let app = Router::new()
        .route("/", get(root))
        .route("/healthz", get(health))
        .route("/api/v1/auth/send_code", axum::routing::post(send_code))
        .route("/api/v1/auth/register", axum::routing::post(register))
        .route("/api/v1/auth/login", axum::routing::post(login))
        .route("/api/v1/auth/me", get(me))
        .route("/api/v1/auth/logout", axum::routing::post(logout))
        .route("/api/v1/auth/change_password", axum::routing::post(change_password))
        .route("/api/v1/auth/reset_password", axum::routing::post(reset_password))
        .route("/api/v1/auth/rebind_email", axum::routing::post(rebind_email))
        .route("/api/v1/auth/update_profile", axum::routing::post(update_profile))
        .route("/api/v1/auth/admin/users", get(admin_users))
        .route("/api/v1/auth/admin/user", axum::routing::post(admin_user))
        .layer(cors)
        .with_state(state);

    let port: u16 = env_or("GEO_PORT", "8787").parse().unwrap_or(8787);
    let bind = env_or("GEO_BIND", "127.0.0.1");
    let addr: SocketAddr = format!("{bind}:{port}").parse().unwrap_or_else(|_| {
        error!("GEO_BIND/GEO_PORT 解析失败");
        std::process::exit(1);
    });
    info!("geekhonize-auth listening on {addr}");
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    axum::serve(listener, app).await.expect("serve");
}

// ---------------- 单元测试（无 DB） ----------------

#[cfg(test)]
mod tests {
    use super::*;

    fn test_cfg() -> Cfg {
        Cfg {
            db_url: String::new(),
            jwt_secret: "unit-test-secret-0123456789abcdef".into(),
            apps: ["breakfront".into()].into_iter().collect(),
            token_ttl_secs: 3600,
            mail_mode: "stub".into(),
            smtp_host: "127.0.0.1".into(),
            smtp_port: 25,
            smtp_user: String::new(),
            smtp_pass: String::new(),
            smtp_from: "no-reply@geekhonize.top".into(),
        }
    }

    #[test]
    fn username_normalize_and_password_hash_verify() {
        assert_eq!(normalize_username("  GEEK.Alpha-01 "), "geek.alpha-01");
        let h = hash_password("secret123").unwrap();
        assert!(verify_password("secret123", &h));
        assert!(!verify_password("wrong", &h));
        assert!(validate_password("12345").is_err());
        assert!(validate_password("123456").is_ok());
    }

    #[test]
    fn email_validate_and_code_gen() {
        assert!(valid_email("a@b.com"));
        assert!(valid_email("user+tag@example.co"));
        assert!(!valid_email("plain"));
        assert!(!valid_email("@x.com"));
        assert!(!valid_email("a@b"));
        assert!(!valid_email("a@b."));
        for _ in 0..50 {
            let c = gen_code();
            assert_eq!(c.len(), 6);
            assert!(c.chars().all(|ch| ch.is_ascii_digit()));
        }
    }

    #[test]
    fn purpose_label_and_b64() {
        assert_eq!(purpose_label("reset"), "找回密码");
        assert_eq!(purpose_label("register"), "注册账号");
        assert_eq!(base64_std(b"hello"), "aGVsbG8=");
        assert_eq!(base64_std(b"u"), "dQ==");
    }

    #[test]
    fn jwt_roundtrip_and_expiry() {
        let cfg = test_cfg();
        let u = UserOut {
            id: 7,
            username: "alice".into(),
            display_name: Some("Alice".into()),
            email: Some("alice@example.com".into()),
            email_verified: true,
            roles: vec!["player".into(), "admin".into()],
        };
        let tok = issue_token(&cfg, &u).unwrap();
        let claims = decode_token(&cfg, &tok).unwrap();
        assert_eq!(claims.sub, "alice");
        assert_eq!(claims.uid, 7);
        assert!(claims.roles.contains(&"admin".to_string()));
        assert!(claims.exp > now());
        // 错误密钥不可解
        let bad = Cfg { jwt_secret: "different-secret-AAAAAAAAAAAAAAAA".into(), ..cfg };
        assert!(decode_token(&bad, &tok).is_err());
    }

    #[test]
    fn app_allowlist() {
        let cfg = test_cfg();
        assert!(cfg.apps.contains("breakfront"));
        assert!(!cfg.apps.contains("unknown"));
    }
}
