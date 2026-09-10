#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""任务 #13：Geekhonize 登录 —— 进游戏即提示 + 令牌登录（幂等重放）。

需求（用户原话）：
  「Geekhonize 登录要在进入游戏后（不是加入服务器）就提示登录；点击按钮可以跳转到
   浏览器登录，也可以选择 token 登录；需要在账户管理界面（网页）生成登录 token。」

现状盘点（改前）：
  - 登录屏 BfGeoLoginScreen 已有：账号密码登录 / 邮箱验证码注册 / **设备码浏览器登录**
    （跳 auth.geekhonize.top/#device?code=xxx 并轮询）。→ 「跳转浏览器登录」已具备。
  - 但**没有令牌登录**入口；也没有「进游戏即提示」（只有 /geo ui 手动开）。
  - 服务端 AuthBridge 走 /api/v1/auth/me 校验，任意合法 JWT 均可，不区分签发来源。

本脚本：
  A. 客户端 GeoHttp 增加 me(token)：粘贴令牌后校验并取回 username（与浏览器登录等价）。
  B. BfGeoLoginScreen 增加「令牌登录」模式（与浏览器登录并排在同一行，不撑破面板布局）。
  C. BreakfrontClient 在主菜单出现时弹一次登录屏（= 「进入游戏后提示」，不是进服后）。

网页侧（生成令牌）在 G:\\sxsm-auth-cf：新增 POST /api/v1/auth/game_token + 账号页
「游戏令牌」标签页，与该工程一起部署，不在本脚本内。

用法：
    python scripts/_patch_login.py --check
    python scripts/_patch_login.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GH = ROOT / "client/src/main/java/com/breakfront/client/geo/GeoHttp.java"
LS = ROOT / "client/src/main/java/com/breakfront/client/ui/BfGeoLoginScreen.java"
BC = ROOT / "client/src/main/java/com/breakfront/client/BreakfrontClient.java"

EDITS: list[tuple[Path, str, str, str]] = []


def E(f: Path, tag: str, old: str, new: str):
    EDITS.append((f, tag, old, new))


# ==========================================================================
# A. GeoHttp.me(token)
# ==========================================================================
E(
    GH,
    "gh-me",
    """    // ---------- 设备码登录（PCL/FCL 等第三方启动器：游戏内跳浏览器授权） ----------""",
    """    /**
     * 校验一个已有令牌（{@code GET /api/v1/auth/me}）——「令牌登录」用。
     *
     * <p>玩家在账号中心（auth.geekhonize.top → 我的账号 → 游戏令牌）生成长期令牌，
     * 复制到游戏内粘贴即可完成绑定，无需输账号密码、也不用邮箱验证码往返。
     * 服务端 {@code AuthBridge.me} 校验的是同一个端点，故两边口径一致。
     */
    public static Res me(String token) {
        String tk = token == null ? "" : token.trim();
        if (tk.isEmpty()) {
            return new Res(false, "", "", "令牌为空");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + "/api/v1/auth/me"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + tk)
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                String msg = quoted(resp.body(), "msg");
                return new Res(false, "", "",
                        msg == null ? "令牌校验失败（HTTP " + resp.statusCode() + "）" : msg);
            }
            String user = quoted(resp.body(), "username");
            if (user == null || user.isEmpty()) {
                return new Res(false, "", "", "令牌响应缺少用户名");
            }
            return new Res(true, tk, user, "ok");
        } catch (Exception e) {
            return new Res(false, "", "", "网络错误：" + e.getClass().getSimpleName());
        }
    }

    // ---------- 设备码登录（PCL/FCL 等第三方启动器：游戏内跳浏览器授权） ----------""",
)

# ==========================================================================
# B. BfGeoLoginScreen —— 令牌登录模式
# ==========================================================================
E(
    LS,
    "ls-fields-mode",
    """    private boolean registerMode = false;
    private boolean busy = false;""",
    """    private boolean registerMode = false;
    /** 令牌登录模式：粘贴「账号中心 → 游戏令牌」生成的长期令牌直接完成绑定。 */
    private boolean tokenMode = false;
    /** 令牌输入缓冲（tokenMode 下由 focus==0 编辑）。 */
    private String tokenBuf = "";
    private boolean busy = false;""",
)

E(
    LS,
    "ls-fields-rects",
    """    private int deviceX, deviceY, deviceW, deviceH;""",
    """    private int deviceX, deviceY, deviceW, deviceH;
    private int tokenX, tokenY, tokenW, tokenH;""",
)

