package wtm.snap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 硬链接快照引擎。
 *
 * 安全前提（务必保持）：
 * 1. 快照只在**服务端已停止**时执行，源文件静止；
 * 2. 硬链接只在快照与快照之间建立，**永不指向 world/**；
 * 3. 恢复时必须复制物化，不得把快照目录直接改名成 world/。
 *
 * 违反任意一条，都会让旧快照随在线世界的原地写入一起损坏。
 */
public final class SnapshotEngine {

    private static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TRASH_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path serverDir;
    private final Path dataDir;
    private final Path snapRoot;
    private final List<String> worlds;
    private final Object lock = new Object();

    public SnapshotEngine(Path serverDir, Path dataDir, List<String> worlds) throws IOException {
        this.serverDir = serverDir;
        this.dataDir = dataDir;
        this.snapRoot = dataDir.resolve("snapshots");
        this.worlds = List.copyOf(worlds);
        Files.createDirectories(snapRoot);
    }

    // ------------------------------------------------------------------ 创建

    public Snapshot create(String kind) throws IOException {
        synchronized (lock) {
            String id = LocalDateTime.now().format(ID_FMT);
            Path dir = snapRoot.resolve(id);
            int suffix = 0;
            while (Files.exists(dir)) {
                suffix++;
                dir = snapRoot.resolve(id + "-" + suffix);
            }
            // 必须先取上一个快照，再创建新目录：否则新目录会被当成 "最新的一个"
            Path prev = latestDir();
            Files.createDirectories(dir);

            long bytes = 0;
            int files = 0;
            List<String> written = new ArrayList<>();

            for (String world : worlds) {
                Path src = serverDir.resolve(world);
                if (!Files.isDirectory(src)) {
                    continue;
                }
                List<Path> all;
                try (Stream<Path> walk = Files.walk(src)) {
                    all = walk.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path file : all) {
                    Path rel = src.relativize(file);
                    Path target = dir.resolve(world).resolve(rel);
                    Files.createDirectories(target.getParent());
                    boolean linked = false;
                    if (prev != null) {
                        Path candidate = prev.resolve(world).resolve(rel);
                        if (Files.isRegularFile(candidate) && sameStat(file, candidate)) {
                            try {
                                Files.createLink(target, candidate);
                                linked = true;
                            } catch (IOException | UnsupportedOperationException e) {
                                linked = false; // 跨卷或文件系统不支持硬链接，退回复制
                            }
                        }
                    }
                    if (!linked) {
                        copyPreservingTime(file, target);
                    }
                    bytes += Files.size(target);
                    files++;
                }
                written.add(world);
            }

            Snapshot snap = new Snapshot(dir.getFileName().toString(), Instant.now(), kind, bytes, files,
                    List.copyOf(written), dir);
            writeMeta(snap);
            return snap;
        }
    }

