package com.breakfront.game;

/**
 * 编译期桩 —— core 的 Side 枚举（仅用于客户端 HUD 读取 ownerOrdinal）。
 * 绝不打包（见 client/build.gradle Jar exclude）；运行时由 core 模组提供真实类型。
 * 顺序与 core 保持一致：0=ATTACKER 1=DEFENDER。
 */
public enum Side {
    ATTACKER,
    DEFENDER
}
