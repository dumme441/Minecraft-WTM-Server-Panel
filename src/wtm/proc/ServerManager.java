package wtm.proc;

import wtm.config.Config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端进程管理者：起停、日志环形缓冲、指令转发、就绪判定。
 *
 * 设计要点：
 * - 本进程是唯一入口，子进程与它同生共死（systemd Restart=always 兜底），避免孤儿进程。
 * - 所有输出先进环形缓冲，再分发给订阅者；面板断线不会拖住服务端。
 */
public final class ServerManager {

    public enum State { STOPPED, STARTING, RUNNING, STOPPING }

    private static final int RING_MAX = 1000;
    private static final Pattern PLAYERS = Pattern.compile("There are (\\d+) of a max(?: of)? (\\d+) players online");
    private static final Pattern TPS_AVG = Pattern.compile("Average TPS: ([\\d.]+)");
    private static final Pattern TPS_TRIPLE = Pattern.compile("TPS from last 1m, 5m, 15m: ([\\d.]+)");
    /** 原版 / NeoForge 的 /tick query：Average time per tick: 1.7ms (Target: 50.0ms) */
    private static final Pattern TICK_MS = Pattern.compile("Average time per tick:\\s*([\\d.]+)\\s*ms");
    private static final Pattern JOIN = Pattern.compile(": ([A-Za-z0-9_]{3,16}) joined the game");
    private static final Pattern LEAVE = Pattern.compile(": ([A-Za-z0-9_]{3,16}) left the game");

    private final Config cfg;
    private final Path logFile;
    private final Deque<String> ring = new ArrayDeque<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private volatile State state = State.STOPPED;
    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile Instant startedAt;
    private volatile int onlinePlayers = -1;
    private volatile int maxPlayers = -1;
    private volatile double tps = Double.NaN;
    /** 由 join/leave 日志维护的在线玩家集合，避免为了统计而反复执行 list 弄脏日志。 */
    private final Set<String> onlineNames = ConcurrentHashMap.newKeySet();

    public ServerManager(Config cfg) throws IOException {
        this.cfg = cfg;
        this.logFile = cfg.dataDir.resolve("server-console.log");
        Files.createDirectories(cfg.dataDir);
    }

    // ------------------------------------------------------------------ 生命周期

    public void start() throws IOException {
        synchronized (lock) {
            if (state == State.RUNNING || state == State.STARTING) {
                throw new IllegalStateException("服务端已在运行");
            }
            ProcessBuilder pb = new ProcessBuilder(cfg.commandLine())
                    .directory(cfg.serverDir.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            this.process = p;
            this.stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            this.state = State.STARTING;
            this.startedAt = null;
            this.onlinePlayers = -1;
            this.tps = Double.NaN;
            this.onlineNames.clear();
            emit("[wtm] 启动服务端: " + String.join(" ", cfg.commandLine()));

            CountDownLatch ready = new CountDownLatch(1);
            Thread pump = new Thread(() -> pump(p, ready), "wtm-stdout");
            pump.setDaemon(true);
            pump.start();

            Thread waiter = new Thread(() -> awaitReady(ready), "wtm-ready");
            waiter.setDaemon(true);
            waiter.start();
        }
    }

    private void awaitReady(CountDownLatch ready) {
        boolean ok;
        try {
            ok = ready.await(cfg.readyTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (lock) {
            if (state != State.STARTING) {
                return;
            }
            state = State.RUNNING;
            startedAt = Instant.now();
        }
        emit(ok ? "[wtm] 服务端已就绪" : "[wtm] 超时未检测到 Done 标记，按已启动处理（请检查日志）");
    }

    private void awaitReadyFallback() {
        awaitReady(new CountDownLatch(0));
    }

    private void pump(Process p, CountDownLatch ready) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                append(line);
                parse(line);
                if (line.contains("Done (")) {
                    ready.countDown();
                }
            }
        } catch (IOException e) {
            append("[wtm] 读取服务端输出失败: " + e.getMessage());
        } finally {
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (lock) {
                state = State.STOPPED;
                startedAt = null;
                process = null;
                stdin = null;
            }
            ready.countDown();
            append("[wtm] 服务端进程已退出（exit=" + p.exitValue() + "）");
        }
    }

