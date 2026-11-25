package com.deek.blog.service;

import com.deek.blog.common.BizException;
import com.deek.blog.entity.OtaDownloadTask;
import com.deek.blog.repository.OtaDownloadTaskRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GitHub Release 下载任务。进度写入数据库，后台切换路由或刷新页面后仍能接着轮询。
 * 同一时间只允许一个下载任务，下载完成后再进入原有待更新校验区。
 */
@Service
public class OtaDownloadService {

    private static final Logger log = LoggerFactory.getLogger(OtaDownloadService.class);
    private static final long MAX_JAR_BYTES = 240L * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OtaDownloadTaskRepository repository;
    private final OtaService otaService;
    private final SiteConfigService siteConfigService;

    public OtaDownloadService(OtaDownloadTaskRepository repository,
                              OtaService otaService,
                              SiteConfigService siteConfigService) {
        this.repository = repository;
        this.otaService = otaService;
        this.siteConfigService = siteConfigService;
    }

    @PostConstruct
    public void failInterruptedJobs() {
        List<OtaDownloadTask> running = repository.findByStatus("RUNNING");
        if (running.isEmpty()) {
            return;
        }
        for (OtaDownloadTask task : running) {
            task.setStatus("FAILED");
            task.setMessage("服务已重启，下载任务中断，请重新下载");
            task.setUpdatedAt(LocalDateTime.now());
            repository.save(task);
        }
        log.warn("已将 {} 个中断的 OTA 下载任务标记为失败", running.size());
    }

    public synchronized DownloadJobView start(GithubReleaseService.ReleaseInfo release, String mode) {
        if (release == null) {
            throw new BizException("没有找到要下载的 Release");
        }
        if (release.jarAsset() == null || release.jarAsset().downloadUrl() == null
                || release.jarAsset().downloadUrl().isBlank()) {
            throw new BizException("这个 Release 没有可下载的 jar 资产");
        }
        OtaService.StatusView status = otaService.status();
        if (status.applying()) {
            throw new BizException("当前正在安装更新，暂时不能下载新版本");
        }
        OtaDownloadTask current = repository.findFirstByOrderByCreatedAtDesc();
        if (current != null && "RUNNING".equals(current.getStatus())) {
            throw new BizException("已有版本正在下载，请等待当前任务完成");
        }

        URI target = OtaService.checkedGithubAssetUri(release.jarAsset().downloadUrl());
        String proxyPrefix = normalizeProxyPrefix(
                siteConfigService.get().getOrDefault("otaDownloadProxyPrefix", ""));
        String normalizedMode = "ROLLBACK".equalsIgnoreCase(mode) ? "ROLLBACK" : "UPDATE";

        OtaDownloadTask task = new OtaDownloadTask();
        task.setId(UUID.randomUUID().toString());
        task.setTag(release.tag());
        task.setVersion(release.version());
        task.setFileName(release.jarAsset().name());
        task.setDownloadUrl(target.toString());
        task.setMode(normalizedMode);
        task.setStatus("RUNNING");
        task.setTotalBytes(Math.max(0, release.jarAsset().size()));
        task.setDownloadedBytes(0);
        task.setMessage(proxyPrefix.isBlank() ? "正在直连 GitHub 下载" : "正在通过代理下载");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        repository.save(task);
        pruneTasks();

        Thread worker = new Thread(() -> runDownload(task, target, proxyPrefix,
                release.jarAsset().size()), "ota-download-" + task.getId().substring(0, 8));
        worker.setDaemon(true);
        worker.start();
        return view(task);
    }

    public DownloadJobView status(String jobId) {
        OtaDownloadTask task;
        if (jobId == null || jobId.isBlank()) {
            task = repository.findFirstByOrderByCreatedAtDesc();
        } else {
            task = repository.findById(jobId.trim()).orElse(null);
        }
        return task == null ? null : view(task);
    }

    /** 清理已结束的下载任务记录；运行中的任务必须先结束，避免下载线程继续写入已删除任务。 */
    public synchronized Map<String, Object> clearHistory() {
        OtaDownloadTask current = repository.findFirstByOrderByCreatedAtDesc();
        if (current != null && "RUNNING".equals(current.getStatus())) {
            throw new BizException("下载任务正在进行，请等待完成或失败后再清理");
        }
        long count = repository.count();
        repository.deleteAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removedTasks", count);
        return result;
    }

    /** 更新成功后清空下载任务历史；失败记录也一并移除。 */
    public synchronized Map<String, Object> clearHistoryAfterUpdate() {
        long count = repository.count();
        repository.deleteAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removedTasks", count);
        return result;
    }

