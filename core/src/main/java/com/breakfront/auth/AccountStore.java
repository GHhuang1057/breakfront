package com.breakfront.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 账号存储（认证系统 M1，纯 Java 可单测）。
 *
 * <p>持久化于 runDir（如 {@code runDir/breakfront/accounts.txt}）：
 * 每行一个账号，Tab 分隔：
 * {@code name<TAB>role<TAB>createdAtMillis<TAB>passwordStored}
 * 其中 {@code passwordStored} = {@code iterations$saltHex$hashHex}（见 {@link PasswordHasher}）。
 * 账号名仅允许 {@code [A-Za-z0-9_]}（长度 ≤20），杜绝分隔符注入。
 *
 * <p>全部操作 synchronized（服务器线程 + 后台线程并存时安全）；写盘为整体覆盖式原子写
 * （先写临时文件再 move）。内存表与磁盘在每次变更后同步保存。
 */
public final class AccountStore {

    /** 账号记录（不可变，对外只读）。 */
    public record Account(String name, Role role, long createdAtMillis, String passwordStored) {
    }

    private final Path file;
    private final Map<String, Account> accounts = new LinkedHashMap<>();

    public AccountStore(Path file) {
        this.file = file;
        load();
    }

    // ---------------- 账号生命周期 ----------------

    /** 注册（失败返回 false：名字非法/已存在）。 */
    public synchronized boolean register(String name, String rawPassword, Role role) {
        String n = normalize(name);
        if (n == null || rawPassword == null || rawPassword.length() < 4
                || rawPassword.length() > 64 || accounts.containsKey(n)) {
            return false;
        }
        accounts.put(n, new Account(n, role, System.currentTimeMillis(),
                PasswordHasher.hash(rawPassword)));
        save();
        return true;
    }

    /** 校验口令：成功返回账号，失败返回 empty。 */
    public synchronized Optional<Account> authenticate(String name, String rawPassword) {
        Account a = accounts.get(normalize(name));
        if (a == null || !PasswordHasher.verify(rawPassword, a.passwordStored())) {
            return Optional.empty();
        }
        return Optional.of(a);
    }

    public synchronized boolean remove(String name) {
        String n = normalize(name);
        if (n == null || accounts.remove(n) == null) {
            return false;
        }
        save();
        return true;
    }

    public synchronized Optional<Role> roleOf(String name) {
        Account a = accounts.get(normalize(name));
        return a == null ? Optional.empty() : Optional.of(a.role());
    }

    public synchronized boolean exists(String name) {
        return accounts.containsKey(normalize(name));
    }

    public synchronized List<String> names() {
        return List.copyOf(accounts.keySet());
    }

    public synchronized int size() {
        return accounts.size();
    }

    /** 账号名规范化（小写 + 合法性校验；非法返回 null）。 */
    private static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.length() > 20) {
            return null;
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return null;
            }
        }
        return n;
    }

    // ---------------- 持久化 ----------------

    private void load() {
        accounts.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                String[] f = s.split("\t", -1);
                if (f.length != 4) {
                    continue;
                }
                String n = f[0];
                try {
                    accounts.put(n, new Account(n, Role.valueOf(f[1].toUpperCase(Locale.ROOT)),
                            Long.parseLong(f[2]), f[3]));
                } catch (IllegalArgumentException ignored) {
                    // 跳过损坏行
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot load account store " + file, e);
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent() == null
                    ? Path.of(".") : file.getParent());
            StringBuilder sb = new StringBuilder("# BREAKFRONT account store v1\n");
            for (Account a : accounts.values()) {
                sb.append(a.name()).append('\t').append(a.role().name()).append('\t')
                        .append(a.createdAtMillis()).append('\t')
                        .append(a.passwordStored()).append('\n');
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("cannot save account store " + file, e);
        }
    }

    /** 仅内存操作（测试用，不落盘）。 */
    public static AccountStore inMemory() {
        return new AccountStore(null);
    }
}
