package com.deek.blog.service.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在 GraalJS 中运行 LX Music 自定义音源脚本。
 *
 * <p>音源脚本只允许通过 {@code lx.request} 发起网络请求；不开放 Java 类、文件系统、
 * 子进程或线程能力。调用方负责给初始化与播放地址解析设置超时。</p>
 */
public final class LxSourceRuntime implements AutoCloseable {

    public record ScriptMeta(String name, String description, String version, String author,
                             String homepage, String rawScript) {
    }

    public record PlatformInfo(String code, String label, List<String> actions, List<String> qualities) {
    }

    private static final Logger log = LoggerFactory.getLogger(LxSourceRuntime.class);
    private static final Pattern META_PATTERN = Pattern.compile("@([^\\s]+)\\s+([^\\r\\n]+)");
    private static final String USER_AGENT = "LX-Music/2.0.0 (compatible; DeekBlog)";
    private static final int MAX_BODY_BYTES = 6 * 1024 * 1024;

    private final Context context;
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final ScriptMeta meta;
    private final Map<String, PlatformInfo> platforms = new LinkedHashMap<>();
    private final String initAlert;
    private Value requestHandler;

    private LxSourceRuntime(Context context, HttpClient client, ObjectMapper objectMapper, ScriptMeta meta,
                            Map<String, PlatformInfo> platforms, String initAlert) {
        this.context = context;
        this.client = client;
        this.objectMapper = objectMapper;
        this.meta = meta;
        this.platforms.putAll(platforms);
        this.initAlert = initAlert;
    }

