package com.breakfront.auth;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 认证服务（认证系统 M1，纯 Java 可单测）。
 *
 * <p>职责：注册/登录/登出/令牌校验 + 失败限速。令牌为 32B SecureRandom → 64 hex，
 * 会话有效期默认 1800s；登出或过期即失效。不依赖网络层——进服握手/登录包（M2）与
 * Web 管理台登录复用同一服务。
 */
public final class AuthService {

    public record LoginResult(boolean ok, String token, String message) {
        public static LoginResult fail(String msg) {
            return new LoginResult(false, "", msg);
        }

        public static LoginResult ok(String token) {
            return new LoginResult(true, token, "ok");
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountStore store;
    private final long sessionTtlMillis;
    private final int maxAttempts;
    private final long lockMillis;

    private final Map<String, Session> sessions = new HashMap<>();      // token -> session
    private final Map<String, FailState> fails = new HashMap<>();       // account -> failures

    private record Session(String account, long expiresAt) {
    }

    private record FailState(int count, long lockUntil) {
    }

    public AuthService(AccountStore store) {
        this(store, 1_800_000L, 5, 30_000L);
    }

    public AuthService(AccountStore store, long sessionTtlMillis,
                       int maxAttempts, long lockMillis) {
        this.store = store;
        this.sessionTtlMillis = sessionTtlMillis;
        this.maxAttempts = maxAttempts;
        this.lockMillis = lockMillis;
    }

    /** 注册（会话层包装：账号名直接小写化）。 */
    public boolean register(String name, String rawPassword, Role role) {
        return store.register(name, rawPassword, role);
    }

    /** 登录：失败限速（连续失败 maxAttempts 次锁定 lockMillis）。 */
    public synchronized LoginResult login(String name, String rawPassword) {
        String n = normalize(name);
        if (n == null) {
            return LoginResult.fail("账号名不合法");
        }
        long now = System.currentTimeMillis();
        FailState fs = fails.get(n);
        if (fs != null && fs.lockUntil() > now) {
            return LoginResult.fail("尝试过于频繁，请稍后再试");
        }
        Optional<AccountStore.Account> a = store.authenticate(n, rawPassword);
        if (a.isEmpty()) {
            int count = (fs == null ? 0 : fs.count()) + 1;
            long lockUntil = count >= maxAttempts ? now + lockMillis : 0;
            fails.put(n, new FailState(count, lockUntil));
            return LoginResult.fail(count >= maxAttempts
                    ? "尝试次数过多，已临时锁定" : "账号或密码错误");
        }
        fails.remove(n);
        String token = newToken();
        sessions.put(token, new Session(n, now + sessionTtlMillis));
        return LoginResult.ok(token);
    }

    public synchronized void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 校验令牌：有效返回账号名。 */
    public synchronized Optional<String> accountOf(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Session s = sessions.get(token);
        if (s == null) {
            return Optional.empty();
        }
        if (s.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(s.account());
    }

    /** 令牌对应账号是否具备给定角色（管理员会话据此放行管理台/命令）。 */
    public synchronized boolean hasRole(String token, Role required) {
        return accountOf(token).flatMap(store::roleOf)
                .map(role -> role.atLeast(required)).orElse(false);
    }

    public synchronized void purgeExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    private static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte v : b) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    private static String normalize(String name) {
        return name == null ? null : name.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
