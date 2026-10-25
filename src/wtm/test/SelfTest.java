package wtm.test;

import wtm.config.Config;
import wtm.config.Yaml;
import wtm.snap.Integrity;
import wtm.snap.SnapshotEngine;
import wtm.web.Auth;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 零依赖自检：java -cp wtm.jar wtm.test.SelfTest */
public final class SelfTest {

    private static int passed;
    private static int failed;

    private SelfTest() {}

    public static void main(String[] args) throws Exception {
        testYaml();
        testConfig();
        testAuth();
        testSnapshot();
        testIntegrity();
        System.out.printf("%n结果: %d 通过 / %d 失败%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ YAML

    private static void testYaml() {
        String text = """
                # 注释
                server:
                  command: java
                  args:
                    - -Xms1G
                    - "-jar"
                  dir: /srv/mc   # 行尾注释
                panel:
                  port: 8080
                  ok: true
                """;
        Map<String, Object> root = Yaml.parse(text);
        Map<String, Object> server = cast(root.get("server"));
        assertEquals("java", server.get("command"), "yaml: 标量");
        assertEquals(List.of("-Xms1G", "-jar"), server.get("args"), "yaml: 列表与引号");
        assertEquals("/srv/mc", server.get("dir"), "yaml: 行尾注释");
        Map<String, Object> panel = cast(root.get("panel"));
        assertEquals(8080L, panel.get("port"), "yaml: 整数");
        assertEquals(Boolean.TRUE, panel.get("ok"), "yaml: 布尔");
    }

    private static void testConfig() throws Exception {
        Path tmp = Files.createTempDirectory("wtm-cfg");
        Path server = tmp.resolve("server");
        Files.createDirectories(server);
        Path cfgFile = tmp.resolve("config.yaml");
        Files.writeString(cfgFile, """
                server:
                  command: java
                  args:
                  dir: %s
                panel:
                  password_hash: "pbkdf2$sha256$1000$abc$def"
                backup:
                  dir: %s/data
                  worlds: [world]
                """.formatted(server, tmp), StandardCharsets.UTF_8);
        try {
            Config.load(cfgFile);
            fail("config: 空 args 应报错");
        } catch (IllegalArgumentException expected) {
            pass("config: 拒绝空的 server.args");
        }

        Files.writeString(cfgFile, """
                server:
                  command: java
                  args:
                    - -jar
                    - server.jar
                  dir: %s
                  stop_timeout: 60s
                panel:
                  listen: 127.0.0.1
                  port: 8080
                  password_hash: "x"
                backup:
                  dir: %s/data
                  worlds: [world, world_nether]
                  at: "4:00"
                  keep_last: 7
                """.formatted(server, tmp), StandardCharsets.UTF_8);
        Config cfg = Config.load(cfgFile);
        assertEquals(2, cfg.worlds.size(), "config: worlds 数量");
        assertEquals(60L, cfg.stopTimeout.getSeconds(), "config: 时长解析");
        assertEquals(4, cfg.backupAt.getHour(), "config: 时间解析");
    }

    // ------------------------------------------------------------------ 鉴权

    private static void testAuth() {
        String stored = Auth.hash("family-2026".toCharArray());
        assertTrue(Auth.verify("family-2026".toCharArray(), stored), "auth: 正确口令");
        assertFalse(Auth.verify("wrong".toCharArray(), stored), "auth: 错误口令");
        assertFalse(Auth.verify("family-2026".toCharArray(), "garbage"), "auth: 损坏的哈希");

        String session = Auth.issueSession("secret", 60_000);
        assertTrue(Auth.validSession("secret", session), "auth: 会话有效");
        assertFalse(Auth.validSession("other-secret", session), "auth: 换密钥应失效");
        assertFalse(Auth.validSession("secret", null), "auth: 空会话");
    }

    // ------------------------------------------------------------------ 快照

    private static void testSnapshot() throws Exception {
        Path root = Files.createTempDirectory("wtm-snap");
        Path server = root.resolve("server");
        Path data = root.resolve("data");
        Files.createDirectories(server.resolve("world/region"));
        Files.createDirectories(server.resolve("world/playerdata"));
        write(server.resolve("world/level.dat"), "LEVEL");
        write(server.resolve("world/region/r.0.0.mca"), "CHUNK-A");
        write(server.resolve("world/region/r.0.1.mca"), "CHUNK-B");
        write(server.resolve("world/playerdata/abc.dat"), "PLAYER");

        SnapshotEngine engine = new SnapshotEngine(server, data, List.of("world"));
        SnapshotEngine.Snapshot s1 = engine.create("manual");
        Thread.sleep(1100);
        SnapshotEngine.Snapshot s2 = engine.create("manual");

        Path a1 = s1.dir().resolve("world/region/r.0.0.mca");
        Path a2 = s2.dir().resolve("world/region/r.0.0.mca");
        assertTrue(Files.isSameFile(a1, a2), "snapshot: 未变更文件应复用硬链接");

        Thread.sleep(1100);
        write(server.resolve("world/region/r.0.0.mca"), "CHUNK-A2");
        SnapshotEngine.Snapshot s3 = engine.create("manual");
        Path a3 = s3.dir().resolve("world/region/r.0.0.mca");
        assertFalse(Files.isSameFile(a2, a3), "snapshot: 变更文件不应复用");
        assertEquals("CHUNK-A", read(a2), "snapshot: 旧快照内容必须保持不变");
        assertEquals("CHUNK-A2", read(a3), "snapshot: 新快照应反映新内容");

        // 保留策略：只清理 auto；manual 必须保留
        engine.create("auto");
        Thread.sleep(1100);
        engine.create("auto");
        int removed = engine.prune(1, 1);
        assertEquals(1, removed, "prune: 应删除 1 个旧 auto 快照");
        long manuals = engine.list().stream().filter(s -> "manual".equals(s.kind())).count();
        assertEquals(3L, manuals, "prune: manual 快照不应被自动删除");

        // 恢复：回到 s1 的内容
        engine.restore(s1.id());
        assertEquals("CHUNK-A", read(server.resolve("world/region/r.0.0.mca")), "restore: 内容应回到快照");
        assertTrue(Files.isDirectory(server.resolve(".wtm-trash")), "restore: 旧世界应移入 serverDir/.wtm-trash（同挂载点）");

        SnapshotEngine.deleteTree(root);
    }

    // ------------------------------------------------------------------ 体检

    private static void testIntegrity() throws Exception {
        Path root = Files.createTempDirectory("wtm-int");
        Path region = root.resolve("region");
        Files.createDirectories(region);
        Path mca = region.resolve("r.0.0.mca");
        byte[] buf = new byte[8192];
        // 第 0 个区块：扇区偏移 10、长度 1，但文件只有 2 个扇区 -> 越界
        buf[0] = 0;
        buf[1] = 0;
        buf[2] = 10;
        buf[3] = 1;
        Files.write(mca, buf);

        List<Integrity.Issue> issues = Integrity.checkRegion(mca, "world");
        assertTrue(issues.size() >= 1, "integrity: 应检出越界区块");
        assertTrue(issues.get(0).detail().contains("越界"), "integrity: 错误描述应包含越界");

        // level.dat 不是 gzip
        Files.write(root.resolve("level.dat"), "NOT-GZIP".getBytes(StandardCharsets.UTF_8));
        assertFalse(Integrity.levelDatLooksValid(root), "integrity: 非 gzip 的 level.dat 应判为异常");

        SnapshotEngine.deleteTree(root);
    }

    // ------------------------------------------------------------------ 工具

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS  " + name);
    }

    private static void fail(String name) {
        failed++;
        System.out.println("  FAIL  " + name);
    }

    private static void assertEquals(Object expected, Object actual, String name) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            pass(name);
        } else {
            fail(name + " (期望 " + expected + ", 实际 " + actual + ")");
        }
    }

    private static void assertTrue(boolean cond, String name) {
        if (cond) {
            pass(name);
        } else {
            fail(name);
        }
    }

    private static void assertFalse(boolean cond, String name) {
        assertTrue(!cond, name);
    }
}