    public static ScriptMeta parseMeta(String rawScript) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = META_PATTERN.matcher(String.valueOf(rawScript == null ? "" : rawScript));
        while (matcher.find()) {
            values.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2).trim());
        }
        String name = values.getOrDefault("name", "").trim();
        String description = values.getOrDefault("description", "").trim();
        String rawVersion = values.getOrDefault("version", "").trim();
        String version = rawVersion.replaceFirst("^v", "");
        if (name.isBlank() || version.isBlank()) {
            throw new IllegalArgumentException("脚本缺少 @name 或 @version 元数据，不是有效的 LX 音源");
        }
        return new ScriptMeta(name, description, version, values.getOrDefault("author", "").trim(),
                values.getOrDefault("homepage", "").trim(), rawScript);
    }

    public static LxSourceRuntime create(String rawScript, ObjectMapper objectMapper) {
        ScriptMeta meta = parseMeta(rawScript);
        Context context = Context.newBuilder("js")
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        Map<String, PlatformInfo> platforms = new LinkedHashMap<>();
        String[] alert = {""};
        try {
            bindHost(context, objectMapper, client, meta, platforms, alert);
            context.eval("js", rawScript);
        } catch (RuntimeException e) {
            closeQuietly(context);
            throw new IllegalArgumentException("音源初始化失败：" + shortMessage(e), e);
        }
        if (platforms.isEmpty()) {
            closeQuietly(context);
            throw new IllegalArgumentException(alert[0].isBlank()
                    ? "音源没有返回可用平台，请确认脚本支持 LX Music 2.0"
                    : alert[0]);
        }
        LxSourceRuntime runtime = new LxSourceRuntime(context, client, objectMapper, meta, platforms, alert[0]);
        runtime.requestHandler = context.getBindings("js").getMember("__lxRequestHandler");
        if (runtime.requestHandler == null || !runtime.requestHandler.canExecute()) {
            runtime.close();
            throw new IllegalArgumentException("音源没有注册 request 事件");
        }
        context.eval("js", """
                globalThis.__lxResolveResult = null;
                globalThis.__lxResolveMusic = function(platform, id, quality) {
                  globalThis.__lxResolveResult = null;
                  try {
                    const payload = {
                      source: platform,
                      action: 'musicUrl',
                      info: { type: quality, musicInfo: { songmid: String(id) } }
                    };
                    Promise.resolve(globalThis.__lxRequestHandler(payload))
                      .then(value => { globalThis.__lxResolveResult = JSON.stringify({ ok: true, data: value }); })
                      .catch(error => {
                        const message = error && (error.message || error) ? String(error.message || error) : 'failed';
                        globalThis.__lxResolveResult = JSON.stringify({ ok: false, error: message });
                      });
                  } catch (error) {
                    const message = error && (error.message || error) ? String(error.message || error) : 'failed';
                    globalThis.__lxResolveResult = JSON.stringify({ ok: false, error: message });
                  }
                };
                """);
        return runtime;
    }

    private static void bindHost(Context context, ObjectMapper objectMapper, HttpClient client, ScriptMeta meta,
                                 Map<String, PlatformInfo> platforms, String[] alert) {
        context.eval("js", "globalThis.lx = { EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },"
                + " env: 'desktop', version: '2.0.0', currentScriptInfo: {}, utils: { buffer: {}, crypto: {} }, request: null };");
        Value lx = context.getBindings("js").getMember("lx");
        Value scriptInfo = lx.getMember("currentScriptInfo");
        scriptInfo.putMember("name", meta.name());
        scriptInfo.putMember("description", meta.description());
        scriptInfo.putMember("version", meta.version());
        scriptInfo.putMember("author", meta.author());
        scriptInfo.putMember("homepage", meta.homepage());
        scriptInfo.putMember("rawScript", meta.rawScript());

        Value buffer = lx.getMember("utils").getMember("buffer");
        buffer.putMember("from", (ProxyExecutable) args -> bytes(args.length > 0 ? unwrap(args[0]) : null,
                args.length > 1 ? args[1].asString() : "utf-8"));
        buffer.putMember("bufToString", (ProxyExecutable) args -> bufferToString(
                args.length > 0 ? unwrap(args[0]) : null,
                args.length > 1 ? args[1].asString() : "utf-8"));

        Value crypto = lx.getMember("utils").getMember("crypto");
        crypto.putMember("md5", (ProxyExecutable) args -> md5(stringValue(args[0])));
        crypto.putMember("aesEncrypt", (ProxyExecutable) args -> aesEncrypt(args));
        crypto.putMember("randomBytes", (ProxyExecutable) args -> randomBytes(args[0].asInt()));
        crypto.putMember("rsaEncrypt", (ProxyExecutable) args -> {
            throw new UnsupportedOperationException("当前音源宿主暂不支持 RSA 加密");
        });

        lx.putMember("on", (ProxyExecutable) args -> {
            if (args.length >= 2 && "request".equals(args[0].asString()) && args[1].canExecute()) {
                context.getBindings("js").putMember("__lxRequestHandler", args[1]);
            }
            return null;
        });
        lx.putMember("send", (ProxyExecutable) args -> {
            String event = args.length > 0 && args[0].isString() ? args[0].asString() : "";
            Value data = args.length > 1 ? args[1] : null;
            if ("inited".equals(event) && data != null && data.hasMember("sources")) {
                Value sources = data.getMember("sources");
                for (String code : sources.getMemberKeys()) {
                    Value info = sources.getMember(code);
                    String label = info.hasMember("name") ? info.getMember("name").asString() : code;
                    List<String> actions = stringList(info.hasMember("actions") ? info.getMember("actions") : null);
                    List<String> qualities = stringList(info.hasMember("qualitys") ? info.getMember("qualitys") : null);
                    platforms.put(code, new PlatformInfo(code, label, actions, qualities));
                }
            } else if ("updateAlert".equals(event) && data != null && data.hasMember("log")) {
                alert[0] = data.getMember("log").asString();
            }
            return null;
        });

        context.getBindings("js").putMember("setTimeout", (ProxyExecutable) args -> {
            if (args.length > 0 && args[0].canExecute()) args[0].executeVoid();
            return null;
        });
        context.getBindings("js").putMember("clearTimeout", (ProxyExecutable) args -> null);
        context.getBindings("js").putMember("console", ProxyObject.fromMap(Map.of(
                "log", (ProxyExecutable) args -> null,
                "info", (ProxyExecutable) args -> null,
                "warn", (ProxyExecutable) args -> null,
                "error", (ProxyExecutable) args -> null
        )));

        lx.putMember("request", (ProxyExecutable) args -> {
            if (args.length < 3) return null;
            String url = args[0].asString();
            Value options = args[1];
            Value callback = args[2];
            try {
                Map<String, String> headers = new LinkedHashMap<>();
                String method = "GET";
                String body = null;
                if (options.hasMember("method")) method = options.getMember("method").asString().toUpperCase(Locale.ROOT);
                if (options.hasMember("headers")) {
                    Value h = options.getMember("headers");
                    for (String key : h.getMemberKeys()) {
                        Value value = h.getMember(key);
                        if (!value.isNull()) headers.put(key, value.asString());
                    }
                }
                if (options.hasMember("form") && !options.getMember("form").isNull()) {
                    Value form = options.getMember("form");
                    StringBuilder encoded = new StringBuilder();
                    for (String key : form.getMemberKeys()) {
                        if (encoded.length() > 0) encoded.append('&');
                        encoded.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                                .append(URLEncoder.encode(form.getMember(key).asString(), StandardCharsets.UTF_8));
                    }
                    body = encoded.toString();
                    headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
                } else if (options.hasMember("body") && !options.getMember("body").isNull()) {
                    Value value = options.getMember("body");
                    body = value.isString() ? value.asString() : value.toString();
                }
                headers.putIfAbsent("User-Agent", USER_AGENT);
                int timeout = 15;
                if (options.hasMember("timeout") && options.getMember("timeout").isNumber()) {
                    timeout = Math.max(3, Math.min(25, options.getMember("timeout").asInt()));
                }
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri(url))
                        .timeout(Duration.ofSeconds(timeout));
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
                builder.method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
                HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                byte[] raw = response.body();
                if (raw.length > MAX_BODY_BYTES) raw = java.util.Arrays.copyOf(raw, MAX_BODY_BYTES);
                String text = new String(raw, StandardCharsets.UTF_8);
                Object parsed = text;
                String contentType = response.headers().firstValue("content-type").orElse("");
                String trimmed = text.trim();
                if (contentType.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try {
                        Value json = context.getBindings("js").getMember("JSON");
                        parsed = json.invokeMember("parse", text);
                    } catch (RuntimeException ignored) {
                        parsed = text;
                    }
                }
                Map<String, Object> responseMap = new LinkedHashMap<>();
                responseMap.put("statusCode", response.statusCode());
                responseMap.put("body", parsed);
                responseMap.put("headers", response.headers().map());
                callback.execute(null, ProxyObject.fromMap(responseMap));
            } catch (Exception e) {
                callback.execute(e.toString(), null);
            }
            return null;
        });
    }

    public ScriptMeta meta() {
        return meta;
    }

    public Map<String, PlatformInfo> platforms() {
        return new LinkedHashMap<>(platforms);
    }

    public String initAlert() {
        return initAlert;
    }

    public synchronized String resolveMusicUrl(String platform, String songId, String quality) {
        if (!platforms.containsKey(platform)) {
            throw new IllegalArgumentException("当前音源不支持该平台");
        }
        if (songId == null || !songId.matches("[0-9A-Za-z_-]{1,80}")) {
            throw new IllegalArgumentException("歌曲 ID 不合法");
        }
        String q = quality == null || quality.isBlank() ? "320k" : quality;
        context.getBindings("js").getMember("__lxResolveMusic").execute(platform, songId, q);
        context.eval("js", "void 0;");
        String raw = context.getBindings("js").getMember("__lxResolveResult").isNull()
                ? ""
                : context.getBindings("js").getMember("__lxResolveResult").asString();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("音源没有返回播放地址");
        }
        try {
            var root = objectMapper.readTree(raw);
            if (!root.path("ok").asBoolean(false)) {
                String error = root.path("error").asText("音源解析失败");
                throw new IllegalStateException(error);
            }
            String url = root.path("data").asText("");
            if (url.isBlank()) throw new IllegalStateException("音源返回的播放地址为空");
            return url;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("音源返回数据格式不正确", e);
        }
    }

    private static List<String> stringList(Value value) {
        if (value == null || value.isNull()) return List.of();
        List<String> result = new ArrayList<>();
        if (value.hasArrayElements()) {
            for (int i = 0; i < value.getArraySize(); i += 1) {
                Value item = value.getArrayElement(i);
                if (item.isString()) result.add(item.asString());
            }
        }
        return result;
    }

    private static Object unwrap(Value value) {
        if (value == null || value.isNull()) return null;
        if (value.isString()) return value.asString();
        if (value.isHostObject()) return value.asHostObject();
        if (value.hasArrayElements()) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < value.getArraySize(); i += 1) result.add(unwrap(value.getArrayElement(i)));
            return result;
        }
        return value.toString();
    }

    private static String stringValue(Value value) {
        if (value == null || value.isNull()) return "";
        if (value.isString()) return value.asString();
        if (value.isHostObject() && value.asHostObject() instanceof byte[] bytes) return bufferToString(bytes, "utf-8");
        return value.toString();
    }

    private static byte[] bytes(Object value, String encoding) {
        if (value instanceof byte[] data) return data;
        String text = value == null ? "" : String.valueOf(value);
        String enc = encoding == null ? "utf-8" : encoding.toLowerCase(Locale.ROOT);
        if ("base64".equals(enc)) return Base64.getDecoder().decode(text);
        if ("hex".equals(enc)) {
            byte[] data = new byte[text.length() / 2];
            for (int i = 0; i < data.length; i += 1) {
                data[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
            }
            return data;
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String bufferToString(Object value, String encoding) {
        byte[] data = bytes(value, null);
        String enc = encoding == null ? "utf-8" : encoding.toLowerCase(Locale.ROOT);
        if ("base64".equals(enc)) return Base64.getEncoder().encodeToString(data);
        if ("hex".equals(enc)) {
            StringBuilder result = new StringBuilder(data.length * 2);
            for (byte item : data) result.append(String.format("%02x", item));
            return result.toString();
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] data = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(data.length * 2);
            for (byte item : data) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[Math.max(1, Math.min(size, 4096))];
        new SecureRandom().nextBytes(data);
        return data;
    }

    private static byte[] aesEncrypt(Value[] args) {
        try {
            String first = args.length > 1 && args[1].isString() ? args[1].asString() : "";
            String fourth = args.length > 3 && args[3].isString() ? args[3].asString() : "";
            String mode;
            Value keyValue;
            Value ivValue;
            if (first.toLowerCase(Locale.ROOT).startsWith("aes-")) {
                // LX 音源实际调用顺序：aesEncrypt(data, mode, key, iv)
                mode = first;
                keyValue = args.length > 2 ? args[2] : null;
                ivValue = args.length > 3 ? args[3] : null;
            } else {
                // 兼容官方文档常见顺序：aesEncrypt(data, key, iv, mode)
                mode = fourth;
                keyValue = args.length > 1 ? args[1] : null;
                ivValue = args.length > 2 ? args[2] : null;
            }
            byte[] data = bytes(unwrap(args[0]), null);
            byte[] key = bytes(unwrap(keyValue), null);
            byte[] iv = ivValue != null && ivValue.isString() && !ivValue.asString().isBlank()
                    ? bytes(ivValue.asString(), null) : new byte[0];
            String algorithm = mode != null && mode.toLowerCase(Locale.ROOT).contains("ecb")
                    ? "AES/ECB/PKCS5Padding" : "AES/CBC/PKCS5Padding";
            Cipher cipher = Cipher.getInstance(algorithm);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            if (algorithm.contains("ECB")) {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            } else {
                if (iv.length != 16) throw new IllegalArgumentException("AES IV 长度必须为 16 字节");
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
            }
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES 加密失败：" + e.getMessage(), e);
        }
    }

    private static URI uri(String url) throws Exception {
        int queryIndex = url.indexOf('?');
        if (queryIndex < 0) return URI.create(url);
        String base = url.substring(0, queryIndex);
        String query = url.substring(queryIndex + 1);
        StringBuilder builder = new StringBuilder();
        for (char c : query.toCharArray()) {
            switch (c) {
                case ' ' -> builder.append("%20");
                case '{' -> builder.append("%7B");
                case '}' -> builder.append("%7D");
                case '"' -> builder.append("%22");
                case '[' -> builder.append("%5B");
                case ']' -> builder.append("%5D");
                case '|' -> builder.append("%7C");
                default -> builder.append(c);
            }
        }
        return URI.create(base + "?" + builder);
    }

    private static String shortMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private static void closeQuietly(Context context) {
        try {
            context.close(true);
        } catch (Exception ignored) {
            /* 忽略关闭异常 */
        }
    }

    @Override
    public synchronized void close() {
        try {
            context.close(true);
        } catch (Exception e) {
            log.debug("关闭音源上下文失败: {}", e.toString());
        }
    }
}
