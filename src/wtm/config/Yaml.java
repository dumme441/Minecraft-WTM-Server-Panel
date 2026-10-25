package wtm.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 YAML 子集解析器（零依赖）。
 *
 * 支持：嵌套 map（空格缩进）、标量、字符串列表（"- item"）、# 注释。
 * 不支持：多行字符串、锚点、行内 map。故意保持极小，够用即可。
 */
public final class Yaml {

    private record Line(int indent, String text) {}

    private Yaml() {}

    public static Map<String, Object> parse(String text) {
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1); // 记事本等编辑器可能写入 BOM
        }
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\r?\n", -1)) {
            String noComment = stripComment(raw);
            if (noComment.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < noComment.length() && noComment.charAt(indent) == ' ') {
                indent++;
            }
            lines.add(new Line(indent, noComment.substring(indent).stripTrailing()));
        }

        int[] pos = {0};
        Map<String, Object> root = new LinkedHashMap<>();
        parseMap(lines, pos, 0, root);
        if (pos[0] < lines.size()) {
            throw new IllegalArgumentException("配置缩进异常，靠近: " + lines.get(pos[0]).text());
        }
        return root;
    }

    private static void parseMap(List<Line> lines, int[] pos, int indent, Map<String, Object> into) {
        while (pos[0] < lines.size()) {
            Line line = lines.get(pos[0]);
            if (line.indent < indent) {
                return;
            }
            if (line.indent > indent) {
                throw new IllegalArgumentException("配置缩进异常，靠近: " + line.text);
            }
            if (line.text.startsWith("- ")) {
                return;
            }

            int colon = line.text.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("缺少冒号: " + line.text);
            }
            String key = line.text.substring(0, colon).trim();
            String rest = line.text.substring(colon + 1).trim();
            pos[0]++;

            if (!rest.isEmpty()) {
                into.put(key, rest.startsWith("[") ? inlineList(rest) : scalar(rest));
                continue;
            }

            if (pos[0] < lines.size()) {
                Line next = lines.get(pos[0]);
                if (next.indent > indent) {
                    if (next.text.startsWith("- ")) {
                        into.put(key, parseList(lines, pos, next.indent));
                    } else {
                        Map<String, Object> child = new LinkedHashMap<>();
                        parseMap(lines, pos, next.indent, child);
                        into.put(key, child);
                    }
                    continue;
                }
            }
            into.put(key, "");
        }
    }

    /** 行内列表：[a, b, "c"]。不支持嵌套与含逗号的引号串（够用即可）。 */
    private static List<Object> inlineList(String raw) {
        List<Object> out = new ArrayList<>();
        String body = raw.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        body = body.trim();
        if (body.isEmpty()) {
            return out;
        }
        for (String part : body.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(scalar(s));
            }
        }
        return out;
    }

    private static List<Object> parseList(List<Line> lines, int[] pos, int indent) {
        List<Object> out = new ArrayList<>();
        while (pos[0] < lines.size()) {
            Line line = lines.get(pos[0]);
            if (line.indent != indent || !line.text.startsWith("- ")) {
                return out;
            }
            out.add(scalar(stripComment(line.text.substring(2)).trim()));
            pos[0]++;
        }
        return out;
    }

    private static Object scalar(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        if (s.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (s.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        if (s.matches("-?\\d+")) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return s;
            }
        }
        return s;
    }

    private static String stripComment(String raw) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '#' && !inSingle && !inDouble && (i == 0 || raw.charAt(i - 1) == ' ')) {
                return raw.substring(0, i);
            }
        }
        return raw;
    }

    /** 调试用：把解析结果打印成可读缩进结构。 */
    public static String dump(Object node) {
        StringBuilder sb = new StringBuilder();
        dumpInto(sb, node, 0);
        return sb.toString();
    }

    private static void dumpInto(StringBuilder sb, Object node, int depth) {
        String pad = "  ".repeat(depth);
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(pad).append(e.getKey()).append(":\n");
                dumpInto(sb, e.getValue(), depth + 1);
                if (!(e.getValue() instanceof Map<?, ?>)) {
                    sb.setLength(sb.length() - 2);
                }
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) {
                sb.append(pad).append("- ").append(o).append('\n');
            }
        } else {
            sb.append(pad).append(node).append('\n');
        }
    }

    /** 供单元测试使用的最小栈工具，避免测试里出现裸 Deque 泛型警告。 */
    static Deque<Object> newStack() {
        return new ArrayDeque<>();
    }
}
