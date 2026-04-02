package io.jvmdoctor.parser;

import io.jvmdoctor.model.LockInfo;
import io.jvmdoctor.model.StackFrame;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses jstack output into a ThreadDump model.
 */
public class JstackParser {

    // "Thread-0" #12 daemon prio=5 os_prio=0 cpu=0.00ms elapsed=0.10s tid=0x0000... nid=0x... waiting on condition  [0x...]
    private static final Pattern THREAD_HEADER = Pattern.compile(
            "^\"(.+?)\"\\s+(?:#(\\d+)\\s+)?(daemon\\s+)?(?:prio=(\\d+)\\s+)?.*(?:tid=\\S+)?.*$");

    // java.lang.Thread.State: BLOCKED (on object monitor)
    private static final Pattern THREAD_STATE = Pattern.compile(
            "^\\s+java\\.lang\\.Thread\\.State:\\s+(\\S+).*$");

    //  at com.example.Foo.bar(Foo.java:42)
    private static final Pattern STACK_FRAME = Pattern.compile(
            "^\\s+at\\s+(.+)\\.([^.(]+)\\((.+?)(?::(\\d+))?\\)\\s*$");

    //  - locked <0x000000076b572f00> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)
    //  - waiting to lock <0x000000076b572f00> (a java.lang.Object)
    //  - waiting on <0x000000076b572f00> (a java.lang.Object)
    private static final Pattern LOCK_LINE = Pattern.compile(
            "^\\s+- (locked|waiting to lock|waiting on|parking to wait for)\\s+<(\\S+)>\\s+\\(a ([^)]+)\\)\\s*$");

    // Timestamp: 2024-01-01 12:00:00
    private static final Pattern TIMESTAMP_LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})$");

    public ThreadDump parse(String rawDump) {
        String[] lines = rawDump.split("\\r?\\n");
        List<ThreadInfo> threads = new ArrayList<>();
        Instant timestamp = Instant.now();
        StringBuilder jvmInfo = new StringBuilder();

        int i = 0;

        // Parse header lines (before first thread block)
        while (i < lines.length) {
            String line = lines[i];
            if (line.startsWith("\"")) break;
            Matcher tsMatcher = TIMESTAMP_LINE.matcher(line);
            if (tsMatcher.matches()) {
                try {
                    timestamp = java.time.LocalDateTime
                            .parse(tsMatcher.group(1), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            .toInstant(java.time.ZoneOffset.UTC);
                } catch (Exception ignored) {}
            } else if (!line.isBlank()) {
                if (!jvmInfo.isEmpty()) jvmInfo.append("\n");
                jvmInfo.append(line);
            }
            i++;
        }

        // Parse thread blocks
        while (i < lines.length) {
            String line = lines[i];
            if (line.startsWith("\"")) {
                // Parse this thread block
                List<String> block = new ArrayList<>();
                block.add(line);
                i++;
                while (i < lines.length && !lines[i].startsWith("\"")) {
                    // Stop at blank line only if next non-blank starts a new thread or is EOF
                    if (lines[i].isBlank()) {
                        // peek ahead
                        int peek = i + 1;
                        while (peek < lines.length && lines[peek].isBlank()) peek++;
                        if (peek >= lines.length || lines[peek].startsWith("\"")) {
                            i = peek;
                            break;
                        }
                    }
                    block.add(lines[i]);
                    i++;
                }
                ThreadInfo thread = parseThreadBlock(block);
                if (thread != null) threads.add(thread);
            } else {
                i++;
            }
        }

        return new ThreadDump(timestamp, jvmInfo.toString(), threads);
    }

    private ThreadInfo parseThreadBlock(List<String> lines) {
        if (lines.isEmpty()) return null;
        String header = lines.get(0);

        Matcher hm = THREAD_HEADER.matcher(header);
        String name = hm.matches() ? hm.group(1) : header.replaceAll("\"", "").trim();
        boolean daemon = header.contains(" daemon ");
        int priority = 5;
        long tid = -1;

        // Extract priority
        Matcher prioM = Pattern.compile("prio=(\\d+)").matcher(header);
        if (prioM.find()) priority = Integer.parseInt(prioM.group(1));

        // Extract tid
        Matcher tidM = Pattern.compile("#(\\d+)").matcher(header);
        if (tidM.find()) tid = Long.parseLong(tidM.group(1));

        String state = "UNKNOWN";
        LockInfo waitingOnLock = null;
        List<LockInfo> heldLocks = new ArrayList<>();
        List<StackFrame> frames = new ArrayList<>();

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);

            Matcher sm = THREAD_STATE.matcher(line);
            if (sm.matches()) {
                state = sm.group(1);
                continue;
            }

            Matcher fm = STACK_FRAME.matcher(line);
            if (fm.matches()) {
                String className = fm.group(1);
                String methodName = fm.group(2);
                String fileName = fm.group(3);
                int lineNum = fm.group(4) != null ? Integer.parseInt(fm.group(4)) : -1;
                if ("Native Method".equals(fileName)) lineNum = -2;
                frames.add(new StackFrame(className, methodName, fileName, lineNum));
                continue;
            }

            Matcher lm = LOCK_LINE.matcher(line);
            if (lm.matches()) {
                String action = lm.group(1);
                String lockId = lm.group(2);
                String lockClass = lm.group(3);
                LockInfo li = new LockInfo(lockId, lockClass);
                if ("locked".equals(action)) {
                    heldLocks.add(li);
                } else {
                    waitingOnLock = li;
                }
            }
        }

        return new ThreadInfo(name, tid, state, priority, daemon, waitingOnLock, heldLocks, frames);
    }
}