    private void runDownload(OtaDownloadTask task, URI target, String proxyPrefix, long expectedBytes) {
        Path temp = null;
        try {
            URI requestUri = proxiedUri(proxyPrefix, target);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(12))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(requestUri)
                    .timeout(Duration.ofMinutes(10))
                    .header("Accept", "application/octet-stream")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "deek-blog-ota/1.1")
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("下载服务返回 HTTP " + response.statusCode());
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > MAX_JAR_BYTES) {
                throw new BizException("Release 中的 jar 超过 240MB");
            }
            long total = declared > 0 ? declared : Math.max(0, expectedBytes);
            task.setTotalBytes(total);
            task.setMessage("正在下载 " + task.getFileName());
            repository.save(task);

            temp = otaService.createDownloadPart("release-");
            long copied = 0;
            long lastSaved = System.currentTimeMillis();
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    copied += n;
                    if (copied > MAX_JAR_BYTES) {
                        throw new BizException("Release 中的 jar 超过 240MB");
                    }
                    out.write(buffer, 0, n);
                    long now = System.currentTimeMillis();
                    if (now - lastSaved >= 450) {
                        task.setDownloadedBytes(copied);
                        task.setUpdatedAt(LocalDateTime.now());
                        repository.save(task);
                        lastSaved = now;
                    }
                }
            }
            if (copied <= 0) {
                throw new BizException("下载得到空文件");
            }
            task.setDownloadedBytes(copied);
            task.setTotalBytes(copied);
            task.setMessage("下载完成，正在校验 jar");
            task.setUpdatedAt(LocalDateTime.now());
            repository.save(task);

            OtaService.PendingInfo pending = otaService.stageDownloadedJar(
                    temp, task.getFileName(), copied,
                    "ROLLBACK".equals(task.getMode()) ? "回档下载" : "GitHub 在线下载");
            temp = null;

            task.setStatus("SUCCESS");
            task.setVersion(pending.version());
            task.setSha256(pending.sha256());
            task.setDownloadedBytes(pending.size());
            task.setTotalBytes(pending.size());
            task.setMessage("下载并校验通过，可安装 v" + pending.version());
            task.setUpdatedAt(LocalDateTime.now());
            repository.save(task);
            log.info("OTA 下载任务完成：task={}, version={}, sha={}",
                    task.getId(), task.getVersion(), task.getSha256());
        } catch (BizException e) {
            fail(task, e.getMessage());
        } catch (Exception e) {
            log.warn("OTA 下载任务失败：{}", e.getMessage(), e);
            fail(task, "下载失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void fail(OtaDownloadTask task, String message) {
        task.setStatus("FAILED");
        task.setMessage(message == null || message.isBlank() ? "下载失败" : message);
        task.setUpdatedAt(LocalDateTime.now());
        repository.save(task);
    }

    private void pruneTasks() {
        List<OtaDownloadTask> tasks = repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        for (int i = 20; i < tasks.size(); i++) {
            repository.deleteById(tasks.get(i).getId());
        }
    }

    private DownloadJobView view(OtaDownloadTask task) {
        long total = task.getTotalBytes();
        long done = task.getDownloadedBytes();
        int percent = total > 0 ? (int) Math.max(0, Math.min(100, done * 100 / total)) : 0;
        return new DownloadJobView(
                task.getId(),
                task.getMode(),
                task.getStatus(),
                task.getTag(),
                task.getVersion(),
                task.getFileName(),
                total,
                done,
                percent,
                task.getMessage(),
                task.getSha256(),
                task.getCreatedAt() == null ? "" : task.getCreatedAt().format(TIME),
                task.getUpdatedAt() == null ? "" : task.getUpdatedAt().format(TIME)
        );
    }

    private static URI proxiedUri(String proxyPrefix, URI target) {
        if (proxyPrefix == null || proxyPrefix.isBlank()) {
            return target;
        }
        try {
            return URI.create(proxyPrefix + target);
        } catch (Exception e) {
            throw new BizException("代理前缀无效：" + e.getMessage());
        }
    }

    /** 留空直连；协议缺省时补 https://，并补齐末尾斜杠。 */
    static String normalizeProxyPrefix(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!value.matches("(?i)^http(s)?://.*")) {
            value = "https://" + value;
        }
        if (!value.endsWith("/")) {
            value = value + "/";
        }
        try {
            URI uri = URI.create(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("只支持 http/https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("缺少域名");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException("代理前缀不能包含账号密码");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("代理前缀无效：" + e.getMessage());
        }
        if (value.length() > 500) {
            throw new BizException("代理前缀过长");
        }
        return value;
    }

    public record DownloadJobView(String id, String mode, String status, String tag, String version,
                                  String fileName, long totalBytes, long downloadedBytes,
                                  int percent, String message, String sha256,
                                  String createdAt, String updatedAt) {
    }
}
