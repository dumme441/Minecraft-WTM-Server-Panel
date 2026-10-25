package wtm.app;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 备份/回档/电源任务的极简状态机。面板靠它显示"现在在干什么"。 */
public final class Jobs {

    public enum Phase { QUEUED, RUNNING, DONE, FAILED }

    public static final class Job {
        public final String id;
        public final String kind;
        public final Instant createdAt = Instant.now();

        public volatile Phase phase = Phase.QUEUED;
        public volatile String stage = "排队中";
        public volatile int percent;
        public volatile String error;
        public volatile Instant finishedAt;
        public volatile boolean skipped;

        Job(String id, String kind) {
            this.id = id;
            this.kind = kind;
        }

        public synchronized void stage(String text, int pct) {
            this.stage = text;
            this.percent = Math.max(0, Math.min(100, pct));
            this.phase = Phase.RUNNING;
        }

        public synchronized void done() {
            this.phase = Phase.DONE;
            this.percent = 100;
            this.stage = "完成";
            this.finishedAt = Instant.now();
        }

        public synchronized void fail(String message) {
            this.phase = Phase.FAILED;
            this.error = message;
            this.stage = "失败：" + message;
            this.finishedAt = Instant.now();
        }

        public synchronized void skip(String message) {
            this.phase = Phase.DONE;
            this.skipped = true;
            this.error = message;
            this.stage = "已跳过：" + message;
            this.finishedAt = Instant.now();
        }

        public boolean running() {
            return phase == Phase.QUEUED || phase == Phase.RUNNING;
        }
    }

    private final Map<String, Job> byId = new ConcurrentHashMap<>();
    private final Deque<Job> order = new ArrayDeque<>();
    private final AtomicInteger seq = new AtomicInteger(1000);

    public Job create(String kind) {
        String id = "J" + seq.incrementAndGet();
        Job job = new Job(id, kind);
        byId.put(id, job);
        synchronized (order) {
            order.addFirst(job);
            while (order.size() > 50) {
                Job old = order.removeLast();
                byId.remove(old.id);
            }
        }
        return job;
    }

    public Job get(String id) {
        return byId.get(id);
    }

    public List<Job> recent(int limit) {
        synchronized (order) {
            List<Job> out = new ArrayList<>();
            for (Job j : order) {
                if (out.size() >= limit) {
                    break;
                }
                out.add(j);
            }
            return out;
        }
    }
}
