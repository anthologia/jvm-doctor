package io.jvmdoctor.analyzer;

import io.jvmdoctor.model.StackFrame;
import io.jvmdoctor.model.ThreadDump;
import io.jvmdoctor.model.ThreadInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlameGraphModelTest {

    private static ThreadInfo thread(String name, String state, StackFrame... frames) {
        return new ThreadInfo(name, 1, state, 5, false, null, List.of(), List.of(frames));
    }

    private static ThreadInfo thread(String name, StackFrame... frames) {
        return thread(name, "RUNNABLE", frames);
    }

    private static StackFrame frame(String cls, String method) {
        return new StackFrame(cls, method, cls + ".java", 1);
    }

    @Test
    void singleThreadProducesLinearTree() {
        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of(
                thread("t1",
                        frame("com.app.C", "leaf"),
                        frame("com.app.B", "middle"),
                        frame("com.app.A", "entry"))
        ));

        FlameGraphModel model = FlameGraphModel.build(dump, false);
        FlameGraphModel.Node root = model.root();

        assertEquals(1, root.totalCount());
        assertEquals(1, root.children().size());

        FlameGraphModel.Node entry = root.children().iterator().next();
        assertEquals("com.app.A.entry", entry.label());
        assertEquals(1, entry.totalCount());

        FlameGraphModel.Node middle = entry.children().iterator().next();
        assertEquals("com.app.B.middle", middle.label());

        FlameGraphModel.Node leaf = middle.children().iterator().next();
        assertEquals("com.app.C.leaf", leaf.label());
        assertTrue(leaf.isLeaf());
        assertEquals(1, leaf.selfCount());
    }

    @Test
    void sharedPrefixIsFolded() {
        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of(
                thread("t1",
                        frame("com.app.B", "doWork"),
                        frame("com.app.A", "main")),
                thread("t2",
                        frame("com.app.C", "doOther"),
                        frame("com.app.A", "main"))
        ));

        FlameGraphModel model = FlameGraphModel.build(dump, false);
        FlameGraphModel.Node root = model.root();

        assertEquals(2, root.totalCount());
        assertEquals(1, root.children().size());
        FlameGraphModel.Node main = root.children().iterator().next();
        assertEquals("com.app.A.main", main.label());
        assertEquals(2, main.totalCount());
        assertEquals(2, main.children().size());
    }

    @Test
    void hideJdkFiltersJdkFrames() {
        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of(
                thread("t1",
                        frame("com.app.Handler", "handle"),
                        frame("java.lang.Thread", "run"))
        ));

        FlameGraphModel withJdk = FlameGraphModel.build(dump, false);
        FlameGraphModel withoutJdk = FlameGraphModel.build(dump, true);

        FlameGraphModel.Node jdkEntry = withJdk.root().children().iterator().next();
        assertEquals("java.lang.Thread.run", jdkEntry.label());

        FlameGraphModel.Node appEntry = withoutJdk.root().children().iterator().next();
        assertEquals("com.app.Handler.handle", appEntry.label());
        assertTrue(appEntry.isLeaf());
    }

    @Test
    void emptyDumpProducesEmptyRoot() {
        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of());
        FlameGraphModel model = FlameGraphModel.build(dump, false);
        assertEquals(0, model.root().totalCount());
        assertTrue(model.root().children().isEmpty());
    }

    @Test
    void selfCountIsCorrect() {
        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of(
                thread("t1",
                        frame("com.app.B", "work"),
                        frame("com.app.A", "main")),
                thread("t2",
                        frame("com.app.A", "main"))
        ));

        FlameGraphModel model = FlameGraphModel.build(dump, false);
        FlameGraphModel.Node main = model.root().children().iterator().next();
        assertEquals(2, main.totalCount());
        assertEquals(1, main.selfCount());
        assertEquals(1, main.children().size());
    }

    @Test
    void nodesTrackThreadNamesAndStates() {
        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of(
                thread("worker-1", "BLOCKED",
                        frame("com.app.Lock", "acquire"),
                        frame("com.app.Main", "run")),
                thread("worker-2", "RUNNABLE",
                        frame("com.app.Compute", "calc"),
                        frame("com.app.Main", "run"))
        ));

        FlameGraphModel model = FlameGraphModel.build(dump, false);
        FlameGraphModel.Node main = model.root().children().iterator().next();

        assertEquals(2, main.threadNames().size());
        assertTrue(main.threadNames().contains("worker-1"));
        assertTrue(main.threadNames().contains("worker-2"));
        assertEquals(1, (int) main.stateCounts().get("BLOCKED"));
        assertEquals(1, (int) main.stateCounts().get("RUNNABLE"));
    }

    @Test
    void dominantStateReflectsHighestCount() {
        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of(
                thread("t1", "BLOCKED", frame("com.app.A", "m")),
                thread("t2", "BLOCKED", frame("com.app.A", "m")),
                thread("t3", "RUNNABLE", frame("com.app.A", "m"))
        ));

        FlameGraphModel model = FlameGraphModel.build(dump, false);
        FlameGraphModel.Node node = model.root().children().iterator().next();
        assertEquals("BLOCKED", node.dominantState());
        assertEquals(2, node.blockedCount());
        assertEquals(1, node.runnableCount());
    }

    @Test
    void reverseModePutsLeafFirst() {
        // Stack: top=C.leaf, middle=B.middle, bottom=A.entry
        List<ThreadInfo> threads = List.of(
                thread("t1",
                        frame("com.app.C", "leaf"),
                        frame("com.app.B", "middle"),
                        frame("com.app.A", "entry"))
        );

        FlameGraphModel normal = FlameGraphModel.build(threads, false, false);
        FlameGraphModel reverse = FlameGraphModel.build(threads, false, true);

        // Normal (icicle): root -> A.entry -> B.middle -> C.leaf
        FlameGraphModel.Node normalFirst = normal.root().children().iterator().next();
        assertEquals("com.app.A.entry", normalFirst.label());

        // Reverse (bottom-up): root -> C.leaf -> B.middle -> A.entry
        FlameGraphModel.Node reverseFirst = reverse.root().children().iterator().next();
        assertEquals("com.app.C.leaf", reverseFirst.label());
        assertTrue(reverse.isReversed());
    }

    @Test
    void stateRatioCalculation() {
        ThreadDump dump = new ThreadDump(Instant.now(), "", List.of(
                thread("t1", "WAITING", frame("com.app.A", "m")),
                thread("t2", "WAITING", frame("com.app.A", "m")),
                thread("t3", "WAITING", frame("com.app.A", "m")),
                thread("t4", "RUNNABLE", frame("com.app.A", "m"))
        ));

        FlameGraphModel model = FlameGraphModel.build(dump, false);
        FlameGraphModel.Node node = model.root().children().iterator().next();
        assertEquals(0.75, node.stateRatio("WAITING"), 0.001);
        assertEquals(0.25, node.stateRatio("RUNNABLE"), 0.001);
        assertEquals(0.0, node.stateRatio("BLOCKED"), 0.001);
    }
}
