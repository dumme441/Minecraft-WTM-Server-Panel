package wtm.snap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 世界文件体检（L1 级别）：只读 region 头部，不做全量 NBT 解析。
 * 目的是在"能进服"之前就发现明显的区域文件损坏，而不是等玩家踩到。
 */
public final class Integrity {

    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int SECTOR = 4096;

    public record Issue(String world, String file, int chunkX, int chunkZ, String detail) {
        @Override
        public String toString() {
            return world + "/" + file + " @(" + chunkX + "," + chunkZ + "): " + detail;
        }
    }

    private Integrity() {}

    public static List<Issue> scanWorld(Path worldDir, String worldName) throws IOException {
        List<Issue> out = new ArrayList<>();
        Path regionDir = worldDir.resolve("region");
        if (!Files.isDirectory(regionDir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(regionDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".mca")).toList()) {
                out.addAll(checkRegion(f, worldName));
            }
        }
        return out;
    }

    public static List<Issue> checkRegion(Path file, String worldName) throws IOException {
        List<Issue> out = new ArrayList<>();
        String name = file.getFileName().toString();
        Matcher m = REGION_NAME.matcher(name);
        int baseX = 0;
        int baseZ = 0;
        boolean named = m.matches();
        if (named) {
            baseX = Integer.parseInt(m.group(1)) * 32;
            baseZ = Integer.parseInt(m.group(2)) * 32;
        }

        long size = Files.size(file);
        if (size < 2L * SECTOR) {
            out.add(new Issue(worldName, name, -1, -1, "文件小于 8KB 头部，疑似截断"));
            return out;
        }
        long sectors = size / SECTOR;

        byte[] header = new byte[SECTOR];
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.wrap(header);
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // 读满 4KB 头部
            }
        }

        boolean[] used = new boolean[(int) Math.min(sectors, 1 << 20)];
        for (int i = 0; i < 1024; i++) {
            int off = ((header[i * 4] & 0xFF) << 16) | ((header[i * 4 + 1] & 0xFF) << 8) | (header[i * 4 + 2] & 0xFF);
            int cnt = header[i * 4 + 3] & 0xFF;
            if (off == 0 && cnt == 0) {
                continue;
            }
            int cx = baseX + (i % 32);
            int cz = baseZ + (i / 32);
            if (cnt == 0) {
                out.add(new Issue(worldName, name, cx, cz, "扇区数为 0（空记录）"));
                continue;
            }
            if (off < 2 || off + cnt > sectors) {
                out.add(new Issue(worldName, name, cx, cz,
                        "区块越界: 扇区 " + off + "+" + cnt + " > 文件扇区数 " + sectors));
                continue;
            }
            boolean overlapped = false;
            for (int s = off; s < off + cnt && s < used.length; s++) {
                if (used[s]) {
                    overlapped = true;
                    break;
                }
                used[s] = true;
            }
            if (overlapped) {
                out.add(new Issue(worldName, name, cx, cz, "扇区重叠（可能有多个区块指向同一段）"));
            }
        }
        return out;
    }

    public static boolean levelDatLooksValid(Path worldDir) {
        Path level = worldDir.resolve("level.dat");
        try {
            if (!Files.isRegularFile(level)) {
                return false;
            }
            byte[] head = new byte[2];
            try (var in = Files.newInputStream(level)) {
                if (in.read(head) != 2) {
                    return false;
                }
            }
            // gzip 魔数 1f 8b
            return (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B;
        } catch (IOException e) {
            return false;
        }
    }
}
