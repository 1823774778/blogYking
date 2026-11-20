package com.deek.blog.service;

import com.deek.blog.common.BizException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * 本地上传 jar 的 OTA 更新。
 *
 * <p>上传与校验、应用更新分成两步：先落到 updates/staging 并校验是否为本项目的 Spring Boot 可执行 jar，
 * 再由独立 Java 进程完成备份、替换、重启与（standalone 模式下的）健康检查回滚。
 */
@Service
public class OtaService {

    private static final Logger log = LoggerFactory.getLogger(OtaService.class);
    private static final long MAX_JAR_BYTES = 100L * 1024 * 1024;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final boolean enabled;
    private final String configuredJarPath;
    private final Path workDir;
    private final Path stagingDir;
    private final Path backupDir;
    private final int healthTimeoutSeconds;
    private final int port;
    private final AtomicBoolean applying = new AtomicBoolean(false);
    private final String instanceId = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.now();

    private final Path pendingFile;
    private final Path stateFile;
    private final Path updaterJar;
    private final Path updaterLog;
    private final Path appLog;

    private volatile Path jarPath;
    private volatile String currentSha = "";
    private volatile long currentSize;
    private volatile long jarLastModified = -1;
    private volatile String currentVersion = "未知";
    private volatile String mode = "unknown";
    private volatile String modeNote = "";

    public OtaService(@Value("${app.ota.enabled:true}") boolean enabled,
                      @Value("${app.ota.jar-path:}") String configuredJarPath,
                      @Value("${app.ota.work-dir:./updates}") String workDir,
                      @Value("${app.ota.health-timeout-seconds:45}") int healthTimeoutSeconds,
                      @Value("${server.port:8080}") int port) {
        this.enabled = enabled;
        this.configuredJarPath = configuredJarPath == null ? "" : configuredJarPath.trim();
        this.workDir = Paths.get(workDir).toAbsolutePath().normalize();
        this.stagingDir = this.workDir.resolve("staging");
        this.backupDir = this.workDir.resolve("backups");
        this.healthTimeoutSeconds = Math.max(10, healthTimeoutSeconds);
        this.port = port;
        this.pendingFile = this.workDir.resolve("pending.properties");
        this.stateFile = this.workDir.resolve("ota-state.properties");
        this.updaterJar = this.workDir.resolve("updater.jar");
        this.updaterLog = this.workDir.resolve("updater.log");
        this.appLog = this.workDir.resolve("app.log");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(stagingDir);
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            log.warn("OTA 工作目录创建失败: {}", e.getMessage());
        }

