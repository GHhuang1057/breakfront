package com.breakfront.weapon;

/**
 * BREAKFRONT 武器族类（骨架）。
 *
 * <p>对应 BF 语境的主武器分类；未来每族定义专属属性（衰减曲线/腰射精度/换弹时长
 * 等），此处先定分类基调与展示名。
 */
public enum WeaponClass {

    AR("突击步枪"),
    SMG("冲锋枪"),
    LMG("轻机枪"),
    DMR("精确射手步枪"),
    SR("狙击步枪"),
    SG("霰弹枪"),
    PISTOL("手枪"),
    LAUNCHER("榴弹/火箭发射器");

    private final String label;

    WeaponClass(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