E(
    LS,
    "ls-layout-rows",
    """        rows = (si || !registerMode) ? 2 : 4;""",
    """        rows = (si || !registerMode) ? (tokenMode ? 1 : 2) : 4;""",
)

E(
    LS,
    "ls-layout-aux",
    """        // 浏览器登录（仅登录模式显示）
        deviceW = Math.min(320, panelW - 60);
        deviceH = 24;
        deviceX = panelX + (panelW - deviceW) / 2;
        deviceY = toggleY + toggleH + 10;""",
    """        // 登录辅助行（仅登录模式显示）：左=浏览器登录，右=令牌登录。
        // 两个按钮**并排共用原有的一行高度** —— 本面板的纵向布局是「以底边为基准往上
        // 推」的写法，再加一行会把面板撑破，故只能横向分摊宽度。
        int auxW = Math.min(320, panelW - 60);
        int auxGap = 8;
        deviceW = (auxW - auxGap) / 2;
        deviceH = 24;
        deviceX = panelX + (panelW - auxW) / 2;
        deviceY = toggleY + toggleH + 10;
        tokenW = deviceW;
        tokenH = deviceH;
        tokenX = deviceX + deviceW + auxGap;
        tokenY = deviceY;""",
)

E(
    LS,
    "ls-header",
    """                Text.literal(registerMode ? "邮箱验证码注册（一个账号通行全部作品）"
                        : "登录后进服自动绑定 · 防冒名"),""",
    """                Text.literal(registerMode ? "邮箱验证码注册（一个账号通行全部作品）"
                        : tokenMode ? "粘贴账号中心生成的「游戏令牌」即可完成绑定"
                        : "登录后进服自动绑定 · 防冒名"),""",
)

E(
    LS,
    "ls-boxes",
    """        drawBox(ctx, "用户名", userBuf, boxY[0], focus == 0, mouseX, mouseY, false, 32);
        drawBox(ctx, registerMode ? "密码（6-128 位）" : "密码", passBuf, boxY[1],
                focus == 1, mouseX, mouseY, true, 128);""",
    """        if (tokenMode) {
            drawBox(ctx, "游戏令牌（账号中心 → 游戏令牌 生成）", tokenBuf, boxY[0],
                    focus == 0, mouseX, mouseY, false, 2048);
        } else {
            drawBox(ctx, "用户名", userBuf, boxY[0], focus == 0, mouseX, mouseY, false, 32);
            drawBox(ctx, registerMode ? "密码（6-128 位）" : "密码", passBuf, boxY[1],
                    focus == 1, mouseX, mouseY, true, 128);
        }""",
)

E(
    LS,
    "ls-act",
    """        String act = busy ? "处理中…" : (registerMode ? "注 册" : "登 录");""",
    """        String act = busy ? "处理中…"
                : (registerMode ? "注 册" : (tokenMode ? "令牌登录" : "登 录"));""",
)

E(
    LS,
    "ls-toggle-text",
    """        String tg = registerMode ? "← 已有账号？返回登录" : "没有账号？邮箱验证码注册";""",
    """        String tg = registerMode ? "← 已有账号？返回登录"
                : (tokenMode ? "← 用账号密码登录" : "没有账号？邮箱验证码注册");""",
)

