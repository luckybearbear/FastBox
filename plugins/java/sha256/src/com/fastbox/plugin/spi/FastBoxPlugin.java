package com.fastbox.plugin.spi;

import java.util.List;
import java.util.Map;

/**
 * FastBox Java 插件 SPI
 *
 * <p>插件 jar 内需自带本接口（拷贝此文件），由网关独立 ClassLoader 反射加载调用。
 * 返回统一 JSON 契约：
 * <pre>
 *   { "code": 0,
 *     "data": { "detail": { "title": "...", "content": "..." } },  // 可选，面板详情
 *     "message": "ok" }
 * </pre>
 * code=0 表示成功；非 0 时 message 作为失败提示展示。
 */
public interface FastBoxPlugin {

    /**
     * 插件执行入口
     *
     * @param keyword    触发关键词（用于模式判定，如"解码"）
     * @param args       用户输入参数（按 args_schema 顺序）
     * @param userConfig 用户级配置（预留）
     * @return 统一 JSON 契约 Map
     */
    Map<String, Object> run(String keyword, List<String> args, Map<String, Object> userConfig);
}
