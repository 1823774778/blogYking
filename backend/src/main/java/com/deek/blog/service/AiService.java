package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容接口的 AI 设置探测与摘要生成。Base URL 例如：
 * https://api.openai.com/v1、https://api.deepseek.com/v1。
 */
@Service
public class AiService {

    private final SiteConfigService siteConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AiService(SiteConfigService siteConfigService, ObjectMapper objectMapper) {
        this.siteConfigService = siteConfigService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> status() {
        Map<String, String> config = siteConfigService.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", Boolean.parseBoolean(config.getOrDefault("aiEnabled", "false")));
        data.put("provider", config.getOrDefault("aiProvider", "OpenAI 兼容"));
        data.put("baseUrl", config.getOrDefault("aiBaseUrl", ""));
        data.put("model", config.getOrDefault("aiModel", ""));
        data.put("apiKeySet", !config.getOrDefault("aiApiKey", "").isBlank());
        return data;
    }

    public Map<String, Object> test() {
        Map<String, String> config = requireConfig();
        String baseUrl = normalizeBase(config.get("aiBaseUrl"));
        String apiKey = config.getOrDefault("aiApiKey", "").trim();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/models"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET();
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("AI 连接失败：" + friendlyHttpError(response.statusCode(), response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            int modelCount = root.path("data").isArray() ? root.path("data").size() : 0;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", modelCount > 0
                    ? "连接成功，检测到 " + modelCount + " 个可用模型"
                    : "连接成功");
            result.put("modelCount", modelCount);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("AI 连接失败：" + e.getMessage());
        }
    }

    public String summarize(String title, String content) {
        Map<String, String> config = requireConfig();
        String model = config.getOrDefault("aiModel", "").trim();
        if (model.isBlank()) {
            throw new BizException("请先填写 AI 模型名称");
        }
        String baseUrl = normalizeBase(config.get("aiBaseUrl"));
        String apiKey = config.getOrDefault("aiApiKey", "").trim();
        String source = (content == null ? "" : content);
        if (source.length() > 8000) {
            source = source.substring(0, 8000);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.3);
        body.put("max_tokens", 240);
        body.put("messages", List.of(
                Map.of("role", "system", "content",
                        "你是中文博客编辑。根据标题和正文写一段 60-120 字的中文摘要，只输出摘要正文，不要加引号或前后缀。"),
                Map.of("role", "user", "content",
                        "标题：" + (title == null ? "" : title) + "\n\n正文：\n" + source)
        ));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)));
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("AI 生成失败：" + friendlyHttpError(response.statusCode(), response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("choices").path(0).path("message").path("content").asText("").trim();
            if (text.isBlank()) {
                throw new BizException("AI 没有返回摘要内容");
            }
            if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 2) {
                text = text.substring(1, text.length() - 1).trim();
            }
            return text.length() > 200 ? text.substring(0, 200) : text;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("AI 生成失败：" + e.getMessage());
        }
    }

    /** 从 OpenAI 兼容的 /models 接口读取可用模型。 */
    public Map<String, Object> models() {
        Map<String, String> config = requireConfig();
        String baseUrl = normalizeBase(config.get("aiBaseUrl"));
        JsonNode root = getJson(baseUrl + "/models", config.getOrDefault("aiApiKey", "").trim(), 25);
        List<Map<String, String>> models = new java.util.ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                String id = item.path("id").asText("").trim();
                if (!id.isBlank()) {
                    models.add(Map.of("id", id, "ownedBy", item.path("owned_by").asText("")));
                }
            }
        }
        models.sort(java.util.Comparator.comparing(m -> m.get("id")));
        return Map.of("models", models, "count", models.size());
    }

    /** 使用 AI 生成一篇可继续编辑的文章草稿。 */
    public Map<String, Object> generateArticle(String topic, String outline, String tone, String length) {
        String t = topic == null ? "" : topic.trim();
        if (t.isBlank()) {
            throw new BizException("请先填写文章主题");
        }
        String lengthHint = switch (length == null ? "" : length) {
            case "short" -> "600-900 字";
            case "long" -> "2000-3000 字";
            default -> "1200-1800 字";
        };
        String toneHint = switch (tone == null ? "" : tone) {
            case "casual" -> "轻松、口语化";
            case "professional" -> "专业、克制";
            case "story" -> "有叙事感、像随笔";
            default -> "自然、清晰";
        };
        String prompt = """
                主题：%s
                风格：%s
                篇幅：%s
                提纲或补充要求：%s

                请输出严格 JSON，不要 Markdown 代码围栏，不要额外解释。字段：
                {
                  "title": "文章标题",
                  "summary": "60-120 字摘要",
                  "tags": ["标签1", "标签2"],
                  "content": "完整 Markdown 正文，包含小标题、段落，必要时使用代码块"
                }
                """.formatted(t, toneHint, lengthHint, outline == null ? "无" : outline.trim());
        String raw = chat(List.of(
                Map.of("role", "system", "content", "你是中文博客作者，擅长写结构清晰、可读性强的技术或生活文章。"),
                Map.of("role", "user", "content", prompt)
        ), 3000, 0.65);
        JsonNode json = parseJsonObject(raw);
        String title = json.path("title").asText(t).trim();
        String summary = json.path("summary").asText("").trim();
        String content = json.path("content").asText("").trim();
        String tags = "";
        if (json.path("tags").isArray()) {
            List<String> tagList = new java.util.ArrayList<>();
            json.path("tags").forEach(n -> {
                String v = n.asText("").trim();
                if (!v.isBlank()) tagList.add(v);
            });
            tags = String.join(",", tagList);
        } else {
            tags = json.path("tags").asText("").trim();
        }
        if (content.isBlank()) {
            content = raw.trim();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title.isBlank() ? t : title);
        result.put("summary", summary);
        result.put("tags", tags);
        result.put("content", content);
        return result;
    }

    /** 审核文章：返回评分、结论、问题与修改建议。 */
    public Map<String, Object> reviewArticle(String title, String content) {
        String source = content == null ? "" : content;
        if (source.length() > 12000) source = source.substring(0, 12000);
        String prompt = """
                请审核下面这篇中文博客文章，重点检查：标题与正文是否一致、结构是否清晰、事实/代码风险、
                表达是否啰嗦、是否有敏感或不适合公开发布的内容。输出严格 JSON，不要代码围栏：
                {
                  "score": 0-100 的整数,
                  "verdict": "通过 / 建议修改 / 不建议发布",
                  "issues": ["问题1", "问题2"],
                  "suggestions": ["建议1", "建议2"],
                  "conclusion": "一句话结论"
                }
                标题：%s

                正文：
                %s
                """.formatted(title == null ? "" : title, source);
        String raw = chat(List.of(
                Map.of("role", "system", "content", "你是严格但友善的中文博客编辑，审核意见要具体、可执行。"),
                Map.of("role", "user", "content", prompt)
        ), 900, 0.2);
        JsonNode json = parseJsonObject(raw);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", json.path("score").asInt(0));
        result.put("verdict", json.path("verdict").asText("建议修改"));
        result.put("issues", json.path("issues").isArray() ? objectMapper.convertValue(json.path("issues"), List.class) : List.of());
        result.put("suggestions", json.path("suggestions").isArray() ? objectMapper.convertValue(json.path("suggestions"), List.class) : List.of());
        result.put("conclusion", json.path("conclusion").asText(""));
        if (result.get("issues") == null || ((List<?>) result.get("issues")).isEmpty()) {
            result.put("issues", List.of(raw.length() > 300 ? raw.substring(0, 300) : raw));
        }
        return result;
    }

    /** 审核评论：给出通过、人工复核或拒绝建议。 */
    public Map<String, Object> reviewComment(String postTitle, String content) {
        String text = content == null ? "" : content.trim();
        if (text.isBlank()) throw new BizException("评论内容为空");
        String prompt = """
                请审核下面这条博客评论，判断是否适合公开展示。输出严格 JSON，不要代码围栏：
                {
                  "decision": "APPROVE | REVIEW | REJECT",
                  "risk": "低 | 中 | 高",
                  "reason": "判断理由，80 字以内",
                  "suggestion": "给管理员的一句话处理建议"
                }
                所属文章：%s
                评论：%s
                """.formatted(postTitle == null ? "" : postTitle, text);
        String raw = chat(List.of(
                Map.of("role", "system", "content", "你是中文博客评论审核员，只处理广告、攻击、隐私泄露、违法和明显离题内容，不因观点不同而拒绝。"),
                Map.of("role", "user", "content", prompt)
        ), 400, 0.1);
        JsonNode json = parseJsonObject(raw);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decision", json.path("decision").asText("REVIEW"));
        result.put("risk", json.path("risk").asText("中"));
        result.put("reason", json.path("reason").asText(""));
        result.put("suggestion", json.path("suggestion").asText(""));
        return result;
    }

    /** 汇总一篇文章下的评论。 */
    public String summarizeComments(String postTitle, List<String> comments) {
        if (comments == null || comments.isEmpty()) {
            throw new BizException("这篇文章还没有可总结的评论");
        }
        String joined = String.join("\n---\n", comments);
        if (joined.length() > 10000) joined = joined.substring(0, 10000);
        String prompt = """
                请总结下面这篇文章评论区的讨论。用 100-200 字中文概括：读者主要关注点、认可或有分歧的地方、
                有价值的补充信息。只输出总结正文，不要标题、引号或前后缀。
                文章：%s
                评论：
                %s
                """.formatted(postTitle == null ? "" : postTitle, joined);
        String raw = chat(List.of(
                Map.of("role", "system", "content", "你是中文社区编辑，总结要克制、客观、不臆造评论中没有的信息。"),
                Map.of("role", "user", "content", prompt)
        ), 500, 0.25);
        return raw.trim();
    }

    private JsonNode getJson(String url, String apiKey, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .GET();
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("AI 请求失败：" + friendlyHttpError(response.statusCode(), response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("AI 请求失败：" + e.getMessage());
        }
    }

    private String chat(List<Map<String, String>> messages, int maxTokens, double temperature) {
        Map<String, String> config = requireConfig();
        String model = config.getOrDefault("aiModel", "").trim();
        if (model.isBlank()) throw new BizException("请先填写 AI 模型名称");
        String baseUrl = normalizeBase(config.get("aiBaseUrl"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("messages", messages);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)));
        String apiKey = config.getOrDefault("aiApiKey", "").trim();
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("AI 生成失败：" + friendlyHttpError(response.statusCode(), response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("choices").path(0).path("message").path("content").asText("").trim();
            if (text.isBlank()) throw new BizException("AI 没有返回内容");
            return text;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("AI 生成失败：" + e.getMessage());
        }
    }

    private JsonNode parseJsonObject(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\s*", "").replaceFirst("\s*```$", "");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new BizException("AI 返回内容不是有效 JSON");
        }
    }

    private Map<String, String> requireConfig() {
        Map<String, String> config = siteConfigService.get();
        if (!Boolean.parseBoolean(config.getOrDefault("aiEnabled", "false"))) {
            throw new BizException("AI 功能未启用，请先在站点设置中开启");
        }
        if (config.getOrDefault("aiBaseUrl", "").isBlank()) {
            throw new BizException("请先填写 AI Base URL");
        }
        return config;
    }

    private String normalizeBase(String raw) {
        String base = raw == null ? "" : raw.trim();
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String friendlyHttpError(int status, String body) {
        String hint = "";
        try {
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
            hint = root.path("error").path("message").asText("");
        } catch (Exception ignored) {
        }
        if (hint.isBlank()) {
            hint = body == null ? "" : body;
        }
        if (hint.length() > 180) {
            hint = hint.substring(0, 180);
        }
        return "HTTP " + status + (hint.isBlank() ? "" : " · " + hint);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("AI 请求序列化失败");
        }
    }
}