E(
    LS,
    "ls-aux-render",
    """        // 浏览器登录（第三方启动器）
        if (!registerMode) {
            boolean dh = inRect(mouseX, mouseY, deviceX, deviceY, deviceW, deviceH);
            boolean active = devicePolling && !deviceCode.isEmpty();
            if (dh && !devicePolling && !busy) {
                BfGlow.rect(ctx, deviceX - 2, deviceY - 2, deviceW + 4, deviceH + 4,
                        BfTheme.TEAL & 0xFFFFFF, 24, 4);
            }
            BfDraw.parallelogram(ctx, deviceX, deviceY, deviceW, deviceH, 4,
                    devicePolling ? BfTheme.PANEL_LINE : 0xE6161C25);
            BfDraw.border(ctx, deviceX, deviceY, deviceW, deviceH,
                    dh && !devicePolling ? BfTheme.TEAL : BfTheme.PANEL_LINE);
            String dl = devicePolling
                    ? (deviceCode.isEmpty() ? "正在请求设备码…" : "浏览器登录中… 设备码 " + deviceCode)
                    : "或 浏览器登录（PCL / FCL 等第三方启动器）";
            int dlw = this.textRenderer.getWidth(dl);
            ctx.drawText(this.textRenderer, Text.literal(dl),
                    deviceX + deviceW / 2 - dlw / 2,
                    deviceY + deviceH / 2 - this.textRenderer.fontHeight / 2,
                    devicePolling ? BfTheme.TEAL : (dh ? BfTheme.TEXT : BfTheme.TEXT_DIM), false);
        }""",
    """        // 登录辅助行：浏览器登录 / 令牌登录（令牌模式下只剩浏览器登录，占满整行）
        if (!registerMode) {
            int dw = tokenMode ? deviceW * 2 + 8 : deviceW;
            boolean dh = inRect(mouseX, mouseY, deviceX, deviceY, dw, deviceH);
            if (dh && !devicePolling && !busy) {
                BfGlow.rect(ctx, deviceX - 2, deviceY - 2, dw + 4, deviceH + 4,
                        BfTheme.TEAL & 0xFFFFFF, 24, 4);
            }
            BfDraw.parallelogram(ctx, deviceX, deviceY, dw, deviceH, 4,
                    devicePolling ? BfTheme.PANEL_LINE : 0xE6161C25);
            BfDraw.border(ctx, deviceX, deviceY, dw, deviceH,
                    dh && !devicePolling ? BfTheme.TEAL : BfTheme.PANEL_LINE);
            String dl = devicePolling
                    ? (deviceCode.isEmpty() ? "正在请求设备码…" : "浏览器登录中… 设备码 " + deviceCode)
                    : (tokenMode ? "或 浏览器登录（PCL / FCL 等第三方启动器）" : "浏览器登录");
            int dlw = this.textRenderer.getWidth(dl);
            ctx.drawText(this.textRenderer, Text.literal(dl),
                    deviceX + dw / 2 - dlw / 2,
                    deviceY + deviceH / 2 - this.textRenderer.fontHeight / 2,
                    devicePolling ? BfTheme.TEAL : (dh ? BfTheme.TEXT : BfTheme.TEXT_DIM), false);

            if (!tokenMode) {
                boolean th = inRect(mouseX, mouseY, tokenX, tokenY, tokenW, tokenH);
                if (th && !busy) {
                    BfGlow.rect(ctx, tokenX - 2, tokenY - 2, tokenW + 4, tokenH + 4,
                            BfTheme.AMBER & 0xFFFFFF, 24, 4);
                }
                BfDraw.parallelogram(ctx, tokenX, tokenY, tokenW, tokenH, 4, 0xE6161C25);
                BfDraw.border(ctx, tokenX, tokenY, tokenW, tokenH,
                        th ? BfTheme.AMBER : BfTheme.PANEL_LINE);
                String tl = "令牌登录";
                int tlw = this.textRenderer.getWidth(tl);
                ctx.drawText(this.textRenderer, Text.literal(tl),
                        tokenX + tokenW / 2 - tlw / 2,
                        tokenY + tokenH / 2 - this.textRenderer.fontHeight / 2,
                        th ? BfTheme.TEXT : BfTheme.TEXT_DIM, false);
            }
        }""",
)

E(
    LS,
    "ls-click",
    """        if (inRect(mx, my, toggleX, toggleY, toggleW, toggleH)) {
            registerMode = !registerMode;
            msgText = "";
            focus = 0;
            return true;
        }
        if (!registerMode && inRect(mx, my, deviceX, deviceY, deviceW, deviceH)) {
            doDeviceLogin();
            return true;
        }""",
    """        if (inRect(mx, my, toggleX, toggleY, toggleW, toggleH)) {
            msgText = "";
            focus = 0;
            if (tokenMode) {
                tokenMode = false;              // 令牌 → 账号密码
            } else {
                registerMode = !registerMode;    // 登录 ↔ 注册
            }
            return true;
        }
        if (!registerMode && inRect(mx, my, deviceX, deviceY,
                tokenMode ? deviceW * 2 + 8 : deviceW, deviceH)) {
            doDeviceLogin();
            return true;
        }
        // 令牌登录入口：切到令牌模式（面板只留一行令牌输入）
        if (!registerMode && !tokenMode
                && inRect(mx, my, tokenX, tokenY, tokenW, tokenH)) {
            tokenMode = true;
            focus = 0;
            msgOk = true;
            msgText = "在账号中心「我的账号 → 游戏令牌」生成后复制到这里";
            return true;
        }""",
)

