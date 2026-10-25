package wtm.app;

import wtm.config.Config;
import wtm.proc.ServerManager;
import wtm.snap.Integrity;
import wtm.snap.SnapshotEngine;
import wtm.web.Auth;
import wtm.web.Panel;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 应用装配：进程管理 + 快照引擎 + 面板 + 调度器。
 *
 * 所有"会动服务器"的操作（备份、回档、启停）都串行跑在同一条 ops 线程上，
 * 用一把逻辑锁保证任意时刻只有一个生命周期任务，避免启停与备份互相踩踏。
 */
public final class App {

    private final Config cfg;
    private final ServerManager server;
    private final SnapshotEngine engine;
    private final Jobs jobs = new Jobs();

    private final ExecutorService ops = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wtm-ops");
        t.setDaemon(false);
        return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wtm-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    private final String secret;
    private Panel panel;

    private volatile boolean busy;
    private volatile Jobs.Job activeJob;
    private volatile SnapshotEngine.Snapshot lastSnapshot;
    private volatile List<Integrity.Issue> lastIssues = List.of();

    public App(Config cfg) throws IOException {
        this.cfg = cfg;
        Files.createDirectories(cfg.dataDir);
        this.server = new ServerManager(cfg);
        this.engine = new SnapshotEngine(cfg.serverDir, cfg.dataDir, cfg.worlds);
        this.secret = loadOrCreateSecret();
    }

