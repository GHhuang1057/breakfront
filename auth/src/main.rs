//! Geekhonize Auth —— 通用账号认证服务（跨作品统一账号）。
//!
//! 用途：为 BREAKFRONT(Minecraft)/Web 管理台/未来其它作品 提供一套「Geekhonize 账号」：
//! 注册 / 登录（JWT）/ 身份查询 / 角色。离线 MC 客户端持账号令牌进服，服务端经本服务校验。
//!
//! 运行：读取环境变量（见 .env.example），依赖 MySQL/MariaDB。
//!   GEO_DATABASE_URL  mysql://user:pass@host:3306/geekhonize
//!   GEO_JWT_SECRET     >=32 字符随机串
//!   GEO_APPS           允许的应用白名单，逗号分隔（默认 breakfront,geekhonize-portal,sxsm）
//!   GEO_PORT           监听端口（默认 8787，生产由 openresty 反代）

use argon2::password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use argon2::Argon2;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use chrono::Utc;
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use sqlx::mysql::MySqlPoolOptions;
use sqlx::MySqlPool;
use std::collections::HashSet;
use std::net::SocketAddr;
use std::time::{SystemTime, UNIX_EPOCH};
use tower_http::cors::CorsLayer;
use tracing::{error, info};
use tracing_subscriber::EnvFilter;

// ---------------- 配置 ----------------

#[derive(Clone)]
struct Cfg {
    db_url: String,
    jwt_secret: String,
    apps: HashSet<String>,
    token_ttl_secs: i64,
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
    Cfg {
        db_url: env_or("GEO_DATABASE_URL", "mysql://geekhonize:geekhonize@127.0.0.1:3306/geekhonize"),
        jwt_secret: env_or("GEO_JWT_SECRET", ""),
        apps,
        token_ttl_secs: env_or("GEO_TOKEN_TTL", "604800").parse().unwrap_or(604800),
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
    roles: Vec<String>,
}

#[derive(Deserialize)]
struct RegisterReq {
    username: String,
    password: String,
    #[serde(default)]
    email: Option<String>,
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

// ---------------- 错误 ----------------

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
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    )
    .execute(pool)
    .await?;
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

async fn register(State(st): State<AppState>, Json(req): Json<RegisterReq>) -> Result<Response, AppErr> {
    let app = req.app.trim().to_lowercase();
    if !st.cfg.apps.contains(&app) {
        return Err(AppErr(StatusCode::BAD_REQUEST, format!("未知应用: {app}")));
    }
    validate_password(&req.password)?;
    let username = normalize_username(&req.username);
    if username.len() < 3 || username.len() > 32 {
        return Err(AppErr(StatusCode::BAD_REQUEST, "用户名需 3-32 位字母/数字/_-.".into()));
    }
    let hash = hash_password(&req.password)?;
    // 全库首位注册用户 = 管理员（引导期 bootstrap；生产可关闭）
    let (cnt,): (i64,) = sqlx::query_as("SELECT COUNT(*) FROM users")
        .fetch_one(&st.pool).await?;
    let roles = if cnt == 0 { "player,admin,builder".to_string() } else { "player".to_string() };
    let display = req.display_name.clone().unwrap_or_else(|| username.clone());
    let res = sqlx::query(
        "INSERT INTO users (username, pass_hash, email, display_name, roles) VALUES (?,?,?,?,?)",
    )
    .bind(&username)
    .bind(&hash)
    .bind(req.email.as_deref())
    .bind(&display)
    .bind(&roles)
    .execute(&st.pool)
    .await;
    if let Err(e) = res {
        let msg = if format!("{e}").contains("Duplicate") || format!("{e}").contains("1062") {
            "用户名已存在"
        } else {
            return Err(e.into());
        };
        return Err(AppErr(StatusCode::CONFLICT, msg.into()));
    }
    let user = UserOut {
        id: cnt as u64 + 1,
        username,
        display_name: Some(display),
        email: req.email,
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
    let row: Option<(u64, String, String, Option<String>, Option<String>, String)> = sqlx::query_as(
        "SELECT id, username, pass_hash, email, display_name, roles FROM users WHERE username=? AND status=1",
    )
    .bind(&username)
    .fetch_optional(&st.pool)
    .await?;
    let (id, uname, phash, email, dname, roles) = match row {
        Some(r) => r,
        None => return Err(AppErr(StatusCode::UNAUTHORIZED, "账号或密码错误".into())),
    };
    if !verify_password(&req.password, &phash) {
        return Err(AppErr(StatusCode::UNAUTHORIZED, "账号或密码错误".into()));
    }
    let roles: Vec<String> = roles.split(',').map(str::to_string).collect();
    let user = UserOut { id, username: uname, display_name: dname, email, roles };
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
    Ok(Json(serde_json::json!({
        "ok": true,
        "data": {
            "username": claims.sub,
            "uid": claims.uid,
            "roles": claims.roles,
            "app": "geekhonize",
            "exp": claims.exp,
        }
    })))
}

async fn logout() -> Json<ApiOk<()>> {
    // 无状态 JWT：客户端自行删除；此处仅作契约占位
    Json(ApiOk { ok: true, data: None, msg: Some("已退出（请客户端丢弃令牌）".into()) })
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
        .route("/healthz", get(health))
        .route("/api/v1/auth/register", axum::routing::post(register))
        .route("/api/v1/auth/login", axum::routing::post(login))
        .route("/api/v1/auth/me", get(me))
        .route("/api/v1/auth/logout", axum::routing::post(logout))
        .layer(cors)
        .with_state(state);

    let port: u16 = env_or("GEO_PORT", "8787").parse().unwrap_or(8787);
    let addr = SocketAddr::from(([127, 0, 0, 1], port));
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
    fn jwt_roundtrip_and_expiry() {
        let cfg = test_cfg();
        let u = UserOut {
            id: 7,
            username: "alice".into(),
            display_name: Some("Alice".into()),
            email: None,
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
