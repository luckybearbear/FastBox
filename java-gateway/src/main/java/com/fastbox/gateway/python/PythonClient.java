package com.fastbox.gateway.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Python 常驻服务客户端（FastAPI，默认 127.0.0.1:8765）
 *
 * 协议契约：
 *   POST /execute  {"keyword": "...", "args": [...], "userConfig": {...}}
 *   响应          {"code": 0, "data": ..., "message": "ok"}
 */
public class PythonClient {

    private static final Logger log = LoggerFactory.getLogger(PythonClient.class);

    /** 插件执行超时（秒），可通过 FASTBOX_PYTHON_TIMEOUT_SECONDS 覆盖，默认 15s */
    private static final long EXEC_TIMEOUT_SECONDS;

    static {
        long t = 15;
        String env = System.getenv("FASTBOX_PYTHON_TIMEOUT_SECONDS");
        if (env != null && !env.isBlank()) {
            try {
                t = Long.parseLong(env.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        EXEC_TIMEOUT_SECONDS = Math.max(1, t);
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // 强制 HTTP/1.1，避免 h2c 升级被 uvicorn 拒绝
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    public PythonClient() {
        String url = System.getenv("FASTBOX_PYTHON_URL");
        this.baseUrl = url == null || url.isBlank() ? "http://127.0.0.1:8765" : url;
    }

    /** Python 服务是否存活 */
    public boolean isAlive() {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                            .timeout(Duration.ofSeconds(1))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行 Python 脚本
     *
     * @return JsonNode {code, data, message}；服务不可用时 code=-1
     */
    public JsonNode execute(String keyword, Object args, Map<String, Object> userConfig) {
        ObjectNode body = mapper.createObjectNode();
        body.put("keyword", keyword == null ? "" : keyword);
        if (args != null) body.set("args", mapper.valueToTree(args));
        if (userConfig != null) body.set("userConfig", mapper.valueToTree(userConfig));

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/execute"))
                    .timeout(Duration.ofSeconds(EXEC_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(resp.body());
        } catch (java.net.http.HttpTimeoutException e) {
            // 超时留痕：明确的超时信息，便于前端提示与 t_exec_log 记录
            log.warn("Python 插件执行超时（>{}s）: {}", EXEC_TIMEOUT_SECONDS, e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("code", -1);
            err.put("message", "Python 插件执行超时（超过 " + EXEC_TIMEOUT_SECONDS + " 秒）");
            return err;
        } catch (Exception e) {
            log.warn("Python 服务调用失败: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("code", -1);
            err.put("message", "Python 服务不可用: " + e.getMessage());
            return err;
        }
    }
}
