package com.breakfront.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountAuthTest {

    @TempDir
    Path tmp;

    @Test
    void passwordHashRoundTrip() {
        String stored = PasswordHasher.hash("s3cret-Pass");
        assertTrue(PasswordHasher.verify("s3cret-Pass", stored));
        assertFalse(PasswordHasher.verify("wrong", stored));
        assertFalse(PasswordHasher.verify("s3cret-Pass", "garbage"));
    }

    @Test
    void storeRegisterAuthAndPersist() {
        Path file = tmp.resolve("accounts.txt");
        AccountStore store = new AccountStore(file);
        assertTrue(store.register("Admin", "hunter22", Role.ADMIN));
        assertFalse(store.register("admin", "hunter22", Role.PLAYER)); // 大小写归一 → 重复
        assertFalse(store.register("bad name!", "hunter22", Role.PLAYER)); // 非法字符
        assertFalse(store.register("short", "123", Role.PLAYER)); // 密码太短

        assertEquals(Optional.of(Role.ADMIN), store.roleOf("ADMIN"));
        assertTrue(store.authenticate("admin", "hunter22").isPresent());
        assertTrue(store.authenticate("admin", "nope").isEmpty());

        // 重新打开（持久化生效）
        AccountStore reloaded = new AccountStore(file);
        assertTrue(reloaded.exists("admin"));
        assertTrue(reloaded.authenticate("admin", "hunter22").isPresent());
        assertEquals(Role.ADMIN, reloaded.roleOf("admin").orElseThrow());

        assertTrue(reloaded.remove("admin"));
        assertFalse(reloaded.exists("admin"));
    }

    @Test
    void inMemoryStoreWorks() {
        AccountStore m = AccountStore.inMemory();
        assertTrue(m.register("tester", "pw1234", Role.PLAYER));
        assertEquals(1, m.size());
        assertTrue(m.authenticate("tester", "pw1234").isPresent());
    }

    @Test
    void authServiceLoginLogoutAndRole() {
        AuthService svc = new AuthService(AccountStore.inMemory());
        assertTrue(svc.register("alice", "pw1234", Role.ADMIN));
        assertTrue(svc.register("bob", "pw1234", Role.PLAYER));
        AuthService.LoginResult r = svc.login("alice", "pw1234");
        assertTrue(r.ok());
        String token = r.token();
        assertEquals(Optional.of("alice"), svc.accountOf(token));
        // atLeast：账号角色 >= 所需角色 即放行
        assertTrue(svc.hasRole(token, Role.ADMIN));
        assertTrue(svc.hasRole(token, Role.BUILDER)); // ADMIN >= BUILDER
        assertTrue(svc.hasRole(token, Role.PLAYER));
        // PLAYER 账号不满足 BUILDER/ADMIN
        AuthService.LoginResult br = svc.login("bob", "pw1234");
        assertTrue(br.ok());
        assertTrue(svc.hasRole(br.token(), Role.PLAYER));
        assertFalse(svc.hasRole(br.token(), Role.BUILDER));
        assertFalse(svc.hasRole(br.token(), Role.ADMIN));
        svc.logout(token);
        assertTrue(svc.accountOf(token).isEmpty());
    }

    @Test
    void loginRateLimitLocksAfterFailures() {
        // 最多 2 次失败即锁定 5 秒
        AuthService svc = new AuthService(AccountStore.inMemory(), 60_000L, 2, 5_000L);
        assertTrue(svc.register("mallory", "pw1234", Role.PLAYER));
        assertFalse(svc.login("mallory", "bad1").ok());
        assertFalse(svc.login("mallory", "bad2").ok());
        AuthService.LoginResult third = svc.login("mallory", "pw1234");
        assertFalse(third.ok());
        assertTrue(third.message().contains("锁定") || third.message().contains("频繁"));
    }

    @Test
    void expiredTokenInvalidates() {
        AuthService svc = new AuthService(AccountStore.inMemory(), -1L, 5, 5_000L);
        assertTrue(svc.register("bob", "pw1234", Role.PLAYER));
        AuthService.LoginResult r = svc.login("bob", "pw1234");
        assertTrue(r.ok());
        assertTrue(svc.accountOf(r.token()).isEmpty()); // ttl=-1 → 立即可过期
    }
}