        this.jarPath = resolveJarPath();
        this.mode = detectMode(jarPath);
        this.modeNote = modeNote(mode);
        refreshCurrentJar(true);
        reconcileAfterStartup();
        pruneOldFiles();
        cleanupPendingIfMissing();
        log.info("OTA 状态：mode={}, canAutoUpdate={}, jar={}", mode, canAutoUpdate(), jarPath);
    }

    public String getInstanceId() {
        return instanceId;
    }

    public long getPid() {
        return ProcessHandle.current().pid();
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public boolean canAutoUpdate() {
        return enabled && jarPath != null
                && ("standalone".equals(mode) || "systemd".equals(mode) || "docker".equals(mode))
                && port > 0;
    }

    public synchronized PendingInfo upload(MultipartFile file) {
        if (!enabled) {
            throw new BizException(HttpStatus.FORBIDDEN, "OTA 更新已关闭（app.ota.enabled=false）");
        }
        if (!canAutoUpdate()) {
            throw new BizException(HttpStatus.CONFLICT, modeNote);
        }
        if (applying.get()) {
            throw new BizException(HttpStatus.CONFLICT, "已有更新正在执行，请等待当前进程完成重启");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的 jar 文件");
        }
        if (file.getSize() > MAX_JAR_BYTES) {
            throw new BizException("jar 不能超过 100MB，当前为 " + humanSize(file.getSize()));
        }

        String originalName = safeFileName(file.getOriginalFilename());
        if (!originalName.toLowerCase().endsWith(".jar")) {
            throw new BizException("只支持 .jar 文件");
        }

        Path temp = null;
        try {
            temp = Files.createTempFile(stagingDir, "upload-", ".part");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }

            return stageJar(temp, originalName, file.getSize(), "本地上传");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OTA jar 校验失败: {}", e.getMessage(), e);
            throw new BizException("jar 校验失败：" + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 从可信 GitHub Release 资产地址下载 jar，落盘前复用本地上传的启动类、配置与 SHA-256 校验。
     */
    public synchronized PendingInfo downloadFromGitHub(String rawUrl, String rawFileName) {
        if (!enabled) {
            throw new BizException(HttpStatus.FORBIDDEN, "OTA 更新已关闭（app.ota.enabled=false）");
        }
        if (!canAutoUpdate()) {
            throw new BizException(HttpStatus.CONFLICT, modeNote);
        }
        if (applying.get()) {
            throw new BizException(HttpStatus.CONFLICT, "已有更新正在执行，请等待当前进程完成重启");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
        } catch (Exception e) {
            throw new BizException("下载地址不合法");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !(host.equals("github.com")
                || host.endsWith(".githubusercontent.com"))) {
            throw new BizException("只允许从 GitHub Release 下载更新包");
        }
        String fileName = safeFileName(rawFileName);
        if (!fileName.toLowerCase().endsWith(".jar")) {
            throw new BizException("Release 资产不是 jar 文件");
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(stagingDir, "github-", ".part");
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(120))
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "deek-blog-ota/1.0")
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("GitHub 下载失败：HTTP " + response.statusCode());
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > MAX_JAR_BYTES) {
                throw new BizException("Release 中的 jar 超过 100MB");
            }
            long copied = 0;
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    copied += n;
                    if (copied > MAX_JAR_BYTES) {
                        throw new BizException("Release 中的 jar 超过 100MB");
                    }
                    out.write(buffer, 0, n);
                }
            }
            if (copied <= 0) {
                throw new BizException("GitHub 返回了空文件");
            }
            PendingInfo pending = stageJar(temp, fileName, copied, "GitHub 在线下载");
            temp = null;
            return pending;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("GitHub 下载失败：" + e.getMessage());
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            }
        }
    }

    private PendingInfo stageJar(Path temp, String originalName, long size, String source) throws IOException {
        JarMeta meta = inspectJar(temp);
        if (meta.sha256().equalsIgnoreCase(currentSha)) {
            throw new BizException("这个 jar 与当前运行的版本完全相同，无需更新");
        }
        long usable = Files.getFileStore(workDir).getUsableSpace();
        if (usable < size * 3 + 20L * 1024 * 1024) {
            throw new BizException("磁盘剩余空间不足，更新至少需要约 " + humanSize(size * 3));
        }

        String storedName = meta.sha256().substring(0, 16) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".jar";
        Path stored = stagingDir.resolve(storedName);
        moveReplace(temp, stored);

        if (loadPendingRaw() != null) {
            deletePendingFiles();
        }

        Properties p = new Properties();
        p.setProperty("storedFile", storedName);
        p.setProperty("fileName", originalName);
        p.setProperty("size", Long.toString(size));
        p.setProperty("sha256", meta.sha256());
        p.setProperty("version", meta.version());
        p.setProperty("uploadedAt", LocalDateTime.now().format(TIME));
        writeProperties(pendingFile, p);
        log.info("OTA jar 已{}并通过校验：{} ({}), sha={}", source, originalName, humanSize(size), meta.sha256());
        return new PendingInfo(originalName, size, meta.sha256(), meta.version(),
                LocalDateTime.now().format(TIME));
    }

    public synchronized Map<String, Object> apply() {
        if (!canAutoUpdate()) {
            throw new BizException(HttpStatus.CONFLICT, modeNote);
        }
        if (!applying.compareAndSet(false, true)) {
            throw new BizException(HttpStatus.CONFLICT, "已有更新正在执行，请勿重复提交");
        }

        try {
            Pending pending = loadPendingRaw();
            if (pending == null) {
                throw new BizException("请先上传并校验新的 jar");
            }
            Path newJar = stagingDir.resolve(pending.storedFile()).normalize();
            if (!newJar.startsWith(stagingDir) || !Files.isRegularFile(newJar)) {
                throw new BizException("待更新的 jar 已丢失，请重新上传");
            }
            if (!sha256(newJar).equalsIgnoreCase(pending.sha256())) {
                throw new BizException("待更新的 jar 校验值不一致，请重新上传");
            }
            if (jarPath == null || !Files.isRegularFile(jarPath)) {
                throw new BizException("当前运行的不是可替换的 jar 文件");
            }

            String backupName = "blog-" + LocalDateTime.now().format(STAMP) + "-" + UUID.randomUUID().toString().substring(0, 4) + ".jar";
            Path backup = backupDir.resolve(backupName);
            long usable = Files.getFileStore(backupDir).getUsableSpace();
            if (usable < currentSize + newJar.toFile().length() + 20L * 1024 * 1024) {
                throw new BizException("磁盘剩余空间不足，无法备份当前 jar");
            }

            RestartSpec spec = buildRestartSpec(pending, newJar, backup);
            writeApplyConfig(spec);
            Files.copy(jarPath, updaterJar, StandardCopyOption.REPLACE_EXISTING);

            Map<String, String> state = new LinkedHashMap<>();
            state.put("status", "APPLYING");
            state.put("message", "正在备份并替换 jar");
            state.put("targetSha", pending.sha256());
            state.put("previousSha", currentSha);
            state.put("targetVersion", pending.version());
            state.put("previousVersion", currentVersion);
            state.put("backup", backup.toString());
            state.put("mode", mode);
            state.put("updatedAt", LocalDateTime.now().format(TIME));
            writeState(state);

            launchUpdater();
            log.info("OTA 更新进程已启动，目标版本 {}，backup={}", pending.version(), backup);

            if ("standalone".equals(mode)) {
                scheduleExit(3);
            } else {
                monitorSupervisedReplacement();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "RESTARTING");
            result.put("targetSha", pending.sha256());
            result.put("targetVersion", pending.version());
            result.put("previousVersion", currentVersion);
            result.put("instanceId", instanceId);
            result.put("restartMode", mode);
            return result;
        } catch (BizException e) {
            applying.set(false);
            throw e;
        } catch (Exception e) {
            applying.set(false);
            log.error("OTA 更新启动失败", e);
            writeFailure("更新启动失败：" + e.getMessage());
            throw new BizException("更新启动失败：" + e.getMessage());
        }
    }

    /** 不更换 jar，只按当前 jar 重启站点。 */
    public synchronized Map<String, Object> restart() {
        if (!canAutoUpdate()) {
            throw new BizException(HttpStatus.CONFLICT, modeNote);
        }
        if (!applying.compareAndSet(false, true)) {
            throw new BizException(HttpStatus.CONFLICT, "已有更新正在执行，请勿重复提交");
        }
        try {
            if (jarPath == null || !Files.isRegularFile(jarPath)) {
                throw new BizException("当前运行的不是可重启的 jar 文件");
            }
            String backupName = "restart-" + LocalDateTime.now().format(STAMP) + "-"
                    + UUID.randomUUID().toString().substring(0, 4) + ".jar";
            Path backup = backupDir.resolve(backupName);
            long usable = Files.getFileStore(backupDir).getUsableSpace();
            if (usable < currentSize * 2 + 20L * 1024 * 1024) {
                throw new BizException("磁盘剩余空间不足，无法安全重启");
            }

            RestartSpec spec = buildRestartSpec(jarPath, backup, currentVersion);
            writeApplyConfig(spec);
            Files.copy(jarPath, updaterJar, StandardCopyOption.REPLACE_EXISTING);

            Map<String, String> state = new LinkedHashMap<>();
            state.put("status", "RESTARTING");
            state.put("message", "正在按当前 jar 重启站点");
            state.put("targetSha", currentSha);
            state.put("previousSha", currentSha);
            state.put("targetVersion", currentVersion);
            state.put("previousVersion", currentVersion);
            state.put("backup", backup.toString());
            state.put("mode", mode);
            state.put("updatedAt", LocalDateTime.now().format(TIME));
            writeState(state);

            launchUpdater();
            log.info("站点重启已启动，版本 {}，backup={}", currentVersion, backup);

            if ("standalone".equals(mode)) {
                scheduleExit(3);
            } else {
                monitorSupervisedReplacement();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "RESTARTING");
            result.put("targetVersion", currentVersion);
            result.put("previousVersion", currentVersion);
            result.put("instanceId", instanceId);
            result.put("restartMode", mode);
            return result;
        } catch (BizException e) {
            applying.set(false);
            throw e;
        } catch (Exception e) {
            applying.set(false);
            log.error("站点重启启动失败", e);
            writeFailure("重启启动失败：" + e.getMessage());
            throw new BizException("重启启动失败：" + e.getMessage());
        }
    }

    public synchronized void cancelPending() {
        if (applying.get()) {
            throw new BizException(HttpStatus.CONFLICT, "更新正在执行，不能取消");
        }
        deletePendingFiles();
        log.info("OTA 待更新 jar 已取消");
    }

    public StatusView status() {
        refreshCurrentJar(false);
        PendingInfo pendingInfo = null;
        Pending pending = loadPendingRaw();
        if (pending != null) {
            pendingInfo = new PendingInfo(pending.fileName(), pending.size(), pending.sha256(),
                    pending.version(), pending.uploadedAt());
        }
        return new StatusView(enabled, canAutoUpdate(), applying.get(), mode, modeNote,
                currentVersion, currentSha, currentSize, jarPath == null ? "" : jarPath.toString(),
                getPid(), instanceId, durationSeconds(), pendingInfo, lastUpdate());
    }

    // ---------- jar 识别 / 路径 ----------

    private Path resolveJarPath() {
        if (!configuredJarPath.isEmpty()) {
            try {
                Path p = Paths.get(configuredJarPath).toAbsolutePath().normalize();
                return Files.isRegularFile(p) ? p.toRealPath() : p;
            } catch (Exception e) {
                log.warn("app.ota.jar-path 无效: {}", configuredJarPath);
            }
        }
        // java -jar 启动时 java.class.path 就是当前 jar；Spring Boot 的嵌套类路径不能直接转 Path
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isBlank() && !classPath.contains(File.pathSeparator)) {
            try {
                Path p = Paths.get(classPath).toAbsolutePath().normalize();
                if (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".jar")) {
                    return p.toRealPath();
                }
            } catch (Exception e) {
                log.debug("java.class.path 定位失败: {}", e.getMessage());
            }
        }
        try {
            URI uri = OtaService.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            String raw = uri.toString();
            int bang = raw.indexOf('!');
            if (bang > 0) {
                raw = raw.substring(0, bang);
                if (raw.startsWith("jar:")) {
                    raw = raw.substring(4);
                }
                uri = URI.create(raw);
            }
            Path p = Paths.get(uri).toAbsolutePath().normalize();
            if (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".jar")) {
                return p.toRealPath();
            }
        } catch (Exception e) {
            log.debug("无法定位当前 jar: {}", e.getMessage());
        }
        return null;
    }

    private String detectMode(Path jar) {
        if (!enabled) {
            return "disabled";
        }
        if (jar == null || !Files.isRegularFile(jar)) {
            return "dev";
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (Files.exists(Paths.get("/.dockerenv"))) {
            return "docker";
        }
        if (!os.contains("win") && System.getenv("INVOCATION_ID") != null) {
            return "systemd";
        }
        return "standalone";
    }

    private String modeNote(String m) {
        return switch (m) {
            case "disabled" -> "OTA 更新已关闭（app.ota.enabled=false）";
            case "dev" -> "当前以开发目录方式运行（不是 jar），无法自替换；请用 java -jar blog.jar 启动";
            case "docker" -> "容器内更新只在当前容器生效，必须配置 restart: unless-stopped/always 才会自动拉起；重建容器会回退到镜像里的 jar，长期请重建镜像。";
            case "systemd" -> "当前由 systemd 管理：替换后由服务重启；请确保 Restart=always，systemd 模式不做自动回滚";
            default -> "";
        };
    }

    private void refreshCurrentJar(boolean force) {
        Path jar = jarPath;
        if (jar == null || !Files.isRegularFile(jar)) {
            currentSha = "";
            currentSize = 0;
            currentVersion = "未知";
            return;
        }
        try {
            long mod = Files.getLastModifiedTime(jar).toMillis();
            long size = Files.size(jar);
            if (force || mod != jarLastModified || size != currentSize) {
                currentSha = sha256(jar);
                currentSize = size;
                jarLastModified = mod;
                currentVersion = jarVersion(jar);
            }
        } catch (Exception e) {
            log.warn("读取当前 jar 信息失败: {}", e.getMessage());
        }
    }

    private static String jarVersion(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile(), false)) {
            Manifest mf = jf.getManifest();
            if (mf != null) {
                String v = mf.getMainAttributes().getValue("Implementation-Version");
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "未知";
    }

    private JarMeta inspectJar(Path path) throws IOException {
        try (JarFile jf = new JarFile(path.toFile(), true)) {
            Manifest mf = jf.getManifest();
            if (mf == null) {
                throw new IllegalArgumentException("缺少 META-INF/MANIFEST.MF");
            }
            String mainClass = mf.getMainAttributes().getValue("Main-Class");
            String startClass = mf.getMainAttributes().getValue("Start-Class");
            if (mainClass == null || !mainClass.startsWith("org.springframework.boot.loader")) {
                throw new IllegalArgumentException("不是 Spring Boot 可执行 jar（Main-Class 不匹配）");
            }
            if (startClass == null || !startClass.startsWith("com.deek.blog.")) {
                throw new IllegalArgumentException("不是本博客程序的 jar（Start-Class 不匹配）");
            }
            if (jf.getJarEntry("BOOT-INF/classes/application.yml") == null
                    && jf.getJarEntry("BOOT-INF/classes/application.properties") == null) {
                throw new IllegalArgumentException("缺少 Spring Boot 应用配置");
            }
            if (jf.getJarEntry("BOOT-INF/classes/com/deek/blog/BlogApplication.class") == null) {
                throw new IllegalArgumentException("缺少博客启动类");
            }
            String version = mf.getMainAttributes().getValue("Implementation-Version");
            if (version == null || version.isBlank()) {
                version = "未知";
            }
            return new JarMeta(sha256(path), version.trim());
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("不是有效的 jar 文件（zip 结构损坏）");
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ---------- 更新执行 ----------

    private RestartSpec buildRestartSpec(Pending pending, Path newJar, Path backup) {
        return buildRestartSpec(newJar, backup, pending.version());
    }

    private RestartSpec buildRestartSpec(Path newJar, Path backup, String targetVersion) {
        String os = System.getProperty("os.name", "").toLowerCase();
        Path javaBin = Paths.get(System.getProperty("java.home"), "bin",
                os.contains("win") ? "java.exe" : "java");
        String work = System.getProperty("user.dir");
        List<String> jvmArgs = new ArrayList<>(ManagementFactory.getRuntimeMXBean().getInputArguments());
        List<String> appArgs = currentApplicationArgs();
        return new RestartSpec(getPid(), instanceId, jarPath, newJar, backup,
                port, Paths.get(work).toAbsolutePath().normalize(), javaBin,
                jvmArgs, appArgs, mode, targetVersion);
    }

    private List<String> currentApplicationArgs() {
        String[] processArgs = ProcessHandle.current().info().arguments().orElse(new String[0]);
        for (int i = 0; i < processArgs.length; i++) {
            if ("-jar".equals(processArgs[i]) && i + 1 < processArgs.length) {
                return new ArrayList<>(Arrays.asList(processArgs).subList(Math.min(processArgs.length, i + 2), processArgs.length));
            }
        }
        String command = System.getProperty("sun.java.command", "");
        int space = command.indexOf(' ');
        if (space > 0) {
            return splitCommandLine(command.substring(space + 1));
        }
        return List.of();
    }

    private static List<String> splitCommandLine(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean escape = false;
        for (char c : s.toCharArray()) {
            if (escape) {
                cur.append(c);
                escape = false;
            } else if (c == '\\' && quote != '\'') {
                escape = true;
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private void writeApplyConfig(RestartSpec spec) throws IOException {
        Properties p = new Properties();
        p.setProperty("mode", spec.mode());
        p.setProperty("pid", Long.toString(spec.pid()));
        p.setProperty("oldInstanceId", spec.instanceId());
        p.setProperty("jarPath", spec.jarPath().toString());
        p.setProperty("newJar", spec.newJar().toString());
        p.setProperty("backup", spec.backup().toString());
        p.setProperty("port", Integer.toString(spec.port()));
        p.setProperty("workDir", spec.workDir().toString());
        p.setProperty("javaPath", spec.javaPath().toString());
        p.setProperty("appLog", appLog.toString());
        p.setProperty("updaterLog", updaterLog.toString());
        p.setProperty("stateFile", stateFile.toString());
        p.setProperty("targetVersion", spec.targetVersion());
        p.setProperty("healthTimeoutSeconds", Integer.toString(healthTimeoutSeconds));
        writeList(p, "jvmArg", spec.jvmArgs());
        writeList(p, "appArg", spec.appArgs());
        writeProperties(workDir.resolve("update.properties"), p);
    }

    private static void writeList(Properties p, String prefix, List<String> values) {
        p.setProperty(prefix + ".count", Integer.toString(values.size()));
        for (int i = 0; i < values.size(); i++) {
            p.setProperty(prefix + "." + i, values.get(i));
        }
    }

    private void launchUpdater() throws IOException {
        Path javaBin = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin.toString());
        cmd.add("-Dloader.main=com.deek.blog.ota.UpdaterMain");
        cmd.add("-cp");
        cmd.add(updaterJar.toString());
        cmd.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        cmd.add(workDir.resolve("update.properties").toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(updaterLog.toFile()));
        Process process = pb.start();
        log.info("OTA updater pid={}", process.pid());
    }

    private void scheduleExit(int seconds) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            log.info("OTA：主进程退出，等待 updater 替换并重启");
            System.exit(0);
        }, "ota-exit");
        t.setDaemon(true);
        t.start();
    }

    private void monitorSupervisedReplacement() {
        Thread t = new Thread(() -> {
            Instant deadline = Instant.now().plusSeconds(90);
            while (Instant.now().isBefore(deadline)) {
                Properties state = readProperties(stateFile);
                String status = state.getProperty("status", "");
                if ("REPLACED".equals(status) || "SUCCESS".equals(status)) {
                    log.info("OTA：jar 已替换，交由 {} 重启", mode);
                    // 非 0 退出码让 Restart=on-failure 也能拉起新版本
                    System.exit(75);
                    return;
                }
                if ("FAILED".equals(status)) {
                    applying.set(false);
                    log.warn("OTA：替换失败，保持当前进程运行");
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            applying.set(false);
            log.warn("OTA：等待替换结果超时");
        }, "ota-supervisor");
        t.setDaemon(true);
        t.start();
    }

    private void writeFailure(String message) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("status", "FAILED");
        state.put("message", message);
        state.put("updatedAt", LocalDateTime.now().format(TIME));
        try {
            writeState(state);
        } catch (IOException e) {
            log.warn("写入 OTA 失败状态失败: {}", e.getMessage());
        }
    }

    // ---------- 状态文件 ----------

    private void reconcileAfterStartup() {
        Properties state = readProperties(stateFile);
        if (state.isEmpty()) {
            return;
        }
        String status = state.getProperty("status", "");
        if ("ROLLED_BACK".equals(status) || "FAILED".equals(status)) {
            // 上次更新失败/已回滚，清掉残留待更新包，避免误点再次应用
            deletePendingFiles();
            return;
        }
        if (!"APPLYING".equals(status) && !"REPLACED".equals(status) && !"RESTARTING".equals(status)) {
            return;
        }
        String targetSha = state.getProperty("targetSha", "");
        String previousSha = state.getProperty("previousSha", "");
        try {
            if (!targetSha.isEmpty() && targetSha.equalsIgnoreCase(currentSha)) {
                state.setProperty("status", "SUCCESS");
                state.setProperty("message", "新版本已启动：" + currentVersion);
                state.setProperty("updatedAt", LocalDateTime.now().format(TIME));
                state.setProperty("pid", Long.toString(getPid()));
                writeProperties(stateFile, state);
                deletePendingFiles();
                log.info("OTA 更新成功：{}", currentVersion);
            } else if (!previousSha.isEmpty() && previousSha.equalsIgnoreCase(currentSha)) {
                state.setProperty("status", "ROLLED_BACK");
                state.setProperty("message", "新 jar 未能正常启动，已恢复旧版本");
                state.setProperty("updatedAt", LocalDateTime.now().format(TIME));
                writeProperties(stateFile, state);
                log.warn("OTA 更新已回滚，当前仍为 {}", currentVersion);
            } else {
                state.setProperty("status", "FAILED");
                state.setProperty("message", "重启后 jar 校验值与更新目标不一致");
                state.setProperty("updatedAt", LocalDateTime.now().format(TIME));
                writeProperties(stateFile, state);
            }
        } catch (IOException e) {
            log.warn("OTA 状态校准失败: {}", e.getMessage());
        }
    }

    private Map<String, Object> lastUpdate() {
        Properties p = readProperties(stateFile);
        if (p.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (String key : List.of("status", "message", "targetVersion", "previousVersion",
                "targetSha", "updatedAt", "backup", "mode", "pid")) {
            String value = p.getProperty(key);
            if (value != null && !value.isBlank()) {
                data.put(key, value);
            }
        }
        return data;
    }

    private void writeState(Map<String, String> values) throws IOException {
        Properties p = readProperties(stateFile);
        for (Map.Entry<String, String> e : values.entrySet()) {
            p.setProperty(e.getKey(), e.getValue());
        }
        writeProperties(stateFile, p);
    }

    private Pending loadPendingRaw() {
        Properties p = readProperties(pendingFile);
        if (p.isEmpty()) {
            return null;
        }
        String stored = p.getProperty("storedFile", "");
        String sha = p.getProperty("sha256", "");
        if (stored.isEmpty() || sha.isEmpty()) {
            return null;
        }
        Path file = stagingDir.resolve(stored).normalize();
        if (!file.startsWith(stagingDir) || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            long size = Long.parseLong(p.getProperty("size", "0"));
            return new Pending(stored, p.getProperty("fileName", "blog.jar"), size, sha,
                    p.getProperty("version", "未知"), p.getProperty("uploadedAt", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void cleanupPendingIfMissing() {
        if (loadPendingRaw() == null && Files.exists(pendingFile)) {
            try {
                Files.deleteIfExists(pendingFile);
            } catch (IOException ignored) {
            }
        }
    }

    private void deletePendingFiles() {
        Pending pending = loadPendingRaw();
        if (pending != null) {
            try {
                Files.deleteIfExists(stagingDir.resolve(pending.storedFile()));
            } catch (IOException ignored) {
            }
        }
        try {
            Files.deleteIfExists(pendingFile);
        } catch (IOException ignored) {
        }
    }

    private void pruneOldFiles() {
        try (Stream<Path> stream = Files.list(backupDir)) {
            List<Path> backups = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong((Path p) -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0;
                        }
                    }).reversed())
                    .toList();
            for (int i = 5; i < backups.size(); i++) {
                Files.deleteIfExists(backups.get(i));
            }
        } catch (IOException ignored) {
        }
        try (Stream<Path> stream = Files.list(stagingDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".part"))
                    .filter(p -> {
                        try {
                            return Duration.between(Files.getLastModifiedTime(p).toInstant(), Instant.now()).toHours() > 6;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private long durationSeconds() {
        return Duration.between(startedAt, Instant.now()).getSeconds();
    }

    // ---------- 文件工具 ----------

    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "blog.jar";
        }
        String base = Paths.get(name.replace('\\', '/')).getFileName().toString();
        base = base.replaceAll("[\\r\\n\\t]", "").trim();
        return base.isEmpty() ? "blog.jar" : base;
    }

    private static Properties readProperties(Path file) {
        Properties p = new Properties();
        if (!Files.isRegularFile(file)) {
            return p;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            log.debug("读取 {} 失败: {}", file, e.getMessage());
        }
        return p;
    }

    private static void writeProperties(Path file, Properties p) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "deek blog ota");
        }
        moveReplace(tmp, file);
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private record JarMeta(String sha256, String version) {
    }

    private record Pending(String storedFile, String fileName, long size, String sha256,
                           String version, String uploadedAt) {
    }

    private record RestartSpec(long pid, String instanceId, Path jarPath, Path newJar, Path backup,
                               int port, Path workDir, Path javaPath, List<String> jvmArgs,
                               List<String> appArgs, String mode, String targetVersion) {
    }

    public record PendingInfo(String fileName, long size, String sha256, String version, String uploadedAt) {
    }

    public record StatusView(boolean enabled, boolean canAutoUpdate, boolean applying,
                             String mode, String modeNote, String version, String sha256, long size,
                             String jarPath, long pid, String instanceId, long uptimeSeconds,
                             PendingInfo pending, Map<String, Object> lastUpdate) {
    }
}
