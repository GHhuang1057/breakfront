package com.tacz.guns.api.event.common;

/**
 * 编译期桩 —— TaCZ: Refabricated 的 EntityKillByGunEvent 最小签名。
 *
 * ⚠ 本类仅用于让 breakfront 主代码在编译期对上 TaCZ 事件的调用签名，
 * 打包时被排除（见 core/build.gradle 中 Jar 任务的 exclude('com/tacz/**')），
 * 运行时由真实 TaCZ 模组 jar 提供同名同签名类型。
 * 签名若与真实版本漂移会导致运行期 NoSuchMethodError，届时按源码校准即可。
 */
@SuppressWarnings("unused")
public class EntityKillByGunEvent {

    public interface Callback {
        void post(EntityKillByGunEvent event);
    }

    public static net.fabricmc.fabric.api.event.Event<Callback> CALLBACK;

    public net.minecraft.entity.LivingEntity getKilledEntity() {
        return null;
    }

    public net.minecraft.entity.LivingEntity getAttacker() {
        return null;
    }

    public net.minecraft.util.Identifier getGunDisplayId() {
        return null;
    }

    public boolean isHeadShot() {
        return false;
    }
}
