package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Groups threads into logical pools by recognising common naming patterns.
 * Unknown/singleton threads are placed into an "(other)" bucket.
 */
public class ThreadPoolGrouper {

    private static final List<PoolPattern> PATTERNS = List.of(
            // java.util.concurrent – pool-1-thread-3
            new PoolPattern(Pattern.compile("^(pool-\\d+)-thread-\\d+$"), 1),
            // ForkJoinPool.commonPool-worker-3  /  ForkJoinPool-1-worker-3
            new PoolPattern(Pattern.compile("^(ForkJoinPool(?:\\.commonPool|-\\d+))-worker-\\d+$"), 1),
            // Tomcat/Catalina – http-nio-8080-exec-5
            new PoolPattern(Pattern.compile("^((?:http|https|ajp)-nio(?:-\\d+)?)-exec-\\d+$"), 1),
            // Tomcat BIO – http-bio-8080-exec-5
            new PoolPattern(Pattern.compile("^((?:http|https)-bio(?:-\\d+)?)-exec-\\d+$"), 1),
            // Jetty – qtp123456789-42
            new PoolPattern(Pattern.compile("^(qtp\\d+)-\\d+$"), 1),
            // Netty – nioEventLoopGroup-2-1
            new PoolPattern(Pattern.compile("^(nioEventLoopGroup-\\d+)-\\d+$"), 1),
            // Spring task executor – taskExecutor-3  /  MyExecutor-3
            new PoolPattern(Pattern.compile("^(.+Executor)-\\d+$"), 1),
            // Generic "-N" suffix pools  e.g. grpc-default-executor-3
            new PoolPattern(Pattern.compile("^(.+)-\\d+$"), 1)
    );

    public List<ThreadPool> group(ThreadDump dump) {
        Map<String, List<ThreadInfo>> buckets = new LinkedHashMap<>();

        for (ThreadInfo t : dump.threads()) {
            String pool = detectPoolName(t.name());
            buckets.computeIfAbsent(pool, k -> new ArrayList<>()).add(t);
        }

        return buckets.entrySet().stream()
                .map(e -> new ThreadPool(e.getKey(), List.copyOf(e.getValue())))
                .sorted(Comparator.comparingInt((ThreadPool p) -> -p.total()))
                .collect(Collectors.toList());
    }

    public String detectPoolName(String name) {
        if (name == null || name.isBlank()) {
            return "<unnamed-thread>";
        }
        for (PoolPattern pp : PATTERNS) {
            Matcher m = pp.pattern().matcher(name);
            if (m.matches()) {
                return m.group(pp.group());
            }
        }
        return name; // singleton — use full name as its own "pool"
    }

    private record PoolPattern(Pattern pattern, int group) {}
}
