package wtm.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import wtm.app.App;
import wtm.app.Jobs;
import wtm.config.Config;
import wtm.proc.ServerManager;
import wtm.snap.SnapshotEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 极简面板：一个页面 + 一小组 JSON 接口。监听地址默认仅 127.0.0.1。 */
public final class Panel {

    private static final String COOKIE_SESSION = "wtm_sess";
    private static final String COOKIE_CSRF = "wtm_csrf";
    private static final long SESSION_TTL_MS = 7L * 24 * 3600 * 1000;
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final App app;
    private final HttpServer server;
    /** ip -> {失败次数, 锁定截止时间} */
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();

    private volatile boolean closed;

    public Panel(App app) throws IOException {
        this.app = app;
        Config cfg = app.config();
        this.server = HttpServer.create(new InetSocketAddress(cfg.listen, cfg.port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        closed = true;
        server.stop(0);
    }

    // ------------------------------------------------------------------ 路由

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            if ("/".equals(path)) {
                send(ex, 200, "text/html; charset=utf-8", Page.html(app.appName()));
                return;
            }
            if (!path.startsWith("/api/")) {
                send(ex, 404, "text/plain; charset=utf-8", "not found");
                return;
            }
            if ("/api/login".equals(path) && "POST".equals(method)) {
                login(ex);
                return;
            }
            if (!authed(ex)) {
                send(ex, 401, "application/json", "{\"error\":\"unauthorized\"}");
                return;
            }
            if ("/api/logout".equals(path)) {
                logout(ex);
                return;
            }
            if (!"GET".equals(method) && !csrfOk(ex)) {
                send(ex, 403, "application/json", "{\"error\":\"csrf\"}");
                return;
            }
            switch (path) {
                case "/api/status" -> status(ex);
                case "/api/logs" -> logs(ex);
                case "/api/command" -> command(ex);
                case "/api/backups" -> backups(ex);
                case "/api/backup" -> backup(ex);
                case "/api/restore" -> restore(ex);
                case "/api/power" -> power(ex);
                case "/api/jobs" -> jobs(ex);
                default -> send(ex, 404, "application/json", "{\"error\":\"not found\"}");
            }
        } catch (Exception e) {
            try {
                send(ex, 500, "application/json", "{\"error\":" + q(String.valueOf(e.getMessage())) + "}");
            } catch (IOException ignored) {
                // 响应已开始，忽略
            }
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------ 鉴权

    private void login(HttpExchange ex) throws IOException {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        long[] state = failures.computeIfAbsent(ip, k -> new long[]{0, 0});
        synchronized (state) {
            if (state[1] > now) {
                send(ex, 429, "application/json", "{\"error\":\"too many attempts\"}");
                return;
            }
        }

        String body = readBody(ex);
        String password = jsonField(body, "password");
        if (password == null) {
            password = formField(body, "password");
        }
        if (password == null) {
            send(ex, 400, "application/json", "{\"error\":\"missing password\"}");
            return;
        }

        boolean ok = Auth.verify(password.toCharArray(), app.config().passwordHash);
        synchronized (state) {
            if (ok) {
                state[0] = 0;
            } else {
                state[0]++;
                if (state[0] >= 5) {
                    state[1] = System.currentTimeMillis() + 60_000;
                    state[0] = 0;
                }
            }
        }
        if (!ok) {
            send(ex, 401, "application/json", "{\"error\":\"bad password\"}");
            return;
        }

        String session = Auth.issueSession(app.secret(), SESSION_TTL_MS);
        String csrf = Auth.randomToken(16);
        ex.getResponseHeaders().add("Set-Cookie",
                COOKIE_SESSION + "=" + session + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=604800");
        ex.getResponseHeaders().add("Set-Cookie",
                COOKIE_CSRF + "=" + csrf + "; Path=/; SameSite=Strict; Max-Age=604800");
        send(ex, 200, "application/json", "{\"ok\":true}");
    }

    private void logout(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE_SESSION + "=; Path=/; Max-Age=0");
        ex.getResponseHeaders().add("Set-Cookie", COOKIE_CSRF + "=; Path=/; Max-Age=0");
        send(ex, 200, "application/json", "{\"ok\":true}");
    }

    private boolean authed(HttpExchange ex) {
        return Auth.validSession(app.secret(), cookie(ex, COOKIE_SESSION));
    }

    private boolean csrfOk(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("X-CSRF");
        String cookie = cookie(ex, COOKIE_CSRF);
        if (header == null || cookie == null) {
            return false;
        }
        return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                cookie.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 接口

    private void status(HttpExchange ex) throws IOException {
        ServerManager s = app.server();
        Jobs.Job job = app.activeJob();
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"state\":\"").append(s.state().name()).append("\",");
        sb.append("\"online\":").append(s.onlinePlayers()).append(',');
        sb.append("\"max\":").append(s.maxPlayers()).append(',');
        sb.append("\"tps\":").append(Double.isNaN(s.tps()) ? "null" : String.format("%.2f", s.tps())).append(',');
        sb.append("\"uptime\":").append(s.uptimeSeconds()).append(',');
        sb.append("\"diskFree\":").append(app.diskFree()).append(',');
        sb.append("\"lastBackup\":").append(q(lastBackupLabel())).append(',');
        sb.append("\"busy\":").append(app.busy()).append(',');
        sb.append("\"issues\":").append(app.lastIssues().size()).append(',');
        if (job != null) {
            sb.append("\"job\":{")
                    .append("\"id\":").append(q(job.id)).append(',')
                    .append("\"phase\":").append(q(job.phase.name())).append(',')
                    .append("\"stage\":").append(q(job.stage)).append(',')
                    .append("\"percent\":").append(job.percent)
                    .append('}');
        } else {
            sb.append("\"job\":null");
        }
        sb.append('}');
        send(ex, 200, "application/json; charset=utf-8", sb.toString());
    }

    private void logs(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        BlockingQueue<String> queue = new LinkedBlockingQueue<>(4000);
        Consumer<String> listener = line -> {
            if (!queue.offer(line)) {
                queue.poll();          // 慢客户端：丢最旧的一行，绝不反压服务端
                queue.offer(line);
            }
        };
        app.server().subscribe(listener);
        try (OutputStream out = ex.getResponseBody()) {
            for (String line : app.server().history()) {
                writeEvent(out, line);
            }
            long lastPing = System.currentTimeMillis();
            while (!closed) {
                String line = queue.poll(15, TimeUnit.SECONDS);
                if (line != null) {
                    writeEvent(out, line);
                } else if (System.currentTimeMillis() - lastPing > 14_000) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    lastPing = System.currentTimeMillis();
                }
            }
        } catch (IOException | InterruptedException e) {
            // 客户端断开或服务关闭
        } finally {
            app.server().unsubscribe(listener);
        }
    }

    private void command(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String line = jsonField(body, "line");
        if (line == null || line.isBlank()) {
            send(ex, 400, "application/json", "{\"error\":\"empty\"}");
            return;
        }
        try {
            app.server().command(line);
            send(ex, 200, "application/json", "{\"ok\":true}");
        } catch (IllegalStateException e) {
            send(ex, 409, "application/json", "{\"error\":" + q(e.getMessage()) + "}");
        }
    }

    private void backups(HttpExchange ex) throws IOException {
        List<SnapshotEngine.Snapshot> list = app.engine().list();
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < list.size(); i++) {
            SnapshotEngine.Snapshot s = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"id\":").append(q(s.id())).append(',')
                    .append("\"created\":").append(q(s.createdLabel())).append(',')
                    .append("\"kind\":").append(q(s.kind())).append(',')
                    .append("\"kindLabel\":").append(q(s.kindLabel())).append(',')
                    .append("\"size\":").append(q(s.sizeLabel()))
                    .append('}');
        }
        sb.append("]}");
        send(ex, 200, "application/json; charset=utf-8", sb.toString());
    }

    private void backup(HttpExchange ex) throws IOException {
        if (app.busy()) {
            send(ex, 409, "application/json", "{\"error\":\"另一个任务正在执行\"}");
            return;
        }
        Jobs.Job job = app.submitBackup(true, true);
        send(ex, 200, "application/json", "{\"job\":" + q(job.id) + "}");
    }

    private void restore(HttpExchange ex) throws IOException {
        if (app.busy()) {
            send(ex, 409, "application/json", "{\"error\":\"另一个任务正在执行\"}");
            return;
        }
        String body = readBody(ex);
        String id = jsonField(body, "id");
        if (id == null || id.isBlank()) {
            send(ex, 400, "application/json", "{\"error\":\"missing id\"}");
            return;
        }
        try {
            app.engine().read(id);
        } catch (IOException e) {
            send(ex, 404, "application/json", "{\"error\":\"快照不存在\"}");
            return;
        }
        Jobs.Job job = app.submitRestore(id);
        send(ex, 200, "application/json", "{\"job\":" + q(job.id) + "}");
    }

    private void power(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String action = jsonField(body, "action");
        if (action == null) {
            send(ex, 400, "application/json", "{\"error\":\"missing action\"}");
            return;
        }
        if (!"start".equals(action) && !"stop".equals(action) && !"restart".equals(action)) {
            send(ex, 400, "application/json", "{\"error\":\"unknown action\"}");
            return;
        }
        if (app.busy()) {
            send(ex, 409, "application/json", "{\"error\":\"另一个任务正在执行\"}");
            return;
        }
        try {
            Jobs.Job job = app.submitPower(action);
            send(ex, 200, "application/json", "{\"job\":" + q(job.id) + "}");
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json", "{\"error\":" + q(e.getMessage()) + "}");
        }
    }

    private void jobs(HttpExchange ex) throws IOException {
        List<Jobs.Job> recent = app.jobs().recent(10);
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < recent.size(); i++) {
            Jobs.Job j = recent.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"id\":").append(q(j.id)).append(',')
                    .append("\"kind\":").append(q(j.kind)).append(',')
                    .append("\"phase\":").append(q(j.phase.name())).append(',')
                    .append("\"stage\":").append(q(j.stage)).append(',')
                    .append("\"percent\":").append(j.percent)
                    .append('}');
        }
        sb.append("]}");
        send(ex, 200, "application/json; charset=utf-8", sb.toString());
    }

    // ------------------------------------------------------------------ 工具

    private String lastBackupLabel() {
        SnapshotEngine.Snapshot snap = app.lastSnapshot();
        if (snap != null) {
            return snap.createdLabel();
        }
        try {
            List<SnapshotEngine.Snapshot> all = app.engine().list();
            return all.isEmpty() ? null : all.get(0).createdLabel();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeEvent(OutputStream out, String line) throws IOException {
        out.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0 && trimmed.substring(0, eq).equals(name)) {
                    return trimmed.substring(eq + 1);
                }
            }
        }
        return null;
    }

    /** 从极简 JSON 里取一个字符串字段（足够应付本面板的入参）。 */
    static String jsonField(String body, String key) {
        if (body == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) {
            return null;
        }
        int colon = body.indexOf(':', k + needle.length());
        if (colon < 0) {
            return null;
        }
        int start = body.indexOf('"', colon);
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char n = body.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(n);
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    static String formField(String body, String key) {
        if (body == null) {
            return null;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** JSON 字符串转义。 */
    public static String q(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
