package com.deepon.crdt;

import com.deepon.crdt.doc.YDoc;
import com.deepon.crdt.doc.YText;
import com.deepon.crdt.model.ActorRef;
import com.deepon.crdt.model.ClientId;
import com.deepon.crdt.model.StateVector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates and checks the cross-implementation conformance vectors.
 *
 * <p>Stage 3 ends up with the same algorithm implemented twice: here, and in
 * JavaScript in the browser so typing has zero latency. Two implementations
 * of a merge algorithm that must agree byte for byte is the single largest
 * correctness risk in the stage, and the failure mode is quiet — documents
 * that diverge only under a particular interleaving, on one client, in
 * production.
 *
 * <p>These vectors are the control. Each one is a fully explicit script:
 * fixed client ids, a numbered list of edits, and an exact delivery order of
 * updates between replicas. The expected text, canonical state and encoded
 * bytes are recorded alongside. The JavaScript port replays the same scripts
 * and must produce the same three answers, so drift fails a test instead of
 * corrupting a document.
 *
 * <p>The file is committed, and this test asserts against it. Regenerate
 * deliberately with {@code -Dconformance.write=true} — and treat a diff in
 * that file as a wire-format change that needs the JS port updated in the
 * same commit.
 */
class ConformanceVectorTest {

    private static final Path VECTOR_FILE = Path.of("src/test/resources/conformance/vectors.json");

