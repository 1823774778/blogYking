package com.deek.blog.ota;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 独立更新进程：等待旧服务退出，备份旧 jar，替换新 jar，启动新进程并做健康检查；失败时恢复旧包并重启。
 *
 * <p>这个类不依赖 Spring 容器，由当前 jar 的副本（updates/updater.jar）通过 Spring Boot
 * PropertiesLauncher 启动，因此即使原 blog.jar 被替换也能继续运行。
 */
public final class UpdaterMain {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private UpdaterMain() {
    }

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                log("缺少 update.properties 参数");
                return;
            }
            Config config = Config.load(Paths.get(args[0]));
            log("updater 启动：mode=" + config.mode() + ", oldPid=" + config.pid()
                    + ", targetVersion=" + config.targetVersion());

            if ("systemd".equals(config.mode()) || "docker".equals(config.mode())) {
                replaceForSupervisor(config);
                return;
            }
            runStandalone(config);
        } catch (Exception e) {
            log("updater 失败: " + e);
            e.printStackTrace();
        }
    }

    private static void replaceForSupervisor(Config c) throws Exception {
        backup(c);
        replace(c);
        MapState state = new MapState(c.stateFile());
        state.set("status", "REPLACED");
        state.set("message", "jar 已替换，等待外部进程管理器重启服务");
        state.set("updatedAt", LocalDateTime.now().format(TIME));
        writeState(c, state);
        log("jar 已替换，交由外部进程管理器重启");
    }

    private static void runStandalone(Config c) throws Exception {
        if (!waitForExit(c.pid(), 120)) {
            log("等待旧进程退出超时，尝试结束旧进程");
            ProcessHandle.of(c.pid()).ifPresent(ph -> {
                ph.destroy();
                try {
                    ph.onExit().get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    ph.destroyForcibly();
                }
            });
            if (isAlive(c.pid())) {
                writeFailure(c, "旧进程未能退出，更新中止");
                return;
            }
        }

        try {
            backup(c);
            replace(c);
        } catch (Exception e) {
            log("备份或替换失败: " + e.getMessage());
            writeFailure(c, "备份或替换失败：" + e.getMessage());
            // 旧 jar 还在原位置时，把旧服务拉起来，避免更新失败后站点下线
            startOld(c);
            return;
        }

        Process newProcess;
        try {
            newProcess = start(c, c.jarPath());
        } catch (Exception e) {
            log("新进程启动失败: " + e.getMessage());
            rollbackAndRestart(c, "新进程启动失败：" + e.getMessage());
            return;
        }
        long newPid = newProcess.pid();
        log("新进程已启动：pid=" + newPid);

        if (healthy(c, c.oldInstanceId(), c.healthTimeoutSeconds())) {
            MapState state = new MapState(c.stateFile());
            state.set("status", "SUCCESS");
            state.set("message", "新版本已启动：" + c.targetVersion());
            state.set("updatedAt", LocalDateTime.now().format(TIME));
            state.set("pid", Long.toString(newPid));
            writeState(c, state);
            log("更新成功，健康检查通过");
            return;
        }

        log("新进程健康检查失败，开始回滚");
        stop(newPid);
        rollbackAndRestart(c, "新 jar 未能正常启动，已恢复旧版本");
    }

    private static void rollbackAndRestart(Config c, String message) {
        try {
            if (Files.isRegularFile(c.backup())) {
                Files.copy(c.backup(), c.jarPath(), StandardCopyOption.REPLACE_EXISTING);
                log("已恢复旧 jar");
            }
        } catch (Exception e) {
            writeFailure(c, "回滚复制失败：" + e.getMessage());
            return;
        }
        Process oldProcess = startOld(c);
        MapState state = new MapState(c.stateFile());
        state.set("status", "ROLLED_BACK");
        state.set("message", message);
        state.set("updatedAt", LocalDateTime.now().format(TIME));
        state.set("pid", oldProcess == null ? "" : Long.toString(oldProcess.pid()));
        try {
            writeState(c, state);
        } catch (IOException e) {
            writeFailure(c, "回滚状态写入失败：" + e.getMessage());
        }
        log("已回滚并重新启动旧版本");
    }

    private static Process startOld(Config c) {
        try {
            Process p = start(c, c.jarPath());
            log("旧版本已重新启动：pid=" + p.pid());
            return p;
        } catch (Exception e) {
            writeFailure(c, "旧版本重启失败：" + e.getMessage());
            log("旧版本重启失败: " + e.getMessage());
            return null;
        }
    }

    private static Process start(Config c, Path jar) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(c.javaPath().toString());
        cmd.addAll(c.jvmArgs());
        cmd.add("-jar");
        cmd.add(jar.toString());
        cmd.addAll(c.appArgs());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(c.workDir().toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(c.appLog().toFile()));
        return pb.start();
    }

    private static boolean healthy(Config c, String oldInstanceId, int timeoutSeconds) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        Instant deadline = Instant.now().plusSeconds(Math.max(10, timeoutSeconds));
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + c.port() + "/api/system/version"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    String body = resp.body();
                    String instance = extract(body, "instanceId");
                    if (instance.isBlank() || !instance.equals(oldInstanceId)) {
                        log("健康检查通过，instanceId=" + instance);
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // 服务尚未启动，继续等待
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static String extract(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int index = json.indexOf(needle);
        if (index < 0) {
            return "";
        }
        int start = index + needle.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "" : json.substring(start, end);
    }

    private static void backup(Config c) throws IOException {
        Files.createDirectories(c.backup().getParent());
        Files.copy(c.jarPath(), c.backup(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        log("已备份旧 jar：" + c.backup());
    }

    private static void replace(Config c) throws IOException {
        Path jar = c.jarPath();
        Path temp = jar.resolveSibling(jar.getFileName() + ".ota-new");
        Files.copy(c.newJar(), temp, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temp, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        log("已替换 jar：" + jar);
    }

    private static boolean waitForExit(long pid, int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            if (!isAlive(pid)) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isAlive(pid);
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void stop(long pid) {
        ProcessHandle.of(pid).ifPresent(ph -> {
            ph.destroy();
            try {
                ph.onExit().get(8, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                ph.destroyForcibly();
            }
        });
    }

    private static void writeFailure(Config c, String message) {
        try {
            MapState state = new MapState(c.stateFile());
            state.set("status", "FAILED");
            state.set("message", message);
            state.set("updatedAt", LocalDateTime.now().format(TIME));
            writeState(c, state);
        } catch (Exception ignored) {
        }
        log("更新失败：" + message);
    }

    private static void writeState(Config c, MapState state) throws IOException {
        Properties p = readProperties(c.stateFile());
        for (String key : state.keys()) {
            p.setProperty(key, state.get(key));
        }
        Files.createDirectories(c.stateFile().getParent());
        Path tmp = c.stateFile().resolveSibling(c.stateFile().getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "deek blog ota");
        }
        try {
            Files.move(tmp, c.stateFile(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, c.stateFile(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Properties readProperties(Path file) {
        Properties p = new Properties();
        if (!Files.isRegularFile(file)) {
            return p;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException ignored) {
        }
        return p;
    }

    private static void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIME) + "] " + message);
    }

    private record Config(String mode, long pid, String oldInstanceId, Path jarPath, Path newJar,
                          Path backup, int port, Path workDir, Path javaPath, List<String> jvmArgs,
                          List<String> appArgs, Path stateFile, Path appLog, Path updaterLog,
                          String targetVersion, int healthTimeoutSeconds) {

        static Config load(Path file) throws IOException {
            Properties p = readProperties(file);
            return new Config(
                    p.getProperty("mode", "standalone"),
                    Long.parseLong(p.getProperty("pid", "0")),
                    p.getProperty("oldInstanceId", ""),
                    Paths.get(p.getProperty("jarPath")),
                    Paths.get(p.getProperty("newJar")),
                    Paths.get(p.getProperty("backup")),
                    Integer.parseInt(p.getProperty("port", "8080")),
                    Paths.get(p.getProperty("workDir")),
                    Paths.get(p.getProperty("javaPath")),
                    readList(p, "jvmArg"),
                    readList(p, "appArg"),
                    Paths.get(p.getProperty("stateFile")),
                    Paths.get(p.getProperty("appLog")),
                    Paths.get(p.getProperty("updaterLog")),
                    p.getProperty("targetVersion", "未知"),
                    Integer.parseInt(p.getProperty("healthTimeoutSeconds", "45"))
            );
        }

        private static List<String> readList(Properties p, String prefix) {
            int count = Integer.parseInt(p.getProperty(prefix + ".count", "0"));
            List<String> list = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                list.add(p.getProperty(prefix + "." + i, ""));
            }
            return list;
        }
    }

    private static final class MapState {
        private final java.util.Map<String, String> values = new java.util.LinkedHashMap<>();

        MapState(Path ignored) {
        }

        void set(String key, String value) {
            if (value != null) {
                values.put(key, value);
            }
        }

        String get(String key) {
            return values.get(key);
        }

        java.util.Set<String> keys() {
            return values.keySet();
        }
    }
}
