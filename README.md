# 墨记 · 前后端分离个人博客

一个可以直接 `java -jar` 跑起来的个人博客：Spring Boot 3 + Vue 3，前端构建产物打进后端 jar，单文件部署。视觉采用 [Firefly](https://github.com/CuteLeaf/Firefly) 风格的薄荷绿主题：悬浮圆角导航、横幅壁纸、三栏首页、圆角半透明卡片。

首次启动不再内置默认账号：打开站点会进入**初始化向导**，由你自己设置管理员账号，并按需填写 MySQL、Redis 连接信息。

---

## 功能

**首次启动**
- 控制台打印初始化横幅：管理员入口、当前数据库、配置文件路径
- Web 端 `/setup` 向导：管理员账号 + 可选 MySQL + 可选 Redis
- MySQL 连接测试：库不存在时自动创建，已有库按 `ddl-auto=update` 更新表结构；Redis 连接测试同样不通过不写配置
- 配置写入外部 `config/application.yml`（权限 600），重启后生效
- 管理员当场创建并自动登录；H2 + 不用 Redis 时无需重启

**账号体系**
- 注册 / 登录（用户名或邮箱均可）/ JWT 无状态鉴权
- 邮箱找回密码（SMTP 发信，30 分钟一次性令牌）
- 个人资料、头像上传、修改密码
- 账号禁用、角色（读者 / 管理员）

**内容**
- Markdown 文章，草稿 / 发布两种状态，置顶
- 标签、封面、摘要（留空自动截取正文）
- 关键词搜索（标题 / 摘要 / 标签）、标签筛选、最新 / 热门排序
- 后台在线写文章，保存后即时生效，无需重启

**互动**
- 评论（支持一级回复，回复的回复自动归并到顶层）
- 点赞、收藏
- 浏览量（同一 IP 对同一篇文章 30 分钟内只计一次；可选 Redis 共享去重）

**站点与运维**
- 站点名称 / 描述 / 公告 / 页脚 / 备案号 / 社交链接 / 关于页，全部后台可改，立即生效
- 亮色 / 暗色主题，响应式布局，history 路由
- OTA 版本探针：`/api/system/version`
- GitHub Releases 发布探测：后台「更新」页显示最新版本、更新内容与历史版本
- 运行状态：`/api/system/health`（数据库产品名、Redis 状态）
- SMTP 测试按钮

**主题**
- Firefly 风格：薄荷绿主色、悬浮玻璃导航、大幅横幅壁纸
- 三栏首页：左侧个人资料卡 / 中间文章流 / 右侧热门、统计、站点信息
- 圆角半透明卡片、图片右置文章卡、标签胶囊；文章详情为 hero 大图 + 白色正文卡
- 移动端底部浮动 dock，桌面与移动端均支持亮 / 暗主题

## 主题说明

视觉机制参考 [CuteLeaf/Firefly](https://github.com/CuteLeaf/Firefly)（MIT），但**没有引入 Astro 构建链**：这里是把它的设计语言在现有 Vue 3 组件里原生实现，后端接口与数据库结构完全不变。

- 主题覆盖层：`frontend/src/firefly.css`，在 `main.js` 中于 `style.css` 之后引入，统一控制主题变量、导航、横幅、三栏、卡片、详情页与移动端 dock。
- 主要改造组件：`views/Home.vue`、`views/PostDetail.vue`、`views/About.vue`、`components/PostCard.vue`、`components/NavBar.vue`、`App.vue`。
- 原创插画素材：`frontend/src/assets/firefly-hero.webp`、`avatar-firefly.webp`、`cover-desk.webp`、`cover-key.webp`、`cover-night.webp`。均为 AI 生成的原创图，不包含 Firefly 仓库内的素材。
- 换图：直接替换以上同名文件（建议 WebP，横幅约 1920×900），或改 `firefly.css` 中 `.ff-hero` 的 `background-image`。
- 改主色：调整 `firefly.css` 顶部的 `--ff-primary`、`--ff-primary-deep` 与 `--accent`；深色模式在 `[data-theme="dark"]` 块中单独覆盖。
- 文章封面兜底：未设置封面时按标签匹配 `cover-key`（Java / Spring / 安全）、`cover-night`（生活 / 思考 / 夜）、`cover-desk`（写作 / 随笔 / 建站）。
- 移动端资料卡、文章卡、详情卡与底部 dock 的断点在 920px / 640px，可在 `firefly.css` 末尾的媒体查询中调整。

主题作者署名已显示在首页右侧「站点信息」中，可在后台站点配置或直接改 `Home.vue` 中对应文案。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.2、Spring Security、Spring Data JPA、JJWT、JavaMail |
| 数据库 | 默认 H2 文件库（零配置）；可在向导里切换 MySQL |
| 缓存 | Redis 可选（仅用于浏览量去重，未启用时走进程内 Map） |
| 前端 | Vue 3、Vue Router、Axios、marked + DOMPurify、Vite |
| 打包 | 前端 `vite build` 输出到 `backend/src/main/resources/static`，Maven 打成可执行 jar（约 63MB） |

## 目录结构

```
personal-blog/
├── blog.jar                 # 已构建好的可执行包（约 63MB，不提交 Git，发布到 Releases）
├── CHANGELOG.md             # 版本更新日志
├── README.md
├── Dockerfile
├── docker-compose.yml
├── release-notes/           # 每个 GitHub Release 的更新说明
├── scripts/
│   ├── build.sh             # 一键构建：前端 + 后端
│   ├── ota-update.sh        # 替换 jar 并重启（systemd）
│   └── release-github.sh    # 发布 jar + SHA-256 + 更新说明到 GitHub Releases
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/deek/blog/
│       │   ├── config/      # 安全、静态资源、启动提示、空库播种
│       │   ├── controller/  # 认证、文章、评论、个人、后台、元信息、系统、初始化
│       │   ├── dto/         # 请求/响应模型（含 SetupDtos）
│       │   ├── entity/      # User / Post / Comment / PostLike / PostFavorite / ResetToken / SiteConfig
│       │   ├── repository/
│       │   ├── security/    # JWT 工具、过滤器、当前用户
│       │   └── service/     # 业务、DemoDataService、SetupService
│       └── resources/
│           ├── application.yml
│           ├── application-mysql.yml
│           └── static/      # 前端构建产物（已生成）
└── frontend/
    ├── package.json
    ├── vite.config.js
    └── src/
        ├── firefly.css      # Firefly 风格主题覆盖层（在 style.css 之后引入）
        ├── assets/          # 横幅、头像、文章封面等原创 WebP 素材
        └── views/           # 页面、组件、样式（含 views/Setup.vue）
```

运行时才会生成、不要提交到仓库：

```
data/                 # H2 数据库文件
uploads/              # 上传的图片
config/application.yml # 初始化向导写入的连接信息与管理员哈希
updates/              # OTA 暂存包、updater.jar、日志、updates/backups 旧包备份
backups/              # scripts/ota-update.sh 命令行更新的备份目录
```

## 快速开始

要求：JDK 17+。

```bash
java -jar blog.jar
```

打开 <http://localhost:8080>，会自动跳到 `/setup`：

1. **管理员账号**：用户名（3-20 位字母/数字/下划线）、邮箱、昵称（可选）、密码（至少 6 位）。
2. **数据库**：先用内置 H2 体验，或选 MySQL 并填写地址、端口、库名、用户名、密码，点「测试连接」。库不存在会自动创建；已有库保留数据并更新表结构。
3. **Redis（可选）**：需要多实例共享浏览量去重时再填，同样支持测试连接。
4. **完成**：管理员立即创建并自动登录；如果启用了 MySQL 或 Redis，按页面提示重启服务让新配置生效。

H2 + 不用 Redis 时不需要重启，提交后直接进入首页。

> **没有默认的 `admin/admin123456`、`reader/reader123456`。** 第一个管理员只能由向导创建；没有管理员之前，注册接口也会拒绝注册。
>
> **部署到公网时，请先在本机完成 `/setup`，再开放访问。** 初始化完成后，`/api/setup/*` 会返回 403。

### 初始化后会生成什么

- `data/blog.mv.db` —— H2 数据库（含管理员、演示文章）
- `uploads/` —— 上传图片
- `config/application.yml` —— 向导写入的 MySQL/Redis 配置与管理员 bcrypt 哈希，权限 600

备份 `data/`、`uploads/`、`config/` 即可完整迁移。使用外部 MySQL 时，还要备份数据库本身。

想完全重来：停服务，删除 `data/` 与 `config/application.yml`，再启动，就会重新进入向导（**会丢失原有内容，谨慎操作**）。

## 首次初始化向导

### 触发条件

后端以「库里没有任何账号」作为未初始化状态。满足时：

- 控制台打印：

  ```
  ============================================================
    首次启动：站点尚未初始化
  ------------------------------------------------------------
    1. 管理员账号：打开 http://localhost:8080/setup 填写用户名 / 邮箱 / 密码
    2. MySQL      ：向导中填写连接信息，保存后重启生效
    3. Redis      ：可选，用于浏览量去重；向导中填写后重启生效
    当前数据库    ：H2（文件库）
    配置文件      ：./config/application.yml（初始化完成后自动生成）
  ============================================================
  ```

- 前端任意页面都会重定向到 `/setup`，导航栏暂时隐藏。
- `GET /api/setup/status` 返回 `required: true`。

一旦向导创建了管理员，`required` 变为 `false`，向导入口和连接测试接口全部关闭。

### 数据库选项

| 选项 | 行为 |
| --- | --- |
| 内置 H2 | 直接写入当前 `data/` 库，管理员当场生效，无需重启 |
| MySQL | 先测试连接，成功后把 `spring.datasource` 写入 `config/application.yml`；**重启后**才真正切换 |

选 MySQL 时：

- 不需要提前建库：连接后如果库不存在，会用同一账号自动创建 `utf8mb4` 库；账号需具备 `CREATE` 权限。
- 库已存在时不会清空数据，JPA 以 `ddl-auto=update` 自动补齐/更新表结构。
- 旧版 MySQL 库的 `posts.content` 会在启动时自动升级为 `LONGTEXT`，已有文章内容保留。
- 向导会先验证连通性，连不上不会写配置，也不会创建管理员。
- 如果目标库是空的：重启后会用向导里的管理员账号（bcrypt 哈希）自动重建管理员，并写入演示文章。
- 如果目标库已有数据：沿用库里的账号，向导里填的账号不会覆盖已有用户。

### Redis 选项

Redis 只用于浏览量去重，不存文章/用户数据，可以随时关掉。

- 启用后，键形如 `blog:view:{postId}:{ip}`，TTL 30 分钟，用 `SETNX` 保证多实例下也只计一次。
- Redis 连接异常时自动回退到进程内 `ConcurrentHashMap`，不影响页面访问。
- 向导会先 `PING` 测试，再写配置；重启后 `GET /api/system/health` 的 `redis` 字段应为 `ok: PONG`。

### 写入的配置长什么样

`config/application.yml` 由 SnakeYAML 生成，结构如下（密码已打码）：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/blog?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false&createDatabaseIfNotExist=true
    username: blog
    password: '******'
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password: ''
      database: 0
      timeout: 3s
app:
  redis:
    enabled: true
  bootstrap:
    admin-username: deek
    admin-email: deek@example.com
    admin-password-hash: '$2a$10$...'
    admin-nickname: Deek
```

- 文件里含数据库口令与管理员哈希，已尽量设为 600；不要提交、不要贴到聊天里。
- `app.bootstrap.*` 只用于「换到空库后重建管理员」，不要手改哈希；改密码请登录后台改。
- 删除该文件会回到内置默认配置（H2 + 未启用 Redis）。如果站点一直跑在 MySQL 上，删除前先确认 `data/` 里的 H2 库仍有可登录账号。

## 配置（环境变量）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `8080` | 服务端口 |
| `SITE_URL` | `http://localhost:8080` | 对外访问地址，用于拼接找回密码链接 |
| `JWT_SECRET` | 内置示例值 | **务必替换**，至少 32 字符 |
| `JWT_EXPIRE_HOURS` | `72` | 登录有效期（小时） |
| `UPLOAD_DIR` | `./uploads` | 图片存储目录 |
| `SETUP_CONFIG` | `./config/application.yml` | 初始化向导写入的外部配置文件路径 |
| `REDIS_ENABLED` | `false` | 是否启用 Redis 去重 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 地址 |
| `REDIS_PASSWORD` / `REDIS_DB` | 空 / `0` | Redis 口令与库号 |
| `MAIL_ENABLED` | `false` | 是否真正发送邮件 |
| `MAIL_HOST` / `MAIL_PORT` | 空 / `465` | SMTP 服务器 |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | 空 | SMTP 账号 / 授权码 |
| `MAIL_SSL` | `true` | 465 端口一般开 SSL |
| `MAIL_STARTTLS` | `false` | 587 端口改为 `true` 并关闭 SSL |
| `APP_VERSION` | 空（读 jar 清单） | 版本号覆盖；留空时 OTA 探针读取 jar 的 `Implementation-Version` |
| `BUILD_TIME` | 空（显示 `dev`） | 构建时间，打包时注入 |
| `OTA_ENABLED` | `true` | 是否允许后台本地上传 jar 更新 |
| `OTA_JAR_PATH` | 空 | 当前 jar 路径；留空自动根据 `java.class.path` 定位 |
| `OTA_WORK_DIR` | `./updates` | 上传暂存、备份、更新日志目录 |
| `OTA_HEALTH_TIMEOUT` | `45` | 新版本健康检查秒数，超时则回滚 |
| `OTA_GITHUB_REPO` | `1823774778/blogYking` | 后台读取 Release 的仓库（`owner/repo`） |
| `GITHUB_TOKEN` | 空 | 可选；提高 GitHub API 限额，不会返回前端 |
| `OTA_GITHUB_API_BASE` | `https://api.github.com` | GitHub API 地址，一般不用改 |
| `OTA_GITHUB_CACHE_SECONDS` | `600` | Release 信息缓存秒数 |
| `OTA_GITHUB_MAX_RELEASES` | `12` | 最多读取多少个 Release |
| `BOOTSTRAP_ADMIN_*` | 空 | 向导自动写入的兜底管理员信息，一般不用手填 |

**优先级**：命令行参数 > 环境变量 > `config/application.yml` > jar 内 `application.yml`。

所以向导生成的文件会覆盖内置默认值，但你在 systemd / compose 里显式设置的 `PORT`、`JWT_SECRET`、`SITE_URL`、`MAIL_*` 仍然优先。想改数据库/Redis，直接改 `config/application.yml` 或重新走向导（只能空库时）。

### 开启 SMTP 找回密码

```bash
MAIL_ENABLED=true \
MAIL_HOST=smtp.qq.com MAIL_PORT=465 MAIL_SSL=true \
MAIL_USERNAME=you@qq.com MAIL_PASSWORD=你的授权码 \
SITE_URL=https://blog.example.com \
java -jar blog.jar
```

- QQ 邮箱 / 163：密码填**授权码**，不是登录密码；端口 465 开 SSL。
- Gmail：`MAIL_PORT=587`、`MAIL_SSL=false`、`MAIL_STARTTLS=true`。
- 未开启 SMTP 时，`/api/auth/forgot-password` 会直接把重置链接写在响应里（`devLink`），前端会显示出来，方便本地调试；同时服务端日志也会打印。

### 手动切换 MySQL（不用向导）

可直接用 `mysql` profile 启动；目标库不存在时，MySQL 驱动会用 `DB_USER` 自动创建（需 `CREATE` 权限）：

```bash
java -jar blog.jar --spring.profiles.active=mysql \
  --DB_HOST=127.0.0.1 --DB_PORT=3306 --DB_NAME=blog \
  --DB_USER=root --DB_PASSWORD=yourpass
```

表结构由 JPA `ddl-auto=update` 自动创建/更新，MySQL 驱动已内置。第一次连空库时，如果不想再走向导，可以先把 `config/application.yml` 里的 `app.bootstrap.*` 带上（由一次 H2 初始化生成），启动后会自动建管理员。

## 构建

```bash
# 方式一：一键脚本
bash scripts/build.sh

# 方式二：手动
cd frontend && npm install && npm run build      # 产物写入 backend/src/main/resources/static
cd ../backend && mvn clean package -DskipTests    # 生成 target/blog.jar
```

构建完成后把 `backend/target/blog.jar` 复制到项目根目录即可。

前端开发时：

```bash
cd frontend && npm run dev     # http://localhost:5173，/api 与 /uploads 代理到 8080
```

## 部署

### Docker（1Panel 推荐）

项目根目录自带 `Dockerfile` 与 `docker-compose.yml`，把 `blog.jar` 放在同一目录：

```bash
docker compose up -d --build
```

- 数据持久化在 `./data`、`./uploads`
- **向导配置持久化在 `./config`**（compose 已挂载 `./config:/app/config`；不加这个卷，容器重建后 MySQL/Redis 配置会丢）
- 端口 8080，`SITE_URL`、`JWT_SECRET`、SMTP 等都在 compose 的 `environment` 里改
- 1Panel 的路径排查、反代与证书步骤见 [`deploy/1panel.md`](deploy/1panel.md)

### systemd

```ini
# /etc/systemd/system/deek-blog.service
[Unit]
Description=deek blog
After=network.target

[Service]
User=www
WorkingDirectory=/opt/deek-blog
Environment=PORT=8080
Environment=JWT_SECRET=换成你自己的长随机字符串
Environment=MAIL_ENABLED=true
Environment=MAIL_HOST=smtp.qq.com
Environment=MAIL_PORT=465
Environment=MAIL_USERNAME=you@qq.com
Environment=MAIL_PASSWORD=你的授权码
Environment=SITE_URL=https://blog.example.com
ExecStart=/usr/bin/java -jar /opt/deek-blog/blog.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now deek-blog
```

向导要把配置写到 `WorkingDirectory/config/application.yml`，请确保 `User=www` 对该目录有写权限，或显式指定：

```ini
Environment=SETUP_CONFIG=/opt/deek-blog/config/application.yml
```

### Nginx 反代

```nginx
server {
    listen 443 ssl;
    server_name blog.example.com;

    # OTA 需要上传完整 jar（当前约 63MB）
    client_max_body_size 105m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

> 浏览量按 IP 去重依赖 `X-Forwarded-For`，反代时请保留该头（后端已开启 `forward-headers-strategy`）。

## OTA 更新

这个项目里的「OTA」分两层：

**1. 内容级热更新（无需重启）**

后台修改文章、评论、站点配置，写入数据库后立即对所有请求生效，没有本地缓存，也不需要重新打包。

**2. 版本级更新（上传 jar + 备份 + 替换 + 重启）**

后台顶部「更新」页支持直接选择本机的新 `blog.jar`：

1. 选择 jar → **上传并校验**：只写入 `updates/staging`，校验 Spring Boot 启动类、应用配置、文件完整性、SHA-256 与磁盘空间，不会立刻重启。
2. 校验通过后点 **立即更新并重启**：写入 `updates/ota-state.properties`，启动独立的 `updater.jar` 进程。
3. updater 备份当前 jar 到 `updates/backups/`，替换 `blog.jar`，启动新进程，轮询 `/api/system/version` 的 `instanceId` 做健康检查。
4. 新版本 45 秒内未通过健康检查时，自动结束新进程、恢复备份、重新启动旧版本，并把状态写成 `ROLLED_BACK`。
5. 页面会自动重连并对比目标 SHA-256，成功显示「更新成功」，回滚显示失败原因。

版本探针：`GET /api/system/version` → `{ version, buildTime, instanceId, pid, uptimeSeconds, ... }`。
`instanceId` 每次 JVM 启动都会变化，用于区分「同一个版本被重新拉起」和「没有重启」。

**GitHub Releases 更新内容**

后台「更新」页还会读取 <https://github.com/1823774778/blogYking/releases>：

- 最新稳定版本、发布时间、完整更新说明；
- 更早的历史版本列表，可逐个展开查看更新内容；
- 每个版本提供 GitHub Release 与 `blog.jar` 资产入口；
- 根据当前运行版本计算「当前已是最新 / 可更新到 vX.Y.Z」。

实现只读取 GitHub API，不自动下载或执行 Release 资产；下载 jar 后仍需在下方走「上传并校验 → 立即更新」流程。

- 默认缓存 10 分钟，避免频繁请求 GitHub；点「刷新发布信息」可强制重新读取。
- GitHub 未认证 API 每小时只有 60 次。服务器共享出口 IP 时可能触发限流；建议在启动环境配置 `GITHUB_TOKEN`（只需公开仓库读取权限），token 不会返回前端。
- 读取失败时会回退到上一次成功缓存，并在页面显示错误原因与缓存时间。
- 仓库为公开仓库；如果改为私有仓库，必须配置 `GITHUB_TOKEN`，否则 API 会返回 404。

**运行模式**

| 模式 | 自动更新 | 说明 |
| --- | --- | --- |
| `standalone` | 支持 | `java -jar blog.jar` 直接运行；完整备份、重启、健康检查与自动回滚 |
| `systemd` | 支持（由 systemd 拉起） | updater 先替换 jar，再让当前进程退出；要求 `Restart=always`（或 `on-failure` 且退出码非 0）。systemd 模式不做自动回滚 |
| `docker` | 支持（仅当前容器） | updater 替换容器内 `/app/blog.jar`，当前进程退出后由 `restart: unless-stopped/always` 拉起。重建容器会回退到镜像里的 jar，长期更新仍需重建镜像 |
| `dev` | 不支持 | 以 `target/classes` 开发目录运行时没有可替换的 jar |

- systemd / Docker 模式必须保证服务管理器会自动重启；否则替换后站点会保持离线。
- 更新失败后，`updates/backups/` 保留旧包；可手动覆盖回去再启动。
- 更新只改 jar，不动 `data/`、`uploads/`、`config/`；JPA 为 `update` 模式，回滚代码不会破坏已有数据，但新版本已写入的内容会保留。
- 每次更新会在 `updates/` 留一份 `updater.jar`（当前约 63MB）和 `app.log`，可定期清理；`backups/` 默认只保留最近 5 份。

**命令行备用方案**

```bash
bash scripts/build.sh
scp blog.jar server:/opt/deek-blog/blog.jar.new
APP_DIR=/opt/deek-blog SERVICE_NAME=deek-blog \
  bash scripts/ota-update.sh /opt/deek-blog/blog.jar.new
curl http://127.0.0.1:8080/api/system/version
```

`scripts/ota-update.sh` 适合 systemd / 无后台权限时使用；会自动备份旧包、替换并重启。后台的本地上传更新与它使用同一套 `blog.jar` + `backups/` 约定。

**发布新版本到 GitHub Releases**

源码仓库：<https://github.com/1823774778/blogYking>。jar 不提交 Git，发布到 Releases：

```bash
# 1. 构建并确认版本
bash scripts/build.sh
curl -s http://127.0.0.1:8080/api/system/version

# 2. 写本版本更新说明（示例：release-notes/1.3.0.md）
# 3. 发布 jar + SHA-256 + 更新说明
gh auth login
GH_REPO=1823774778/blogYking \
  bash scripts/release-github.sh v1.3.0 blog.jar release-notes/1.3.0.md
```

`scripts/release-github.sh` 会校验 jar 是否为本项目 Spring Boot 包、计算 SHA-256、创建/更新 Release，并上传 `blog.jar` 与 `blog.jar.sha256`。后续每个版本都按同样流程发布，后台更新页会自动读到新的最新版本和历史记录。

**安全**

- OTA 上传/应用接口在 `/api/admin/**` 下，必须管理员 JWT。
- 上传只接受 `.jar`，且必须是 `Start-Class` 为 `com.deek.blog.*` 的 Spring Boot 可执行包；不是本项目或 zip 损坏会直接拒绝。
- 仍建议只上传自己构建、校验过 SHA 的包；这个功能本质上是管理员执行代码，权限等同于服务器部署权限。
- Nginx 反代需放开请求体，至少 `client_max_body_size 105m`。

## API 一览

统一响应：`{ "code": 0, "message": "ok", "data": ... }`，失败时 HTTP 状态码与 `code` 一致（400/401/403/404/500）。

**初始化（仅空库可用，完成后除 status 外全部 403）**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/setup/status` | 是否待初始化、当前数据库、Redis 开关、配置文件、站点地址 |
| POST | `/api/setup/test/mysql` | 测试 MySQL 连接 |
| POST | `/api/setup/test/redis` | 测试 Redis 连接（PING） |
| POST | `/api/setup/complete` | 创建管理员、写配置、播种演示文章，返回 token |
| GET | `/api/system/health` | 数据库产品名、Redis 状态（`disabled` / `ok: PONG` / `error`） |
| GET | `/api/system/version` | 版本、构建时间、`instanceId`、PID、运行时长 |

**OTA（管理员）**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/ota/status` | 当前版本/SHA、运行模式、待更新包、上次更新结果 |
| GET | `/api/admin/ota/releases` | GitHub 最新/历史 Release、更新内容、jar 资产信息；`refresh=1` 强制刷新 |
| POST | `/api/admin/ota/upload` | `multipart/form-data` 字段 `file`，上传并校验 jar |
| POST | `/api/admin/ota/apply` | 应用待更新包，备份、替换并重启 |
| DELETE | `/api/admin/ota/pending` | 丢弃已上传但未应用的包 |

**业务接口**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | 注册（站点无管理员时拒绝） |
| POST | `/api/auth/login` | 登录（用户名或邮箱） |
| GET | `/api/auth/me` | 当前用户（需登录） |
| PUT | `/api/auth/profile` | 修改资料 / 密码 |
| POST | `/api/auth/forgot-password` | 发送重置邮件 |
| POST | `/api/auth/reset-password` | 用 token 重置密码 |
| GET | `/api/posts` | 列表，参数 `keyword/tag/sort/page/size` |
| GET | `/api/posts/{id}` | 详情（含浏览量、是否已赞/已藏） |
| GET | `/api/posts/hot` `/api/posts/tags` `/api/posts/stats` | 热门 / 标签 / 统计 |
| POST | `/api/posts/{id}/like` | 点赞开关 |
| POST | `/api/posts/{id}/favorite` | 收藏开关 |
| GET/POST | `/api/posts/{id}/comments` | 评论列表 / 发表评论 |
| DELETE | `/api/comments/{id}` | 删除评论（作者或管理员） |
| GET | `/api/me/favorites` `/api/me/likes` | 我的收藏 / 喜欢 |
| POST | `/api/upload` | 上传图片（需登录，jpg/png/gif/webp，≤20MB） |
| GET | `/api/meta/site` | 公开站点信息 |
| GET | `/api/system/version` | OTA 版本探针 |
| GET | `/api/admin/stats` | 后台统计 |
| GET/POST/PUT/DELETE | `/api/admin/posts` | 文章管理 |
| GET | `/api/admin/comments` | 评论管理 |
| GET/PUT | `/api/admin/users` `/api/admin/users/{id}/status` `/api/admin/users/{id}/role` | 用户管理 |
| GET/PUT | `/api/admin/config` | 站点配置（热更新） |
| POST | `/api/admin/mail/test` | SMTP 测试 |

## 安全清单

- [ ] 部署后第一时间完成 `/setup`，不要把「未初始化」实例直接暴露在公网
- [ ] 设置随机的 `JWT_SECRET`（≥ 32 字符）
- [ ] `config/application.yml` 保持 600，且不要提交到 Git / 不要发群
- [ ] 全站 HTTPS（Nginx 终止 TLS）
- [ ] 限制 `uploads/` 目录权限，定期备份 `data/`、`uploads/`、`config/`
- [ ] 使用外部 MySQL 时，数据库账号最小权限，并单独做数据库备份
- [ ] OTA 的 `updates/` 里有完整 jar 与备份，只允许管理员访问；只上传自己构建并核对过 SHA 的包
- [ ] 不要提交 `data/`、`uploads/`、`config/`、`updates/`、`node_modules/`、`target/` 到公开仓库（`.gitignore` 已处理）

## 已知边界

- 初始化只在「库里没有任何账号」时开放；初始化完成后不能重复执行。要重来只能删 `data/` 与 `config/application.yml`（或清空数据库）。
- 向导里创建的管理员只写进当前库。切到已有数据的 MySQL 时，沿用那个库里的账号，不会自动合并 H2 的用户。
- MySQL 连接成功路径依赖真实 MySQL 服务；向导只做连通性与 `ddl-auto` 建表，不做数据迁移。从 H2 迁到 MySQL 需要自己导数据。
- 评论只支持两级展示（顶层 + 一级回复），不支持无限嵌套。
- 浏览量去重默认基于进程内存 + IP，进程重启后计数从数据库继续，但去重窗口会重置；启用 Redis 后窗口在多实例间共享。
- 内容热更新依赖单实例；如果部署多实例，需要自行处理配置同步（JPA 直读数据库，本身已共享）。
- 没有 OAuth 第三方登录、全文检索引擎、定时发布。这些可以在此基础上继续加。
- OTA 的自动回滚只在 `standalone` 下执行；systemd / Docker 依赖外部进程管理器重启，回滚需手动覆盖 `updates/backups/`。
- Docker 容器内更新只对当前容器生效，`docker compose up --build` 重建容器后会回到镜像内版本。
- OTA 上传上限 100MB；当前 fat jar 约 63MB，若引入大依赖需要同步调大 `spring.servlet.multipart.max-file-size` 与 Nginx 请求体。
- GitHub Release 读取默认只显示最近 12 个发布；未认证 API 每小时 60 次，共享 IP 可能限流，建议配置只读 `GITHUB_TOKEN`。
- Release 更新内容是 GitHub 上填写的文本，不包含代码签名；下载 jar 后务必核对 Release 附带的 `blog.jar.sha256`，或在本机重新构建。
