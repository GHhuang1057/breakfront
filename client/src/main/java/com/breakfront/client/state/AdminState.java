package com.breakfront.client.state;

/**
 * 管理员会话客户端状态（M8）：
 * 由 S2C AdminLoginResultPayload 驱动；门禁屏读 lastMessage 显示失败原因，
 * 成功时置 justGranted 供客户端 tick 把门禁屏切换为管理面板。
 * 仅在渲染主线程访问。
 */
public final class AdminState {

    private static volatile boolean admin;
    private static volatile boolean justGranted;
    private static volatile String lastMessage = "";

    private AdminState() {
    }

    public static void applyResult(boolean ok, String message) {
        lastMessage = message == null ? "" : message;
        admin = ok;
        if (ok) {
            justGranted = true;
        }
    }

    public static boolean isAdmin() {
        return admin;
    }

    public static boolean justGranted() {
        return justGranted;
    }

    public static void clearJustGranted() {
        justGranted = false;
    }

    public static String lastMessage() {
        return lastMessage;
    }

    /** 本地手动注销（不通知服务端；会话到期服务端同样失效）。 */
    public static void logoutLocal() {
        admin = false;
        justGranted = false;
    }
}
