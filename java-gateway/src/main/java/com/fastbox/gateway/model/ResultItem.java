package com.fastbox.gateway.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 搜索结果条目
 * kind: tool / file / python / sql / plugin / command / favorite
 */
public class ResultItem {
    public final String kind;
    public final String title;
    public final String subtitle;
    public final String action;          // 前端点击后调用的动作类型
    public final Map<String, Object> payload;

    public ResultItem(String kind, String title, String subtitle, String action, Map<String, Object> payload) {
        this.kind = kind;
        this.title = title;
        this.subtitle = subtitle;
        this.action = action;
        this.payload = payload == null ? Map.of() : payload;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("title", title);
        m.put("subtitle", subtitle);
        m.put("action", action);
        m.put("payload", payload);
        return m;
    }

    public static ResultItem of(String kind, String title, String subtitle, String action, Map<String, Object> payload) {
        return new ResultItem(kind, title, subtitle, action, payload);
    }
}
