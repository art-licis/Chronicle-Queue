package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.core.time.TimeProvider;
import net.openhft.chronicle.queue.DirectoryUtils;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.service.HelloWorld;
import net.openhft.chronicle.wire.DocumentContext;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class StoreTailerTest {
    private final Collection<SingleChronicleQueue> createdQueues = new ArrayList<>();
    private final Path dataDirectory = DirectoryUtils.tempDir(StoreTailerTest.class.getSimpleName()).toPath();

    @Test
    public void shouldHandleCycleRollWhenInReadOnlyMode() throws Exception {
        final MutableTimeProvider timeProvider = new MutableTimeProvider();
        final SingleChronicleQueue queue = build(createQueue(dataDirectory, RollCycles.MINUTELY, 0, "cycleRoll", false).
                timeProvider(timeProvider));

        final StringEvents events = queue.acquireAppender().methodWriterBuilder(StringEvents.class).build();
        timeProvider.setTime(System.currentTimeMillis());
        events.onEvent("firstEvent");
        timeProvider.addTime(2, TimeUnit.MINUTES);
        events.onEvent("secondEvent");

        final SingleChronicleQueue readerQueue = build(createQueue(dataDirectory, RollCycles.MINUTELY, 0, "cycleRoll", true).
                timeProvider(timeProvider));

        final ExcerptTailer tailer = readerQueue.createTailer();
        tailer.toStart();
        try (final DocumentContext context = tailer.readingDocument()) {
            assertThat(context.isPresent(), is(true));
        }
        tailer.toEnd();
        try (final DocumentContext context = tailer.readingDocument()) {
            assertThat(context.isPresent(), is(false));
        }
    }

    @Test
    public void shouldConsiderSourceIdWhenDeterminingLastWrittenIndex() throws Exception {
        final SingleChronicleQueue firstInputQueue =
                createQueue(dataDirectory, RollCycles.TEST_DAILY, 1, "firstInputQueue");
        // different RollCycle means that indicies are not identical to firstInputQueue
        final SingleChronicleQueue secondInputQueue =
                createQueue(dataDirectory, RollCycles.TEST_SECONDLY, 2, "secondInputQueue");
        final SingleChronicleQueue outputQueue =
                createQueue(dataDirectory, RollCycles.TEST_DAILY, 0, "outputQueue");;

        final StringEvents firstWriter = firstInputQueue.acquireAppender().
                methodWriterBuilder(StringEvents.class).get();
        final HelloWorld secondWriter = secondInputQueue.acquireAppender().
                methodWriterBuilder(HelloWorld.class).get();

        // generate some data in the input queues
        firstWriter.onEvent("one");
        firstWriter.onEvent("two");

        secondWriter.hello("thirteen");
        secondWriter.hello("thirtyOne");

        final StringEvents eventSink = outputQueue.acquireAppender().
                methodWriterBuilder(StringEvents.class).recordHistory(true).get();

        final CapturingStringEvents outputWriter = new CapturingStringEvents(eventSink);
        final MethodReader firstMethodReader = firstInputQueue.createTailer().methodReader(outputWriter);
        final MethodReader secondMethodReader = secondInputQueue.createTailer().methodReader(outputWriter);

        // replay events from the inputs into the output queue
        assertThat(firstMethodReader.readOne(), is(true));
        assertThat(firstMethodReader.readOne(), is(true));
        assertThat(secondMethodReader.readOne(), is(true));
        assertThat(secondMethodReader.readOne(), is(true));

        // ensures that tailer is not moved to index from the incorrect source
        secondInputQueue.createTailer().afterLastWritten(outputQueue);
    }

    @After
    public void after() throws Exception {
        closeQueues(createdQueues.toArray(new SingleChronicleQueue[0]));
    }

    @NotNull
    private SingleChronicleQueue createQueue(final Path dataDirectory, final RollCycles rollCycle,
                                             final int sourceId, final String subdirectory) {
        return build(createQueue(dataDirectory, rollCycle, sourceId,
                subdirectory, false));
    }

    @NotNull
    private SingleChronicleQueueBuilder createQueue(final Path dataDirectory, final RollCycles rollCycle,
                                             final int sourceId, final String subdirectory, final boolean readOnly) {
        return SingleChronicleQueueBuilder
                .binary(dataDirectory.resolve(Paths.get(subdirectory)))
                .sourceId(sourceId)
                .testBlockSize()
                .rollCycle(rollCycle)
                .readOnly(readOnly);
    }

    private SingleChronicleQueue build(final SingleChronicleQueueBuilder builder) {
        final SingleChronicleQueue queue = builder.build();
        createdQueues.add(queue);
        return queue;
    }

    @FunctionalInterface
    public interface StringEvents {
        void onEvent(final String event);
    }

    private static final class CapturingStringEvents implements StringEvents {
        private final StringEvents delegate;

        CapturingStringEvents(final StringEvents delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onEvent(final String event) {
            delegate.onEvent(event);
        }
    }

    private static final class MutableTimeProvider implements TimeProvider {
        private long currentTimeMillis;

        @Override
        public long currentTimeMillis() {
            return currentTimeMillis;
        }

        void setTime(final long millis) {
            this.currentTimeMillis = millis;
        }

        void addTime(final long duration, final TimeUnit unit) {
            this.currentTimeMillis += unit.toMillis(duration);
        }
    }

    private static void closeQueues(final SingleChronicleQueue... queues) {
        for (SingleChronicleQueue queue : queues) {
            if (queue != null) {
                queue.close();
            }
        }
    }
}