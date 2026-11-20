# 1Panel 部署说明

## 先解释这个报错

```
Error: Unable to access jarfile blog.jar
```

这不是 jar 损坏，而是**容器里的 JVM 在当前工作目录找不到 `blog.jar`**。常见三种情况：

1. 启动命令是相对路径 `java -jar blog.jar`，但容器工作目录是 `/`，jar 却挂在 `/app`；
2. 只挂载了目录，jar 实际没上传进那个目录（或文件名是 `blog (1).jar`、`blog.jar.jar`）；
3. 挂载写成了「文件 → 容器目录」，容器里 `/app/blog.jar` 变成了一个目录。

排查命令（在宿主机执行）：

```bash
docker ps -a | grep blog
docker inspect --format '工作目录={{.Config.WorkingDir}} 启动命令={{.Config.Cmd}}' blog
docker inspect --format '{{range .Mounts}}{{.Source}} -> {{.Destination}} ({{.Type}}){{"\n"}}{{end}}' blog
```

## 方案 A：改现有容器（最快）

1. 先把反复重启的容器**停止**。
2. 确认宿主机上 jar 的位置和文件名：

```bash
ls -lh /opt/1panel/apps/blog/blog.jar    # 按你的实际目录调整
```

3. 在 1Panel「容器 → 编辑」里二选一：
   - 把**工作目录**设为 `/app`，启动命令仍为 `java -jar blog.jar`；
   - 或者启动命令改成绝对路径：`java -jar /app/blog.jar`。
4. 挂载关系应是：
   - `宿主机 /opt/1panel/apps/blog/blog.jar` → 容器 `/app/blog.jar`（文件挂载）
   - 或者 `宿主机 /opt/1panel/apps/blog` → 容器 `/app`（目录挂载）
5. 数据目录也要挂：`/opt/1panel/apps/blog/data → /app/data`、`/opt/1panel/apps/blog/local-storage → /app/local-storage`、`/opt/1panel/apps/blog/uploads → /app/uploads`（旧目录，保留用于首次迁移）、`/opt/1panel/apps/blog/config → /app/config`，否则重建容器后数据库、图片和首次初始化配置都会丢。

## 方案 B：用项目里的 Dockerfile / compose（推荐）

项目根目录已提供 `Dockerfile` 与 `docker-compose.yml`，把 `blog.jar` 和这两个文件放在同一目录：

```
/opt/1panel/apps/blog/
├── blog.jar
├── Dockerfile
├── docker-compose.yml
├── data/
├── local-storage/   # 本地图片分类存储（新版本默认目录）
├── uploads/         # 旧版图片目录，保留用于首次自动迁移
└── config/          # 首次初始化向导生成，含数据库/Redis 口令
```

```bash
cd /opt/1panel/apps/blog
docker compose up -d --build
docker compose logs -f --tail=100
```

在 1Panel 里也可以「容器 → 创建编排」，选择这个 `docker-compose.yml` 启动。

镜像内置 `WORKDIR /app` 和 `java -jar /app/blog.jar`，不会再出现找不到 jar 的问题。

## 反代与域名

1Panel「网站 → 创建网站」→ 反向代理到 `http://127.0.0.1:8080`。
申请证书后开启 HTTPS，并把 compose 里的 `SITE_URL` 改成实际域名（否则找回密码邮件里的链接是 localhost）。

## 首次启动

- 容器第一次启动会自动建库，位置：`/app/data/blog.mv.db`
- 打开 `http://服务器IP:8080/`，会跳到 `/setup` 初始化向导
- 没有默认账号：管理员用户名 / 邮箱 / 密码由你在向导里设置
- 如果填了 MySQL 或 Redis，提交后执行 `docker compose restart blog` 让配置生效；只选 H2 + 不用 Redis 时无需重启
- 向导生成的 `config/application.yml` 必须落在挂载出来的 `./config` 里，否则重建容器会丢
- 初始化完成后，立刻替换 `JWT_SECRET`，并在 1Panel 里确认 `config/` 目录权限

## 常见检查

```bash
curl http://127.0.0.1:8080/api/meta/site     # 应返回站点 JSON
curl http://127.0.0.1:8080/api/system/version # 版本探针
```

若还是重启，看日志前 60 行：

```bash
docker compose logs --tail=60 blog
```

`UnsupportedClassVersionError` 说明镜像 Java 版本低于 17；
`Unable to access jarfile` 说明路径没对上，按方案 A 检查工作目录与挂载。