    @Test
    void vectorsMatchTheCommittedFile() throws IOException {
        String generated = buildAllScenarios();

        if (Boolean.getBoolean("conformance.write")) {
            Files.createDirectories(VECTOR_FILE.getParent());
            Files.writeString(VECTOR_FILE, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(Files.exists(VECTOR_FILE))
                .as("conformance vectors are missing — generate them with -Dconformance.write=true")
                .isTrue();
        assertThat(Files.readString(VECTOR_FILE, StandardCharsets.UTF_8))
                .as("conformance vectors changed. If the wire format or merge result genuinely changed, "
                        + "regenerate with -Dconformance.write=true and update the JavaScript port in the same commit")
                .isEqualTo(generated);
    }

    private String buildAllScenarios() {
        List<String> scenarios = new ArrayList<>();
        scenarios.add(mondayTuesday());
        scenarios.add(samePositionSingleCharacters());
        scenarios.add(insertAnchoredToTombstone());
        scenarios.add(reverseDelivery());
        scenarios.add(midRunSplit());
        scenarios.add(codePointHandling());
        scenarios.add(threeWayAtOnePosition());

        StringBuilder json = new StringBuilder();
        json.append("{\n  \"formatVersion\": 1,\n  \"scenarios\": [\n");
        for (int i = 0; i < scenarios.size(); i++) {
            json.append(scenarios.get(i));
            if (i < scenarios.size() - 1) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    // ------------------------------------------------------------- scenarios

    private String mondayTuesday() {
        Scenario s = new Scenario("monday-tuesday", 2);
        s.insert(0, 0, "Monday");
        s.insert(1, 0, "Tuesday");
        s.deliver(0, 1);
        s.deliver(1, 0);
        return s.finish();
    }

    private String samePositionSingleCharacters() {
        Scenario s = new Scenario("same-position-single-characters", 2);
        s.insert(0, 0, "Hi");
        s.deliver(0, 1);
        s.insert(0, 2, "!");
        s.insert(1, 2, "?");
        s.deliver(1, 1);
        s.deliver(2, 0);
        return s.finish();
    }

    private String insertAnchoredToTombstone() {
        Scenario s = new Scenario("insert-anchored-to-tombstone", 2);
        s.insert(0, 0, "Hi");
        s.deliver(0, 1);
        s.delete(1, 1, 1);      // client 1 removes the "i"
        s.insert(0, 2, "!");    // client 0, not knowing, types after it
        s.deliver(1, 0);
        s.deliver(2, 1);
        return s.finish();
    }

    private String reverseDelivery() {
        Scenario s = new Scenario("reverse-delivery", 2);
        s.insert(0, 0, "a");
        s.insert(0, 1, "b");
        s.insert(0, 2, "c");
        // Backwards on purpose: the peer must park and drain.
        s.deliver(2, 1);
        s.deliver(1, 1);
        s.deliver(0, 1);
        return s.finish();
    }

    private String midRunSplit() {
        Scenario s = new Scenario("mid-run-split", 2);
        s.insert(0, 0, "abcdef");
        s.deliver(0, 1);
        s.insert(1, 3, "-");
        s.deliver(1, 0);
        return s.finish();
    }

    private String codePointHandling() {
        Scenario s = new Scenario("code-point-handling", 2);
        // Non-BMP emoji, a combining acute accent, and CJK.
        s.insert(0, 0, "ab");
        s.insert(0, 1, "😀");
        s.insert(0, 3, "é");
        s.insert(0, 0, "你好");
        s.deliver(0, 1);
        s.deliver(1, 1);
        s.deliver(2, 1);
        s.deliver(3, 1);
        return s.finish();
    }

    private String threeWayAtOnePosition() {
        Scenario s = new Scenario("three-way-at-one-position", 3);
        s.insert(0, 0, "base");
        s.deliver(0, 1);
        s.deliver(0, 2);
        s.insert(0, 4, "-one");
        s.insert(1, 4, "-two");
        s.insert(2, 4, "-three");
        // Every update to every other replica, in a fixed order.
        s.deliver(1, 1);
        s.deliver(1, 2);
        s.deliver(2, 0);
        s.deliver(2, 2);
        s.deliver(3, 0);
        s.deliver(3, 1);
        return s.finish();
    }

    // --------------------------------------------------------------- builder

    /**
     * Records a scenario as it runs: the edits, the exact delivery order, and
     * the resulting state of every replica.
     */
    private static final class Scenario {

        private final String name;
        private final List<ClientId> clientIds = new ArrayList<>();
        private final List<YDoc> docs = new ArrayList<>();
        private final List<YText> texts = new ArrayList<>();
        private final List<byte[]> updates = new ArrayList<>();
        private final List<String> steps = new ArrayList<>();

        Scenario(String name, int clientCount) {
            this.name = name;
            for (int i = 0; i < clientCount; i++) {
                // Fixed, readable ids so the tie-break direction is pinned
                // and the JavaScript port can hard-code the same values.
                ClientId client = new ClientId(
                        UUID.fromString(("0000000%d-0000-4000-8000-00000000000%d").formatted(i + 1, i + 1)));
                clientIds.add(client);
                YDoc doc = new YDoc(client);
                docs.add(doc);
                texts.add(new YText(doc));
            }
        }

        void insert(int client, int index, String text) {
            StateVector before = docs.get(client).stateVector();
            texts.get(client).insert(index, text, actorFor(client));
            int updateIndex = capture(client, before);
            steps.add(("    { \"op\": \"insert\", \"client\": %d, \"index\": %d, \"text\": %s, \"update\": %d }")
                    .formatted(client, index, quote(text), updateIndex));
        }

        void delete(int client, int index, int length) {
            StateVector before = docs.get(client).stateVector();
            texts.get(client).delete(index, length);
            int updateIndex = capture(client, before);
            steps.add(("    { \"op\": \"delete\", \"client\": %d, \"index\": %d, \"length\": %d, \"update\": %d }")
                    .formatted(client, index, length, updateIndex));
        }

        void deliver(int updateIndex, int toClient) {
            Updates.apply(docs.get(toClient), updates.get(updateIndex));
            steps.add(("    { \"op\": \"deliver\", \"update\": %d, \"to\": %d }")
                    .formatted(updateIndex, toClient));
        }

        private int capture(int client, StateVector before) {
            updates.add(Updates.encodeDelta(docs.get(client), before));
            return updates.size() - 1;
        }

        private ActorRef actorFor(int client) {
            return new ActorRef("user:c" + client, "HUMAN");
        }

        String finish() {
            StringBuilder json = new StringBuilder();
            json.append("    {\n");
            json.append("      \"name\": ").append(quote(name)).append(",\n");

            json.append("      \"clients\": [");
            for (int i = 0; i < clientIds.size(); i++) {
                json.append(i == 0 ? "" : ", ").append(quote(clientIds.get(i).toString()));
            }
            json.append("],\n");

            json.append("      \"steps\": [\n");
            for (int i = 0; i < steps.size(); i++) {
                json.append("  ").append(steps.get(i));
                if (i < steps.size() - 1) {
                    json.append(',');
                }
                json.append('\n');
            }
            json.append("      ],\n");

            json.append("      \"updatesHex\": [\n");
            for (int i = 0; i < updates.size(); i++) {
                json.append("        ").append(quote(hex(updates.get(i))));
                if (i < updates.size() - 1) {
                    json.append(',');
                }
                json.append('\n');
            }
            json.append("      ],\n");

            json.append("      \"expected\": [\n");
            for (int i = 0; i < docs.size(); i++) {
                YDoc doc = docs.get(i);
                json.append("        {\n");
                json.append("          \"client\": ").append(i).append(",\n");
                json.append("          \"text\": ").append(quote(doc.text())).append(",\n");
                json.append("          \"canonical\": ").append(quote(Canonical.of(doc))).append("\n");
                json.append("        }");
                if (i < docs.size() - 1) {
                    json.append(',');
                }
                json.append('\n');
            }
            json.append("      ]\n");
            json.append("    }");
            return json.toString();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * JSON string literal, escaping everything above ASCII as {@code \\uXXXX}.
     * Keeps the committed file pure ASCII so it cannot be mangled by an
     * editor or a checkout with different encoding settings, while
     * {@code JSON.parse} still recombines surrogate pairs correctly.
     */
    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
