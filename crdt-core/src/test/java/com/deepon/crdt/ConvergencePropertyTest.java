package com.deepon.crdt;

import com.deepon.crdt.doc.YDoc;
import com.deepon.crdt.model.ClientId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exit gate for the CRDT: five replicas, random concurrent edits, and
 * every replica receiving every update in its own independently shuffled
 * order. All five must end up identical.
 *
 * <p>This is the test that catches what hand-written examples cannot. A merge
 * bug involving three-way concurrency or a transitive origin only appears
 * under a specific interleaving, so the useful strategy is volume plus
 * reproducibility rather than cleverness.
 *
 * <p>Seeds are derived from a fixed root seed, so a failure is reproducible:
 * the assertion message carries the iteration seed, and
 * {@link #replayASingleSeed()} exists to re-run one in isolation. No
 * property-testing dependency is used — the repository already favours
 * seeded loops for its concurrency tests, and a printed seed gives the same
 * debuggability as shrinking for a fraction of the machinery.
 */
class ConvergencePropertyTest {

    private static final long ROOT_SEED = 20260916L;
    private static final int ITERATIONS = 1000;
    private static final int REPLICA_COUNT = 5;

    @Test
    void aThousandRandomPermutationsAcrossFiveReplicasAllConverge() {
        Random seeds = new Random(ROOT_SEED);
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            long seed = seeds.nextLong();
            try {
                runOneScenario(seed);
            } catch (AssertionError | RuntimeException failure) {
                throw new AssertionError(
                        "convergence failed on iteration " + iteration + " with seed " + seed
                                + " — replay it with replayASingleSeed()", failure);
            }
        }
    }

    @Test
    void replayASingleSeed() {
        // A place to pin a failing seed from the run above while debugging.
        runOneScenario(ROOT_SEED);
    }

    @Test
    void convergenceHoldsWhenEveryUpdateIsDeliveredTwice() {
        Random random = new Random(ROOT_SEED * 31);
        for (int iteration = 0; iteration < 50; iteration++) {
            runOneScenario(random.nextLong(), true);
        }
    }

    private void runOneScenario(long seed) {
        runOneScenario(seed, false);
    }

    private void runOneScenario(long seed, boolean deliverTwice) {
        Random random = new Random(seed);

        List<Replica> replicas = new ArrayList<>();
        for (int i = 0; i < REPLICA_COUNT; i++) {
            // Deterministic client ids so a seed fully determines the run,
            // including which way every tie breaks.
            replicas.add(new Replica("r" + i, new ClientId(new java.util.UUID(seed + i, i))));
        }

        // Start from common ground so every replica's local indexes are valid.
        Replica seeder = replicas.get(0);
        seeder.insert(0, "the quick brown fox");
        Replica.syncAll(replicas);
        replicas.forEach(Replica::clearOutbox);

        // Concurrent phase: nobody syncs, so all of this is genuinely simultaneous.
        for (Replica replica : replicas) {
            int operations = 1 + random.nextInt(3);
            for (int op = 0; op < operations; op++) {
                applyRandomOperation(replica, random);
            }
        }

        // Collect every update produced, then hand the whole pile to every
        // replica in a different random order each time.
        List<byte[]> allUpdates = new ArrayList<>();
        for (Replica replica : replicas) {
            allUpdates.addAll(replica.outbox());
        }

        for (Replica replica : replicas) {
            List<byte[]> delivery = new ArrayList<>(allUpdates);
            if (deliverTwice) {
                delivery.addAll(allUpdates);
            }
            Collections.shuffle(delivery, random);
            for (byte[] update : delivery) {
                replica.receive(update);
            }
        }

        assertConverged(replicas, seed);
    }

    private void applyRandomOperation(Replica replica, Random random) {
        int length = replica.text().length();
        boolean canDelete = length > 1;
        if (canDelete && random.nextInt(3) == 0) {
            int index = random.nextInt(length);
            int count = 1 + random.nextInt(Math.min(4, length - index));
            replica.delete(index, count);
        } else {
            int index = random.nextInt(length + 1);
            replica.insert(index, randomWord(random));
        }
    }

    private String randomWord(Random random) {
        String[] words = {"alpha", "b", "gamma!", "-", "😀", "zeta", "xy"};
        return words[random.nextInt(words.length)];
    }

    private void assertConverged(List<Replica> replicas, long seed) {
        Replica reference = replicas.get(0);
        String expectedText = reference.value();
        String expectedCanonical = Canonical.of(reference.doc());

        for (Replica replica : replicas) {
            assertThat(replica.value())
                    .as("seed %s: %s text differs from %s", seed, replica.name(), reference.name())
                    .isEqualTo(expectedText);
            assertThat(Canonical.of(replica.doc()))
                    .as("seed %s: %s canonical state differs from %s", seed, replica.name(), reference.name())
                    .isEqualTo(expectedCanonical);
        }

        // A replica rebuilt purely from the wire format must match too,
        // which puts encode and decode on the same hook as the merge.
        YDoc rebuilt = new YDoc(ClientId.random());
        Updates.apply(rebuilt, Updates.encodeState(reference.doc()));
        assertThat(rebuilt.text())
                .as("seed %s: a replica rebuilt from encodeState does not match", seed)
                .isEqualTo(expectedText);
        assertThat(Canonical.of(rebuilt))
                .as("seed %s: rebuilt canonical state does not match", seed)
                .isEqualTo(expectedCanonical);
    }
}
