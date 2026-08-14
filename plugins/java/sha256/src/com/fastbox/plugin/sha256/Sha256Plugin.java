package com.fastbox.plugin.sha256;

import com.fastbox.plugin.spi.FastBoxPlugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * SHA-256 摘要工具（示例 Java 插件）
 *
 * <p>功能：对输入文本计算 SHA-256 十六进制摘要。
 * 关键词含「sha」「摘要」时计算；含「校验」且提供「原文=摘要」时比对。
 */
public class Sha256Plugin implements FastBoxPlugin {

    @Override
    public Map<String, Object> run(String keyword, List<String> args, Map<String, Object> userConfig) {
        if (args == null || args.isEmpty() || args.get(0).isBlank()) {
            return Map.of("code", 1, "message", "请输入待摘要文本");
        }
        String input = args.get(0).trim();

        // 校验模式：原文=期望摘要
        int eq = input.indexOf('=');
        if (keyword.contains("校验") && eq > 0) {
            String text = input.substring(0, eq).trim();
            String expected = input.substring(eq + 1).trim();
            String actual = sha256(text);
            boolean match = actual.equalsIgnoreCase(expected);
            return Map.of("code", 0,
                    "data", Map.of("detail", Map.of(
                            "title", "SHA-256 校验",
                            "content", (match ? "✓ 匹配" : "✗ 不匹配") + "\n实际值: " + actual))); 
        }

        String hex = sha256(input);
        return Map.of("code", 0,
                "data", Map.of("detail", Map.of(
                        "title", "SHA-256 摘要",
                        "content", hex)));
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 计算失败: " + e.getMessage());
        }
    }
}
