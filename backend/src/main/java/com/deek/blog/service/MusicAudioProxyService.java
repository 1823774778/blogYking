package com.deek.blog.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 音乐播放地址代理。
 *
 * <p>平台返回的音频地址常带防盗链、短时 token，且部分地址只有 http。浏览器直接播放会因
 * 混合内容、Referer 或 token 过期失败。播放地址由本站签发短时令牌后经服务端转发，浏览器
 * 只访问同源的 /api/music/audio，既避免混合内容，也能补上平台所需的请求头。</p>
 */
@Service
public class MusicAudioProxyService {

    private static final long TOKEN_TTL_MILLIS = 6 * 60 * 60 * 1000L;
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; DeekBlog/1.20; +https://github.com/1823774778/blogYking)";
    private static final Set<String> PLATFORMS = Set.of("wy", "tx", "kw", "kg", "mg");
    private static final Map<String, String> REFERERS = Map.of(
            "wy", "https://music.163.com/",
            "tx", "https://y.qq.com/",
            "kw", "https://www.kuwo.cn/",
            "kg", "https://www.kugou.com/",
            "mg", "https://music.migu.cn/"
    );

    private final HttpClient client;
    private final byte[] secret = new byte[32];

    public MusicAudioProxyService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        new SecureRandom().nextBytes(secret);
    }

    public String createProxyUrl(String rawUrl, String platform) {
        String audio = normalizeAudioUrl(rawUrl);
        if (audio.isBlank()) return "";
        String source = PLATFORMS.contains(platform) ? platform : "wy";
        long expires = System.currentTimeMillis() + TOKEN_TTL_MILLIS;
        String payload = expires + "\n" + source + "\n" + audio;
        String body = base64(payload.getBytes(StandardCharsets.UTF_8));
        String signature = base64(hmac(payload));
        return "/api/music/audio?token=" + URLEncoder.encode(body + "." + signature, StandardCharsets.UTF_8);
    }

    /**
     * 校验令牌并向平台发起音频请求。返回 null 表示令牌无效或过期。
     */
    public ProxyStream open(String token, String rangeHeader) throws IOException, InterruptedException {
        Target target = verify(token);
        if (target == null) return null;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target.url()))
                .timeout(Duration.ofSeconds(40))
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERERS.getOrDefault(target.platform(), REFERERS.get("wy")))
                .header("Accept", "*/*")
                .GET();
        if (rangeHeader != null && rangeHeader.matches("bytes=\\d*-\\d*")) {
            builder.header("Range", rangeHeader.trim());
        }
        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new ProxyStream(response.statusCode(), response.headers(), response.body());
    }

    private Target verify(String token) {
        if (token == null || token.isBlank()) return null;
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) return null;
        String body = token.substring(0, dot);
        String signature = token.substring(dot + 1);
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
            byte[] actual = Base64.getUrlDecoder().decode(signature);
            byte[] expected = hmac(payload);
            if (!MessageDigest.isEqual(actual, expected)) return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
        int first = payload.indexOf('\n');
        int second = payload.indexOf('\n', first + 1);
        if (first <= 0 || second <= first) return null;
        long expires;
        try {
            expires = Long.parseLong(payload.substring(0, first));
        } catch (NumberFormatException e) {
            return null;
        }
        if (expires < System.currentTimeMillis()) return null;
        String platform = payload.substring(first + 1, second);
        String url = payload.substring(second + 1);
        if (!PLATFORMS.contains(platform) || normalizeAudioUrl(url).isBlank()) return null;
        return new Target(url, platform);
    }

    private String normalizeAudioUrl(String rawUrl) {
        String value = String.valueOf(rawUrl == null ? "" : rawUrl).trim().replace("&amp;", "&");
        if (value.startsWith("//")) return "https:" + value;
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return "";
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return "";
            return value;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private byte[] hmac(String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("无法签发音乐代理令牌", e);
        }
    }

    private String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private record Target(String url, String platform) {
    }

    public record ProxyStream(int status, java.net.http.HttpHeaders headers, InputStream body) {
    }
}
