import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 极简 JUnit 5 运行器（仅供 scripts/run_core_tests.sh 使用）。
 *
 * <p>用反射扫描给定类中标注了 {@code @Test} 的方法并执行，统计通过/失败。
 * 支持 package-private 测试类与私有构造（按需 setAccessible）。
 *
 * <p>用法：{@code java JUnitMiniRunner com.a.B com.c.D}
 */
public final class JUnitMiniRunner {

    public static void main(String[] args) throws Exception {
        int pass = 0;
        int fail = 0;
        for (String className : args) {
            Class<?> clazz = Class.forName(className);
            Object instance = null;
            int cp = 0;
            int cf = 0;
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getAnnotation(Test.class) == null) {
                    continue;
                }
                if (instance == null) {
                    Constructor<?> ctor = clazz.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    instance = ctor.newInstance();
                }
                m.setAccessible(true);
                try {
                    m.invoke(instance);
                    cp++;
                } catch (InvocationTargetException e) {
                    cf++;
                    Throwable cause = e.getCause();
                    System.out.println("  [FAIL] " + clazz.getSimpleName() + "." + m.getName()
                            + ": " + (cause == null ? e : cause));
                } catch (Exception e) {
                    cf++;
                    System.out.println("  [ERROR] " + clazz.getSimpleName() + "." + m.getName()
                            + ": " + e);
                }
            }
            System.out.println("  " + clazz.getSimpleName() + ": " + (cp + cf)
                    + " tests, " + cf + " failed");
            pass += cp;
            fail += cf;
        }
        System.out.println("---- JUnitMini: " + pass + " passed, " + fail + " failed ----");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private JUnitMiniRunner() {
    }
}
