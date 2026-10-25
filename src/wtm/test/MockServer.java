package wtm.test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 冒烟测试用的假服务端：模拟 Paper 的启动输出与常用指令，用来验证
 * "启动 → 就绪 → 发指令 → 停止 → 快照 → 重启" 全链路。
 */
public final class MockServer {

    private static volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        System.out.println("[Server thread/INFO]: Starting minecraft server version 26.3");
        Thread.sleep(600);
        System.out.println("[Server thread/INFO]: Preparing level \"world\"");
        Thread.sleep(800);
        System.out.println("[Server thread/INFO]: Done (1.412s)! For help, type \"help\"");
        System.out.flush();

        Thread input = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String cmd = line.trim();
                    if (cmd.equals("stop")) {
                        System.out.println("[Server thread/INFO]: Stopping server");
                        running = false;
                        System.exit(0);
                    } else if (cmd.equals("list")) {
                        System.out.println("[Server thread/INFO]: There are 2 of a max of 20 players online: Alice, Bob");
                    } else if (cmd.startsWith("tick") || cmd.equals("tps")) {
                        System.out.println("[Server thread/INFO]: Average TPS: 19.97");
                    } else if (cmd.equals("crash")) {
                        System.out.println("[Server thread/ERROR]: boom");
                        System.exit(3);
                    } else if (!cmd.isEmpty()) {
                        System.out.println("[Server thread/INFO]: unknown command: " + cmd);
                    }
                    System.out.flush();
                }
            } catch (Exception ignored) {
                // 进程退出
            }
        });
        input.setDaemon(true);
        input.start();

        while (running) {
            Thread.sleep(500);
        }
    }
}
