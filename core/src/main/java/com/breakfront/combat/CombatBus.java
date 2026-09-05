package com.breakfront.combat;

import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * 战斗事件总线（#47，对齐 656 ServerCombatEngine 思想）。
 *
 * <p>线程模型：发布与订阅默认在服务器主线程（MinecraftServer tick / 事件回调），
 * 故不做锁；如未来有异步源需自行加锁或换并发队列。
 */
public final class CombatBus {

    /** 消费端（HUD 击杀流/计分/音效/成就等）。 */
    public interface Listener {
        void onEvent(CombatEvent e);
    }

    private static final java.util.List<Listener> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private CombatBus() {
    }

    public static void subscribe(Listener l) {
        if (l != null) {
            LISTENERS.add(l);
        }
    }

    public static void unsubscribe(Listener l) {
        LISTENERS.remove(l);
    }

    /** 发布事件（同步逐监听器分发；单个监听器异常不影响其余与调用方）。 */
    public static void publish(CombatEvent e) {
        if (e == null) {
            return;
        }
        for (Listener l : LISTENERS) {
            try {
                l.onEvent(e);
            } catch (Throwable t) {
                // 消费端故障不应中断总线
                t.printStackTrace();
            }
        }
    }

    public static int listenerCount() {
        return LISTENERS.size();
    }

    public static void clear() {
        LISTENERS.clear();
    }
}