E(
    LS,
    "ls-backspace",
    """        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (focus == 0 && !userBuf.isEmpty()) {""",
    """        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (tokenMode) {
                if (focus == 0 && !tokenBuf.isEmpty()) {
                    tokenBuf = tokenBuf.substring(0, tokenBuf.length() - 1);
                }
                return true;
            }
            if (focus == 0 && !userBuf.isEmpty()) {""",
)

E(
    LS,
    "ls-chartyped",
    """        if (focus == 0 && userBuf.length() < 32) {
            userBuf += chr;
            return true;
        }""",
    """        if (tokenMode) {
            if (focus == 0 && tokenBuf.length() < 2048) {
                tokenBuf += chr;
                return true;
            }
            return false;
        }
        if (focus == 0 && userBuf.length() < 32) {
            userBuf += chr;
            return true;
        }""",
)

E(
    LS,
    "ls-submit",
    """    private void submit() {
        if (busy) {
            return;
        }
        String u = userBuf.trim();""",
    """    /** 令牌登录：校验粘贴的长期令牌，通过后直接落盘为本地会话。 */
    private void submitToken() {
        String tk = tokenBuf.trim();
        if (tk.isEmpty()) {
            msgText = "请粘贴账号中心生成的游戏令牌";
            msgOk = false;
            return;
        }
        busy = true;
        msgText = "";
        java.util.concurrent.CompletableFuture.supplyAsync(() -> GeoHttp.me(tk))
                .thenAccept(r -> this.client.execute(() -> onAuthResult(r, false)));
    }

    private void submit() {
        if (busy) {
            return;
        }
        if (tokenMode) {
            submitToken();
            return;
        }
        String u = userBuf.trim();""",
)

# ==========================================================================
# C. BreakfrontClient —— 「进入游戏后」提示一次
# ==========================================================================
E(
    BC,
    "bc-field",
    """    /** 本进程只跑一次启动预检（BootstrapScreen），完成后进主菜单。 */
    private static volatile boolean bootstrapped = false;""",
    """    /** 本进程只跑一次启动预检（BootstrapScreen），完成后进主菜单。 */
    private static volatile boolean bootstrapped = false;

    /** 本进程只在「进入游戏后」提示一次 Geekhonize 登录（关闭 = 稍后再说，本次不再弹）。 */
    private static volatile boolean loginPrompted = false;""",
)

E(
    BC,
    "bc-tick-prompt",
    """        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.currentScreen instanceof TitleScreen) {""",
    """        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 【进入游戏即提示登录】——是「进入游戏」而不是「加入服务器」：主菜单一出现
            // 就检查本地令牌，没有就把登录屏压上去。这样玩家在进任何 BF 服**之前**登录
            // 就已就绪，不会被服务端的登录门禁拦在门外。每会话只提示一次，关闭即视为
            // 「稍后再说」（仍可用 /geo ui 或主菜单重新打开）。
            if (!loginPrompted && client.currentScreen instanceof BreakfrontMainMenu) {
                loginPrompted = true;
                com.breakfront.client.geo.GeoSession.load();
                if (!com.breakfront.client.geo.GeoSession.signedIn()) {
                    client.setScreen(new com.breakfront.client.ui.BfGeoLoginScreen(client.currentScreen));
                }
            }
            if (client.currentScreen instanceof TitleScreen) {""",
)


def main() -> int:
    if "--check" in sys.argv:
        bad = 0
        for f, tag, old, _ in EDITS:
            n = f.read_text(encoding="utf-8").count(old)
            if n != 1:
                print(f"  [x] {tag}: 命中 {n} 次（应为 1）")
                bad += 1
        print(f"校验完成：{len(EDITS)} 处，失败 {bad} 处（未写盘）")
        return 1 if bad else 0

    cache: dict[Path, str] = {}
    bad = 0
    for f, tag, old, new in EDITS:
        src = cache.get(f) or f.read_text(encoding="utf-8")
        if src.count(old) != 1:
            print(f"  [x] {tag}: 命中 {src.count(old)} 次，跳过")
            bad += 1
            cache[f] = src
            continue
        cache[f] = src.replace(old, new, 1)
    for f, src in cache.items():
        f.write_text(src, encoding="utf-8")
    print(f"已重放 {len(EDITS) - bad}/{len(EDITS)} 处改动")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
