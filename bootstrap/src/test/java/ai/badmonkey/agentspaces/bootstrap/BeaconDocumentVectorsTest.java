/*
 * Copyright 2026 Bad Monkey, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.badmonkey.agentspaces.bootstrap;

import ai.badmonkey.agentspaces.common.crypto.Ed25519;
import ai.badmonkey.agentspaces.common.id.PeerId;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HLC round seven, 2026-11-08 (owner ruling F3 applied to the beacon document, issue §16 question
 * 6): a document that carries a signature arrives as exactly its canonical JSON, so the signature
 * covers the bytes received less the signature member, and no relabel of a signed document reads.
 * The vectors are the {@code beacon-document} rows of {@code agentspaces/tools/wire-encoding/vectors.json}.
 */
class BeaconDocumentVectorsTest {

    private static final Path WORKSPACE = Path.of("..", "..");
    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<JsonNode> vectors() throws IOException {
        Path file = WORKSPACE.resolve(Path.of("agentspaces", "tools", "wire-encoding", "vectors.json"));
        assertThat(file).as("the shared wire-encoding vectors").exists();
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode v : JSON.readTree(Files.readAllBytes(file)).get("vectors")) {
            if (v.get("carrier").asText().equals("beacon-document")) {
                out.add(v);
            }
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> everyBeaconVectorReadsAsItsVerdictSays() throws IOException {
        List<JsonNode> all = vectors();
        assertThat(all).hasSize(40);
        return all.stream().map(v -> DynamicTest.dynamicTest(v.get("id").asText() + " " + v.get("verdict").asText()
                + ": " + v.get("why").asText(), () -> {
            byte[] bytes = HEX.parseHex(v.get("hex").asText());
            if (v.get("verdict").asText().equals("accept")) {
                BeaconDocument document = BeaconDocument.parse(bytes);
                assertThat(document.signer().map(PeerId::value).orElse(null))
                        .isEqualTo(v.get("signer").isNull() ? null : v.get("signer").asText());
            } else {
                assertThatThrownBy(() -> BeaconDocument.parse(bytes)).isInstanceOf(IOException.class);
            }
        }));
    }

    @Test
    void javaSignsTheAcceptedVectorToTheSameBytes() throws IOException {
        JsonNode golden = JSON.readTree(Files.readAllBytes(WORKSPACE.resolve(Path.of("agentspaces-spec", "golden.json"))));
        PeerIdentity issuer = PeerIdentity.of(new KeyPair(
                Ed25519.publicKeyFromRaw(HEX.parseHex(golden.get("public_key_raw").asText())),
                Ed25519.privateKeyFromPkcs8(HEX.parseHex(golden.get("private_key_pkcs8").asText()))));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("region", "eu-west-1");
        parameters.put("a", "ü \"q\" \u0001 \u001f");
        BeaconDocument document = BeaconDocument.signed(issuer, Instant.parse("2026-10-14T00:00:00Z"),
                Duration.ofSeconds(300), List.of(
                        new BeaconDocument.Group("weekend-hack", "aspace://zG", "AA==", List.of(
                                new BeaconDocument.Seed("tls", "seed-a.corp.example:7500", 0),
                                new BeaconDocument.Seed("tcp", "127.0.0.1:7500", 1)),
                                Map.of("session", 1, "batch", 1), "239.255.42.99:7787", parameters, null),
                        new BeaconDocument.Group("bare", null, null, List.of(), null, null, null, null)));
        JsonNode accepted = vectors().get(0);
        assertThat(accepted.get("id").asText()).isEqualTo("beacon-01");
        assertThat(HEX.formatHex(document.toBytes())).isEqualTo(accepted.get("hex").asText());
    }
}