    private String loadOrCreateSecret() throws IOException {
        Path file = cfg.dataDir.resolve("secret.key");
        if (Files.isRegularFile(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (existing.length() >= 32) {
                return existing;
            }
        }
        String generated = Auth.randomToken(32);
        Files.writeString(file, generated + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return generated;
    }

    // ------------------------------------------------------------------ 启停

    public void run() throws IOException {
        panel = new Panel(this);
        panel.start();
        System.out.println("[wtm] 面板已启动: http://" + cfg.listen + ":" + cfg.port);
        System.out.println("[wtm] 服务端目录: " + cfg.serverDir);
        System.out.println("[wtm] 快照目录:   " + engine.snapRoot());

        if (cfg.autostart) {
            Thread boot = new Thread(() -> {
                try {
                    server.start();
                } catch (Exception e) {
                    System.err.println("[wtm] 自动启动失败: " + e.getMessage());
                }
            }, "wtm-autostart");
            boot.setDaemon(true);
            boot.start();
        }

        scheduleNextBackup();
        // 每 5 分钟问一次 TPS：原版/NeoForge 的 tick query 输出 "Average time per tick"
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (server.isRunning()) {
                    server.command("tick query");
                }
            } catch (Exception ignored) {
                // 服务端正在启停时忽略
            }
        }, 60, 300, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "wtm-shutdown"));

        try {
            stopLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void shutdown() {
        System.out.println("[wtm] 收到退出信号，正在优雅停止服务端…");
        try {
            if (server.isRunning()) {
                server.stop();
            }
        } catch (RuntimeException ignored) {
            // 退出路径上不再抛出
        }
        if (panel != null) {
            panel.stop();
        }
        scheduler.shutdownNow();
        ops.shutdownNow();
        stopLatch.countDown();
    }

    // ------------------------------------------------------------------ 备份

    public Jobs.Job submitBackup(boolean manual, boolean force) {
        Jobs.Job job = jobs.create(manual ? "backup-manual" : "backup-auto");
        ops.submit(() -> runBackup(job, manual, force));
        return job;
    }

    private void runBackup(Jobs.Job job, boolean manual, boolean force) {
        busy = true;
        activeJob = job;
        try {
            if (!force) {
                job.stage("检查在线人数", 3);
                int players = probePlayers();
                if (players > 0) {
                    long deadline = System.currentTimeMillis() + cfg.waitIdleMinutes * 60_000L;
                    while (players > 0 && System.currentTimeMillis() < deadline) {
                        job.stage("等待空服（在线 " + players + " 人）", 6);
                        sleep(30_000);
                        players = probePlayers();
                    }
                    if (players > 0) {
                        job.skip("有 " + players + " 名玩家在线，已跳过本次自动备份");
                        return;
                    }
                }
            } else {
                job.stage(manual ? "手动备份" : "强制备份", 5);
            }

            boolean wasRunning = server.isRunning();
            if (wasRunning) {
                job.stage("停止服务端", 15);
                if (!server.stop()) {
                    throw new IllegalStateException("服务端未能停止，已中止备份（未写入任何快照）");
                }
            }
            try {
                job.stage("创建快照", 45);
                SnapshotEngine.Snapshot snap = engine.create(manual ? "manual" : "auto");
                lastSnapshot = snap;
                job.stage("清理旧快照", 75);
                engine.prune(cfg.keepLast, cfg.keepDaily);
            } finally {
                if (wasRunning) {
                    job.stage("启动服务端", 90);
                    server.start();
                    if (!waitReady(180)) {
                        throw new IllegalStateException("备份已完成，但服务端启动超时，请查看日志");
                    }
                }
            }
            job.done();
        } catch (Exception e) {
            job.fail(message(e));
        } finally {
            busy = false;
        }
    }

    // ------------------------------------------------------------------ 回档

    public Jobs.Job submitRestore(String snapshotId) {
        Jobs.Job job = jobs.create("restore");
        ops.submit(() -> runRestore(job, snapshotId));
        return job;
    }

    private void runRestore(Jobs.Job job, String snapshotId) {
        busy = true;
        activeJob = job;
        try {
            job.stage("读取快照信息", 5);
            engine.read(snapshotId);

            boolean wasRunning = server.isRunning();
            if (wasRunning) {
                job.stage("停止服务端", 12);
                if (!server.stop()) {
                    throw new IllegalStateException("服务端未能停止，回档已取消（世界未改动）");
                }
            }

            job.stage("备份当前存档（回档前）", 25);
            engine.create("pre_restore");

            job.stage("回滚世界文件", 55);
            engine.restore(snapshotId);

            job.stage("启动服务端", 80);
            server.start();
            if (!waitReady(180)) {
                throw new IllegalStateException("回档已完成，但服务端未在 180 秒内就绪，请查看日志");
            }

            job.stage("校验世界文件", 92);
            List<Integrity.Issue> issues = verifyWorlds();
            lastIssues = issues;
            if (!issues.isEmpty()) {
                throw new IllegalStateException("发现 " + issues.size()
                        + " 处世界文件异常，建议回档到 pre_restore 快照");
            }
            job.done();
        } catch (Exception e) {
            logError("restore", e);
            job.fail(message(e));
            if (!server.isRunning()) {
                try {
                    server.start();
                } catch (Exception bootError) {
                    logError("restore-restart", bootError);
                    job.fail(message(e) + "；且自动重启失败: " + message(bootError));
                }
            }
        } finally {
            busy = false;
        }
    }

    // ------------------------------------------------------------------ 电源

    public Jobs.Job submitPower(String action) {
        Jobs.Job job = jobs.create("power-" + action);
        ops.submit(() -> {
            busy = true;
            activeJob = job;
            try {
                switch (action) {
                    case "start" -> {
                        job.stage("启动中", 30);
                        if (!server.isRunning()) {
                            server.start();
                            if (!waitReady(180)) {
                                throw new IllegalStateException("启动超时，请查看控制台输出");
                            }
                        }
                        job.done();
                    }
                    case "stop" -> {
                        job.stage("停止中", 50);
                        if (!server.stop()) {
                            throw new IllegalStateException("服务端未能停止");
                        }
                        job.done();
                    }
                    case "restart" -> {
                        job.stage("重启中", 30);
                        server.stop();
                        server.start();
                        if (!waitReady(180)) {
                            throw new IllegalStateException("重启超时，请查看控制台输出");
                        }
                        job.done();
                    }
                    default -> throw new IllegalArgumentException("未知动作: " + action);
                }
            } catch (Exception e) {
                job.fail(message(e));
            } finally {
                busy = false;
            }
        });
        return job;
    }

    // ------------------------------------------------------------------ 调度

    private void scheduleNextBackup() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().atTime(cfg.backupAt);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long delayMs = Math.max(5_000L, Duration.between(now, next).toMillis());
        scheduler.schedule(() -> {
            try {
                submitBackup(false, false);
            } finally {
                scheduleNextBackup();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        System.out.println("[wtm] 下次自动备份: " + next);
    }

    // ------------------------------------------------------------------ 工具

    public int probePlayers() {
        if (!server.isRunning()) {
            return 0;
        }
        try {
            server.command("list");
        } catch (IOException e) {
            return -1;
        }
        sleep(400);
        return server.onlinePlayers();
    }

    private boolean waitReady(int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (server.state() == ServerManager.State.RUNNING) {
                return true;
            }
            if (server.state() == ServerManager.State.STOPPED) {
                return false;
            }
            sleep(200);
        }
        return false;
    }

    public List<Integrity.Issue> verifyWorlds() {
        List<Integrity.Issue> issues = new ArrayList<>();
        for (String world : cfg.worlds) {
            Path dir = cfg.serverDir.resolve(world);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try {
                issues.addAll(Integrity.scanWorld(dir, world));
                if (!Integrity.levelDatLooksValid(dir)) {
                    issues.add(new Integrity.Issue(world, "level.dat", -1, -1, "level.dat 缺失或不是 gzip"));
                }
            } catch (IOException e) {
                issues.add(new Integrity.Issue(world, "region", -1, -1, "扫描失败: " + e.getMessage()));
            }
        }
        return issues;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            m = e.getClass().getSimpleName();
        }
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            String cm = cause.getMessage();
            m = m + " (" + cause.getClass().getSimpleName() + (cm == null ? "" : ": " + cm) + ")";
        }
        return m;
    }

    /** 把异常与堆栈追加到 data/wtm-errors.log，便于事后定位（系统 journal 可能不可用）。 */
    private void logError(String stage, Exception e) {
        try {
            Path file = cfg.dataDir.resolve("wtm-errors.log");
            String head = "\n===== " + Instant.now() + " [" + stage + "] =====\n";
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            Files.writeString(file, head + sw, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 日志失败不影响主流程
        }
    }

    public long diskFree() {
        try {
            return Files.getFileStore(cfg.dataDir).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 访问器

    public Config config() {
        return cfg;
    }

    public ServerManager server() {
        return server;
    }

    public SnapshotEngine engine() {
        return engine;
    }

    public Jobs jobs() {
        return jobs;
    }

    public String secret() {
        return secret;
    }

    public String appName() {
        return "WTM 存档保险箱";
    }

    public boolean busy() {
        return busy;
    }

    public Jobs.Job activeJob() {
        return activeJob;
    }

    public SnapshotEngine.Snapshot lastSnapshot() {
        return lastSnapshot;
    }

    public List<Integrity.Issue> lastIssues() {
        return lastIssues;
    }
}
