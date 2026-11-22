package com.deek.blog.theme;

import com.deek.blog.common.BizException;
import com.deek.blog.service.SiteConfigService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ThemeService {

    public record ThemeView(String id, String name, String description, String mode,
                            String accent, String accentSoft, String paper, String ink,
                            boolean builtin, int order) {}

    private static final List<ThemeView> THEMES = List.of(
            new ThemeView("ink", "墨记", "当前默认的蓝墨配色，适合长文阅读", "auto",
                    "#24589f", "rgba(36, 88, 159, 0.10)", "", "", true, 0),
            new ThemeView("paper", "纸白", "偏暖的纸张底色与深棕强调色", "light",
                    "#7a4f2c", "rgba(122, 79, 44, 0.10)", "#fbf8f1", "#2a2118", true, 1),
            new ThemeView("forest", "青瓷", "低饱和的青绿，安静、克制", "light",
                    "#2f6f5e", "rgba(47, 111, 94, 0.10)", "", "", true, 2),
            new ThemeView("ocean", "海盐", "清透的海蓝，适合技术博客", "light",
                    "#1976a3", "rgba(25, 118, 163, 0.10)", "", "", true, 3),
            new ThemeView("rose", "绯红", "偏酒红的强调色，温度更高", "light",
                    "#a23d5a", "rgba(162, 61, 90, 0.10)", "", "", true, 4),
            new ThemeView("night", "深夜", "深蓝底与冷色强调，适合夜间阅读", "dark",
                    "#7fb9ff", "rgba(127, 185, 255, 0.12)", "#0e1c2f", "#edf4ff", true, 5)
    );

    private final SiteConfigService siteConfigService;

    public ThemeService(SiteConfigService siteConfigService) {
        this.siteConfigService = siteConfigService;
    }

    public Map<String, Object> overview() {
        String current = currentId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("current", current);
        data.put("themes", THEMES);
        return data;
    }

    public String currentId() {
        String id = siteConfigService.get().getOrDefault("themePreset", "ink");
        return THEMES.stream().anyMatch(t -> t.id().equals(id)) ? id : "ink";
    }

    public ThemeView activate(String id) {
        ThemeView theme = THEMES.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> BizException.notFound("主题不存在"));
        siteConfigService.update(Map.of("themePreset", theme.id()));
        return theme;
    }
}
