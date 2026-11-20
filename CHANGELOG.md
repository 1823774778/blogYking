# 更新日志

## 1.2.0 — 2026-11-20

### 新增

- 后台「更新」页接入 GitHub Releases：读取 `1823774778/blogYking` 的最新版本、发布时间与更新内容。
- 展示历史版本列表，可展开查看每个版本的完整更新说明，并提供 GitHub Release 与 jar 资产入口。
- GitHub 发布信息带 10 分钟缓存；接口失败时回退到上一次成功缓存，并显示缓存时间与错误原因。

### 改进

- 版本探针继续返回 `version / buildTime / instanceId / pid`，更新成功后页面会重新计算「当前已是最新 / 可更新」。
- README 增加 GitHub Release 发布流程、`GITHUB_TOKEN` 限额说明和 OTA 新接口。

### 验证

- 使用 GitHub Release 模拟数据验证最新版本、历史版本、更新内容 Markdown 渲染、刷新与错误回退。
- 前端 `vite build` 与后端 Maven 打包通过，jar 启动后 `/api/admin/ota/releases` 可返回发布数据。

## 1.1.0 — 2026-11-20

### 新增

- 后台「更新」页支持本地上传 `blog.jar`，校验后由独立更新进程备份、替换、重启。
- 独立运行模式健康检查失败自动回滚；systemd / Docker 模式交由进程管理器拉起。
- 版本探针新增 `instanceId` 与 `pid`，用于确认新实例确实上线。

### 主题

- Firefly 风格薄荷绿主题：悬浮导航、横幅、三栏首页、文章卡、关于页、移动端 dock 与深色模式。

## 1.0.0 — 初始版本

- Spring Boot 3 + Vue 3 个人博客：初始化向导、文章、评论、点赞、收藏、后台、站点配置、H2 / MySQL、可选 Redis。
