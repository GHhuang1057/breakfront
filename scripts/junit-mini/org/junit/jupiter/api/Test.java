package org.junit.jupiter.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JUnit 5 {@code @Test} 的最小本地替身（仅供 scripts/run_core_tests.sh 使用）。
 *
 * <p>存在意义：本项目本地无 Gradle，而完整构建在 GitHub Actions 上要跑数分钟 ——
 * 一个断言的错误也要等一轮 CI 才知道。core 里有相当一部分测试是纯 Java（无 Minecraft
 * 依赖），本替身让它们能在本地 javac + java 秒级跑通。
 *
 * <p><b>不要放进 src/test/java</b>：会与真实 JUnit 依赖冲突。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {
}
