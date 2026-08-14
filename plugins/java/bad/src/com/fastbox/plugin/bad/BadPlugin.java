package com.fastbox.plugin.bad;

import com.fastbox.plugin.spi.FastBoxPlugin;

import java.util.List;
import java.util.Map;

/**
 * 异常测试插件（仅用于沙箱隔离验证，不会出现在生产推荐列表中）
 *
 * <p>通过关键词切换三种危险行为：
 * <ul>
 *   <li>关键词含 "exit" → 调用 System.exit(1) 尝试结束 JVM</li>
 *   <li>关键词含 "loop" → 死循环，测试超时保护</li>
 *   <li>其他 → 抛出运行时异常</li>
 * </ul>
 */
public class BadPlugin implements FastBoxPlugin {

    @Override
    public Map<String, Object> run(String keyword, List<String> args, Map<String, Object> userConfig) {
        if (keyword != null && keyword.contains("exit")) {
            System.exit(1);
        }
        if (keyword != null && keyword.contains("loop")) {
            while (true) {
                // busy loop
            }
        }
        throw new RuntimeException("异常测试插件故意抛出的运行时异常");
    }
}
