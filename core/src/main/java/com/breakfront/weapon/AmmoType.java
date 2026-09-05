package com.breakfront.weapon;

/**
 * 弹药口径（骨架）。
 *
 * <p>label 为人类可读口径名；与 TaCZ 枪包的 {@code AmmoId} 映射关系
 * 收敛在 {@link WeaponCatalog}（保持本层零外部依赖）。
 */
public enum AmmoType {

    CAL_556X45("556x45"),
    CAL_762X39("762x39"),
    CAL_792X57("792x57"),
    CAL_3006("3006"),
    CAL_50BMG("50bmg"),
    CAL_12GAUGE("12g"),
    CAL_9MM("9mm"),
    CAL_45ACP("45acp");

    private final String label;

    AmmoType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
