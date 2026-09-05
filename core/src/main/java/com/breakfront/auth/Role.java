package com.breakfront.auth;

/**
 * 账号角色（认证系统 M1）。
 *
 * <p>权限语义（与 Web 管理台/服务端命令的 allows 逻辑衔接，见 docs/bf-authn-2026-09-05.md）：
 * <ul>
 *   <li>PLAYER — 普通玩家；</li>
 *   <li>VIP — 高优先级/外观权益；</li>
 *   <li>BUILDER — 地图编辑（对应 /bfs 编辑器）；</li>
 *   <li>ADMIN — 全量管理（对应管理台）。</li>
 * </ul>
 */
public enum Role {

    PLAYER(false),
    VIP(false),
    BUILDER(true),
    ADMIN(true);

    private final boolean staff;

    Role(boolean staff) {
        this.staff = staff;
    }

    public boolean isStaff() {
        return staff;
    }

    public boolean atLeast(Role other) {
        return this.ordinal() >= other.ordinal();
    }
}
