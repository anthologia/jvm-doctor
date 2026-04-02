package io.jvmdoctor.parser;

import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JstackParserTest {

    private JstackParser parser;

    @BeforeEach
    void setUp() {
        parser = new JstackParser();
    }

    @Test
    void parsesThreadCount() {
        ThreadDump dump = parser.parse(SAMPLE_DUMP);
        assertEquals(3, dump.threads().size());
    }

    @Test
    void parsesThreadNames() {
        ThreadDump dump = parser.parse(SAMPLE_DUMP);
        assertTrue(dump.threads().stream().anyMatch(t -> t.name().equals("main")));
        assertTrue(dump.threads().stream().anyMatch(t -> t.name().equals("Thread-1")));
    }

    @Test
    void parsesBlockedState() {
        ThreadDump dump = parser.parse(SAMPLE_DUMP);
        ThreadInfo blocked = dump.threads().stream()
                .filter(t -> t.name().equals("Thread-1"))
                .findFirst().orElseThrow();
        assertEquals("BLOCKED", blocked.state());
        assertTrue(blocked.isBlocked());
    }

    @Test
    void parsesWaitingOnLock() {
        ThreadDump dump = parser.parse(SAMPLE_DUMP);
        ThreadInfo blocked = dump.threads().stream()
                .filter(t -> t.name().equals("Thread-1"))
                .findFirst().orElseThrow();
        assertNotNull(blocked.waitingOnLock());
        assertEquals("0xabcdef01", blocked.waitingOnLock().lockId());
    }

    @Test
    void parsesHeldLocks() {
        ThreadDump dump = parser.parse(SAMPLE_DUMP);
        ThreadInfo t = dump.threads().stream()
                .filter(ti -> ti.name().equals("Thread-2"))
                .findFirst().orElseThrow();
        assertFalse(t.heldLocks().isEmpty());
        assertEquals("0xabcdef01", t.heldLocks().get(0).lockId());
    }

    @Test
    void parsesStackFrames() {
        ThreadDump dump = parser.parse(SAMPLE_DUMP);
        ThreadInfo main = dump.threads().stream()
                .filter(t -> t.name().equals("main"))
                .findFirst().orElseThrow();
        assertFalse(main.stackFrames().isEmpty());
        assertEquals("com.example.Main", main.stackFrames().get(0).className());
    }

    @Test
    void stateDistributionIsCorrect() {
        ThreadDump dump = parser.parse(SAMPLE_DUMP);
        var dist = dump.stateDistribution();
        assertEquals(1L, dist.getOrDefault("RUNNABLE", 0L));
        assertEquals(1L, dist.getOrDefault("BLOCKED", 0L));
        assertEquals(1L, dist.getOrDefault("WAITING", 0L));
    }

    @Test
    void emptyDumpReturnsEmptyThreadList() {
        ThreadDump dump = parser.parse("");
        assertTrue(dump.threads().isEmpty());
    }

    private static final String SAMPLE_DUMP = """
            2024-01-01 12:00:00
            Full thread dump OpenJDK 64-Bit Server VM (21 mixed mode):

            "main" #1 prio=5 os_prio=0 tid=0x00000001 nid=0x100 runnable
               java.lang.Thread.State: RUNNABLE
            \tat com.example.Main.main(Main.java:10)

            "Thread-1" #2 prio=5 os_prio=0 tid=0x00000002 nid=0x101 waiting for monitor entry
               java.lang.Thread.State: BLOCKED (on object monitor)
            \tat com.example.Worker.run(Worker.java:55)
            \t- waiting to lock <0xabcdef01> (a java.lang.Object)

            "Thread-2" #3 prio=5 os_prio=0 tid=0x00000003 nid=0x102 waiting on condition
               java.lang.Thread.State: WAITING (parking)
            \tat java.lang.Object.wait(Native Method)
            \t- locked <0xabcdef01> (a java.lang.Object)
            \tat com.example.Producer.produce(Producer.java:30)
            """;
}