    /** 优雅停止：先发 stop，超时降级 SIGTERM，再超时强杀。返回是否已退出。 */
    public boolean stop() {
        Process p;
        synchronized (lock) {
            p = process;
            if (p == null || !p.isAlive()) {
                state = State.STOPPED;
                return true;
            }
            state = State.STOPPING;
        }
        try {
            writeStdin("stop");
        } catch (IOException ignored) {
            // 进程可能已在退出路径上
        }
        boolean exited = waitFor(p, cfg.stopTimeout);
        if (!exited) {
            emit("[wtm] 优雅停止超时，发送 SIGTERM");
            p.destroy();
            exited = waitFor(p, Duration.ofSeconds(10));
        }
        if (!exited) {
            emit("[wtm] 仍未退出，强制结束");
            p.destroyForcibly();
            exited = waitFor(p, Duration.ofSeconds(5));
        }
        return exited;
    }

    private static boolean waitFor(Process p, Duration timeout) {
        try {
            return p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------ 指令

    public void command(String line) throws IOException {
        String trimmed = line == null ? "" : line.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        if (state != State.RUNNING && state != State.STARTING && state != State.STOPPING) {
            throw new IllegalStateException("服务端未运行");
        }
        writeStdin(trimmed);
        append("[→] " + trimmed);
    }

    private void writeStdin(String line) throws IOException {
        BufferedWriter w = stdin;
        if (w == null) {
            throw new IOException("stdin 不可用");
        }
        synchronized (lock) {
            w.write(line);
            w.write('\n');
            w.flush();
        }
    }

    public void flush() throws IOException {
        command("save-all flush");
    }

    // ------------------------------------------------------------------ 输出

    private void append(String line) {
        String stamped = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + line;
        synchronized (ring) {
            ring.addLast(stamped);
            while (ring.size() > RING_MAX) {
                ring.removeFirst();
            }
        }
        writeLog(stamped);
        for (Consumer<String> l : listeners) {
            try {
                l.accept(stamped);
            } catch (RuntimeException ignored) {
                // 单个订阅者异常不能影响服务端
            }
        }
    }

    private void emit(String line) {
        append("[wtm] " + line.replaceFirst("^\\[wtm\\] ", ""));
    }

    private void writeLog(String line) {
        try {
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 日志写失败不阻塞主流程
        }
    }

    public List<String> history() {
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    public void subscribe(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void unsubscribe(Consumer<String> listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ 解析

    private void parse(String line) {
        Matcher p = PLAYERS.matcher(line);
        if (p.find()) {
            onlinePlayers = Integer.parseInt(p.group(1));
            maxPlayers = Integer.parseInt(p.group(2));
        }
        Matcher j = JOIN.matcher(line);
        if (j.find()) {
            onlineNames.add(j.group(1));
            onlinePlayers = onlineNames.size();
        }
        Matcher l = LEAVE.matcher(line);
        if (l.find()) {
            onlineNames.remove(l.group(1));
            onlinePlayers = onlineNames.size();
        }
        Matcher a = TPS_AVG.matcher(line);
        if (a.find()) {
            tps = safeDouble(a.group(1));
        }
        Matcher t = TPS_TRIPLE.matcher(line);
        if (t.find()) {
            tps = safeDouble(t.group(1));
        }
        // 原版/NeoForge：用每 tick 耗时换算 TPS，并封顶 20
        Matcher ms = TICK_MS.matcher(line);
        if (ms.find()) {
            double perTick = safeDouble(ms.group(1));
            if (perTick > 0) {
                tps = Math.min(20.0, 1000.0 / perTick);
            }
        }
    }

    private static double safeDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ------------------------------------------------------------------ 状态

    public State state() {
        return state;
    }

    public boolean isRunning() {
        State s = state;
        return s == State.RUNNING || s == State.STARTING || s == State.STOPPING;
    }

    public int onlinePlayers() {
        return onlinePlayers;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public double tps() {
        return tps;
    }

    public long uptimeSeconds() {
        Instant s = startedAt;
        return s == null ? -1 : Duration.between(s, Instant.now()).toSeconds();
    }

    public Path logFile() {
        return logFile;
    }
}
