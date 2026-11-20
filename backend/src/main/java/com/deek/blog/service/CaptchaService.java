package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.dto.AuthDtos.CaptchaResp;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地离线图形验证码：不依赖第三方服务，也不需要 Redis。
 * 题目与答案都只保存在服务进程内，5 分钟有效、一次性核销。
 */
@Service
public class CaptchaService {

    private static final int EXPIRE_SECONDS = 300;
    private static final int MAX_ATTEMPTS = 3;
    private static final int WIDTH = 132;
    private static final int HEIGHT = 46;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public CaptchaResp create() {
        int a = 2 + random.nextInt(9);
        int b = 2 + random.nextInt(9);
        boolean plus = random.nextBoolean();
        if (!plus && b > a) {
            int swap = a;
            a = b;
            b = swap;
        }
        int answer = plus ? a + b : a - b;
        String expression = a + (plus ? "+" : "-") + b + "=?";
        String id = randomHex(24);
        entries.put(id, new Entry(String.valueOf(answer), LocalDateTime.now().plusSeconds(EXPIRE_SECONDS), 0));
        purgeExpired();
        String svg = svg(expression);
        String encoded = Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        return new CaptchaResp(id, "data:image/svg+xml;base64," + encoded, EXPIRE_SECONDS);
    }

    public void verify(String id, String answer) {
        String key = id == null ? "" : id.trim();
        if (key.isBlank()) {
            throw new BizException("请先完成安全验证");
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            throw new BizException("验证码已失效，请刷新后重试");
        }
        if (entry.expiresAt().isBefore(LocalDateTime.now())) {
            entries.remove(key);
            throw new BizException("验证码已过期，请刷新后重试");
        }
        String input = answer == null ? "" : answer.trim();
        if (input.isBlank()) {
            throw new BizException("请输入验证码计算结果");
        }
        if (!entry.answer().equals(input)) {
            int attempts = entry.attempts() + 1;
            if (attempts >= MAX_ATTEMPTS) {
                entries.remove(key);
                throw new BizException("验证码错误次数过多，请刷新后重试");
            }
            entries.put(key, new Entry(entry.answer(), entry.expiresAt(), attempts));
            throw new BizException("验证码不正确，请重新计算");
        }
        entries.remove(key);
    }

    private void purgeExpired() {
        LocalDateTime now = LocalDateTime.now();
        entries.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private String svg(String expression) {
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            int x1 = random.nextInt(WIDTH);
            int y1 = random.nextInt(HEIGHT);
            int x2 = random.nextInt(WIDTH);
            int y2 = random.nextInt(HEIGHT);
            String color = i % 2 == 0 ? "#9bcbb9" : "#c7ddd5";
            noise.append("<path d=\"M").append(x1).append(' ').append(y1)
                    .append(" L").append(x2).append(' ').append(y2)
                    .append("\" stroke=\"").append(color).append("\" stroke-width=\"1\" opacity=\"0.45\"/>");
        }
        for (int i = 0; i < 14; i++) {
            int x = random.nextInt(WIDTH);
            int y = random.nextInt(HEIGHT);
            noise.append("<circle cx=\"").append(x).append("\" cy=\"").append(y)
                    .append("\" r=\"1.2\" fill=\"#8cbfae\" opacity=\"0.55\"/>");
        }
        return """
                <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">
                  <defs>
                    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
                      <stop offset="0" stop-color="#f4faf7"/>
                      <stop offset="1" stop-color="#e8f4ee"/>
                    </linearGradient>
                  </defs>
                  <rect width="100%%" height="100%%" rx="10" fill="url(#bg)"/>
                  %s
                  <text x="10" y="31" font-family="monospace" font-size="24" font-weight="700"
                        letter-spacing="2" fill="#1c5d49" transform="rotate(-2 66 23)">%s</text>
                  <path d="M8 38 Q66 32 124 39" stroke="#4fc9a0" stroke-width="1.2" opacity="0.45" fill="none"/>
                </svg>
                """.formatted(WIDTH, HEIGHT, WIDTH, HEIGHT, noise, expression);
    }

    private String randomHex(int length) {
        byte[] bytes = new byte[length / 2];
        random.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private record Entry(String answer, LocalDateTime expiresAt, int attempts) {
    }
}