    private static void copyPreservingTime(Path src, Path dst) throws IOException {
        try {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setLastModifiedTime(dst, Files.getLastModifiedTime(src));
            } catch (IOException ignored) {
                // 保留 mtime 失败只影响下次去重效率，不影响正确性
            }
        }
    }

    private static boolean sameStat(Path a, Path b) {
        try {
            return Files.size(a) == Files.size(b)
                    && Files.getLastModifiedTime(a).equals(Files.getLastModifiedTime(b));
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 恢复

    /** 把快照物化到 serverDir。旧世界移到 data/trash/<ts>/，失败可手工回滚。 */
    public Path restore(String id) throws IOException {
        synchronized (lock) {
            Path snapDir = snapRoot.resolve(id);
            if (!Files.isDirectory(snapDir)) {
                throw new IOException("快照不存在: " + id);
            }
            Snapshot meta = read(id);
            // 回收站必须与服务端目录在同一个挂载点：跨挂载点 rename 会失败。
            // systemd 的 ReadWritePaths/ProtectSystem 会把两个路径变成独立 bind mount。
            Path trash = serverDir.resolve(".wtm-trash").resolve(LocalDateTime.now().format(TRASH_FMT));
            Files.createDirectories(trash);

            for (String world : meta.worlds()) {
                Path target = serverDir.resolve(world);
                Path src = snapDir.resolve(world);
                // 先"停放"旧世界（纯 rename，目标不存在，避免 REPLACE_EXISTING 对目录的额外检查）
                Path parked = serverDir.resolve("." + world + ".wtm-old");
                if (Files.exists(parked)) {
                    deleteTree(parked);
                }
                boolean hadWorld = Files.exists(target);
                System.out.println("[wtm] restore: world=" + world + " target=" + target
                        + " src=" + src + " hadWorld=" + hadWorld);
                if (hadWorld) {
                    Files.move(target, parked);
                }
                try {
                    copyTree(src, target);
                } catch (IOException e) {
                    // 回滚：清掉半成品，把旧世界换回来
                    deleteTree(target);
                    if (hadWorld) {
                        Files.move(parked, target);
                    }
                    throw e;
                }
                if (hadWorld) {
                    Path archived = trash.resolve(world);
                    Files.createDirectories(archived.getParent());
                    if (Files.exists(archived)) {
                        deleteTree(archived);
                    }
                    Files.move(parked, archived);
                }
            }
            return trash;
        }
    }

    private static void copyTree(Path src, Path target) throws IOException {
        Files.createDirectories(target);
        List<Path> all;
        try (Stream<Path> walk = Files.walk(src)) {
            all = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : all) {
            Path dest = target.resolve(src.relativize(file).toString());
            Files.createDirectories(dest.getParent());
            Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ 查询

    public List<Snapshot> list() throws IOException {
        List<Snapshot> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(snapRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                try {
                    out.add(read(dir.getFileName().toString()));
                } catch (IOException ignored) {
                    // 忽略半成品目录（例如备份中途断电），但不删除，留给人工判断
                }
            }
        }
        out.sort(Comparator.comparing(Snapshot::created).reversed());
        return out;
    }

    public Snapshot read(String id) throws IOException {
        Path dir = snapRoot.resolve(id);
        Path meta = dir.resolve("meta.properties");
        Snapshot fallback = new Snapshot(id, Instant.ofEpochMilli(Files.getLastModifiedTime(dir).toMillis()),
                "unknown", 0, 0, List.of(), dir);
        if (!Files.isRegularFile(meta)) {
            return fallback;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(meta)) {
            props.load(in);
        }
        List<String> ws = new ArrayList<>();
        String raw = props.getProperty("worlds", "");
        for (String s : raw.split(",")) {
            if (!s.isBlank()) {
                ws.add(s.trim());
            }
        }
        return new Snapshot(id,
                Instant.ofEpochMilli(Long.parseLong(props.getProperty("created", "0"))),
                props.getProperty("kind", "auto"),
                Long.parseLong(props.getProperty("bytes", "0")),
                Integer.parseInt(props.getProperty("files", "0")),
                List.copyOf(ws),
                dir);
    }

    private void writeMeta(Snapshot snap) throws IOException {
        Properties props = new Properties();
        props.setProperty("id", snap.id());
        props.setProperty("created", String.valueOf(snap.created().toEpochMilli()));
        props.setProperty("kind", snap.kind());
        props.setProperty("bytes", String.valueOf(snap.bytes()));
        props.setProperty("files", String.valueOf(snap.files()));
        props.setProperty("worlds", String.join(",", snap.worlds()));
        try (var out = Files.newOutputStream(snap.dir().resolve("meta.properties"))) {
            props.store(out, "wtm snapshot");
        }
    }

    private Path latestDir() throws IOException {
        List<Snapshot> all = list();
        return all.isEmpty() ? null : all.get(0).dir();
    }

    // ------------------------------------------------------------------ 保留策略

    /** 只清理 auto 快照；manual / pre_restore 永不自动删除。返回删除数量。 */
    public int prune(int keepLast, int keepDaily) throws IOException {
        synchronized (lock) {
            List<Snapshot> all = list();
            List<Snapshot> autos = all.stream().filter(s -> "auto".equals(s.kind())).toList();
            Map<String, Snapshot> keep = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(keepLast, autos.size()); i++) {
                keep.put(autos.get(i).id(), autos.get(i));
            }
            Map<String, Snapshot> newestPerDay = new LinkedHashMap<>();
            for (Snapshot s : autos) {
                String day = s.created().atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
                newestPerDay.putIfAbsent(day, s);
            }
            int dayCount = 0;
            for (Snapshot s : newestPerDay.values()) {
                if (dayCount++ >= keepDaily) {
                    break;
                }
                keep.put(s.id(), s);
            }
            int removed = 0;
            for (Snapshot s : autos) {
                if (!keep.containsKey(s.id())) {
                    deleteTree(s.dir());
                    removed++;
                }
            }
            return removed;
        }
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path serverDir() {
        return serverDir;
    }

    public List<String> worlds() {
        return worlds;
    }

    /** 记录：一次快照的元数据。 */
    public record Snapshot(String id, Instant created, String kind, long bytes, int files,
                           List<String> worlds, Path dir) {

        public String kindLabel() {
            return switch (kind) {
                case "auto" -> "定时";
                case "manual" -> "手动";
                case "pre_restore" -> "回档前";
                default -> kind;
            };
        }

        public String sizeLabel() {
            double v = bytes;
            String[] units = {"B", "KB", "MB", "GB", "TB"};
            int i = 0;
            while (v >= 1024 && i < units.length - 1) {
                v /= 1024;
                i++;
            }
            return String.format("%.1f %s", v, units[i]);
        }

        public String createdLabel() {
            return created.atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        }
    }

    /** 便于单测：暴露根目录。 */
    public Path snapRoot() {
        return snapRoot;
    }

    /** 把字符串安全写入 UTF-8 文件，供元数据以外的场景复用。 */
    public static void writeUtf8(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 包装受检异常，便于在 lambda 里使用。 */
    public static <T> T wrap(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public interface ThrowingSupplier<T> {
        T get() throws IOException;
    }
}
