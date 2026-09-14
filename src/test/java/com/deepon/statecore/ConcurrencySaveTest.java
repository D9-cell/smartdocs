package com.deepon.statecore;

import com.deepon.statecore.domain.Document;
import com.deepon.statecore.error.VersionMismatchException;
import com.deepon.statecore.service.DocumentService;
import com.deepon.statecore.support.AbstractPostgresTest;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design doc section 9, "Concurrency" row, spelled out exactly:
 *   1. Create a document, capture version 1.
 *   2. Two threads await one latch.
 *   3. Both send If-Match: "1" with different content.
 *   4. Release the latch.
 *   5. Assert exactly one 200 and one 412.
 *   6. Assert document.version equals 2 and revision count equals 2.
 * "Run it 100 times in a loop. A race passing once proves nothing" — this
 * runs the whole scenario 100 times via @RepeatedTest, each against a fresh
 * document, against a real PostgreSQL row lock (no mocking).
 */
@SpringBootTest
class ConcurrencySaveTest extends AbstractPostgresTest {

    @Autowired
    private DocumentService documentService;

    @RepeatedTest(100)
    void exactlyOneOfTwoConcurrentSameVersionSavesSucceeds() throws Exception {
        Document created = documentService.create("Race", "initial");
        UUID id = created.getId();
        long baseVersion = created.getVersion();
        assertThat(baseVersion).isEqualTo(1L);

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        try {
            List<Future<?>> futures = List.of(
                    pool.submit(saveAttempt(id, baseVersion, "from thread A", startLatch, successCount, conflictCount)),
                    pool.submit(saveAttempt(id, baseVersion, "from thread B", startLatch, successCount, conflictCount))
            );
            startLatch.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        Document finalState = documentService.get(id);
        assertThat(finalState.getVersion()).isEqualTo(2L);
        assertThat(documentService.listRevisions(id)).hasSize(2);
    }

    private Runnable saveAttempt(UUID id, long baseVersion, String content, CountDownLatch startLatch,
                                  AtomicInteger successCount, AtomicInteger conflictCount) {
        return () -> {
            try {
                startLatch.await();
                documentService.updateContent(id, baseVersion, content, null);
                successCount.incrementAndGet();
            } catch (VersionMismatchException e) {
                conflictCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
