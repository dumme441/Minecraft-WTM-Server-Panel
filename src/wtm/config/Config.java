package wtm.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 已校验的配置对象。所有字段在构造时校验，避免运行期才发现路径写错。 */
public final class Config {

    public final String command;
    public final List<String> args;
    public final Path serverDir;
    public final Duration readyTimeout;
    public final Duration stopTimeout;
    public final boolean autostart;

    public final String listen;
    public final int port;
    public final String passwordHash;

    public final Path dataDir;
    public final List<String> worlds;
    public final LocalTime backupAt;
    public final int keepLast;
    public final int keepDaily;
    public final int waitIdleMinutes;

    private Config(Map<String, Object> root) {
        Map<String, Object> server = section(root, "server");
        this.command = str(server, "command", "java");
        this.args = strings(server.get("args"));
        if (args.isEmpty()) {
            throw new IllegalArgumentException("server.args 不能为空，至少要有 -jar <服务端>.jar nogui");
        }

        this.serverDir = Path.of(str(server, "dir", "")).toAbsolutePath().normalize();
        if (!Files.isDirectory(serverDir)) {
            throw new IllegalArgumentException("server.dir 不存在或不是目录: " + serverDir);
        }
        this.readyTimeout = duration(server, "ready_timeout", Duration.ofSeconds(180));
        this.stopTimeout = duration(server, "stop_timeout", Duration.ofSeconds(60));
        this.autostart = bool(server, "autostart", true);

        Map<String, Object> panel = section(root, "panel");
        this.listen = str(panel, "listen", "127.0.0.1");
        this.port = (int) num(panel, "port", 8080);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("panel.port 超出范围: " + port);
        }
        this.passwordHash = str(panel, "password_hash", "").trim();
        if (passwordHash.isEmpty()) {
            throw new IllegalArgumentException("panel.password_hash 为空，先运行: java -jar wtm.jar hash-password");
        }

        Map<String, Object> backup = section(root, "backup");
        this.dataDir = Path.of(str(backup, "dir", "./wtm-data")).toAbsolutePath().normalize();
        this.worlds = strings(backup.get("worlds"));
        if (worlds.isEmpty()) {
            throw new IllegalArgumentException("backup.worlds 不能为空，例如 [world, world_nether]");
        }
        String at = str(backup, "at", "04:00");
        try {
            this.backupAt = LocalTime.parse(at, DateTimeFormatter.ofPattern("H:mm"));
        } catch (Exception e) {
            throw new IllegalArgumentException("backup.at 格式应为 HH:mm，当前: " + at);
        }
        this.keepLast = (int) num(backup, "keep_last", 7);
        this.keepDaily = (int) num(backup, "keep_daily", 5);
        this.waitIdleMinutes = (int) num(backup, "wait_idle_minutes", 30);
        if (keepLast < 1 || keepDaily < 1) {
            throw new IllegalArgumentException("keep_last / keep_daily 至少为 1");
        }
    }

    public static Config load(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return new Config(Yaml.parse(text));
    }

    public List<String> commandLine() {
        List<String> cmd = new ArrayList<>(args.size() + 1);
        cmd.add(command);
        cmd.addAll(args);
        return cmd;
    }

    private static Map<String, Object> section(Map<String, Object> root, String name) {
        Object v = root.get(name);
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        throw new IllegalArgumentException("缺少配置段 [" + name + "]");
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static long num(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Long l) {
            return l;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 应为整数，当前: " + v);
        }
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private static Duration duration(Map<String, Object> m, String key, Duration def) {
        String raw = str(m, key, "").trim();
        if (raw.isEmpty()) {
            return def;
        }
        try {
            if (raw.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(raw.substring(0, raw.length() - 2)));
            }
            if (raw.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(raw.substring(0, raw.length() - 1)));
            }
            if (raw.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(raw.substring(0, raw.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 应为如 180s / 5m，当前: " + raw);
        }
    }
}
