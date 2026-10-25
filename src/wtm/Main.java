package wtm;

import wtm.app.App;
import wtm.config.Config;
import wtm.web.Auth;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 入口：run / init / hash-password / version。 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        String cmd = args.length == 0 ? "run" : args[0];
        switch (cmd) {
            case "run" -> run(configPath(args, 1));
            case "init" -> init(configPath(args, 1));
            case "hash-password" -> hashPassword(args.length > 1 ? args[1] : null);
            case "version" -> System.out.println("wtm 0.3.0 / java " + System.getProperty("java.version"));
            default -> {
                System.out.println("""
                        用法:
                          java -jar wtm.jar run [config.yaml]        启动面板与服务端
                          java -jar wtm.jar init [config.yaml]       生成配置模板
                          java -jar wtm.jar hash-password [口令]     生成口令哈希
                          java -jar wtm.jar version                  版本
                        """);
            }
        }
    }

    private static Path configPath(String[] args, int index) {
        return Path.of(args.length > index ? args[index] : "config.yaml");
    }

    private static void run(Path configPath) throws Exception {
        Config cfg = Config.load(configPath);
        new App(cfg).run();
    }

    private static void init(Path configPath) throws Exception {
        if (Files.exists(configPath)) {
            System.out.println("配置已存在，未覆盖: " + configPath.toAbsolutePath());
            return;
        }
        String template = """
                # WTM 配置（注释以 # 开头）
                server:
                  command: java
                  args:
                    - -Xms1G
                    - -Xmx3G
                    - -jar
                    - server.jar
                    - nogui
                  dir: /srv/mc
                  autostart: true
                  ready_timeout: 180s
                  stop_timeout: 60s

                panel:
                  listen: 127.0.0.1
                  port: 8080
                  # 用 java -jar wtm.jar hash-password 生成
                  password_hash: ""

                backup:
                  dir: /opt/wtm/data
                  worlds:
                    - world
                    - world_nether
                    - world_the_end
                  at: "04:00"
                  keep_last: 7
                  keep_daily: 5
                  wait_idle_minutes: 30
                """;
        Files.writeString(configPath, template, StandardCharsets.UTF_8);
        System.out.println("已生成: " + configPath.toAbsolutePath());
        System.out.println("下一步: java -jar wtm.jar hash-password  然后把结果填进 panel.password_hash");
    }

    private static void hashPassword(String arg) throws Exception {
        char[] password;
        if (arg != null) {
            password = arg.toCharArray();
        } else {
            Console console = System.console();
            if (console != null) {
                password = console.readPassword("设置面板口令: ");
            } else {
                System.out.print("设置面板口令: ");
                password = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine().toCharArray();
            }
        }
        if (password.length < 4) {
            System.out.println("口令太短（至少 4 位）");
            return;
        }
        System.out.println(Auth.hash(password));
    }
}
