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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The beacon document (SRAD appendix C): what a beacon serves at
 * {@code /.well-known/agentspaces/beacon.json} and a social beacon under a
 * lookup code. Per group: the name, the {@code aspace://} id, the signed
 * founding advertisement (base64 CBOR), the seeds, the wire hints, the
 * multicast group, free parameters, and at a social beacon the public
 * delegation. Document-wide: the issuer PeerId, its raw key, the issue time,
 * a time to live in seconds, and the issuer's Ed25519 signature over the
 * canonical bytes (every field but {@code signature}, in declared order, no
 * whitespace, map keys sorted). A reader ignores unknown fields. The document
 * never carries a content key, a credential, or a membership hint, and the
 * writer refuses parameters by those names.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"v", "issuer", "issuerKey", "issued", "ttl", "groups", "signature"})
public record BeaconDocument(int v,
                             String issuer,
                             String issuerKey,
                             String issued,
                             long ttl,
                             List<Group> groups,
                             String signature) {

    /** The document version this library writes. */
    public static final int VERSION = 1;
    /** The well-known path (RFC 8615 shape). */
    public static final String WELL_KNOWN = "/.well-known/agentspaces/beacon.json";
    /** Parameter names a document never carries. */
    public static final List<String> FORBIDDEN_PARAMETERS = List.of("content-key", "contentkey", "credential",
            "membership", "hints", "secret", "invite-secret", "join-credential");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.INDENT_OUTPUT);

    /** One seed. */
    @JsonPropertyOrder({"transport", "address", "priority"})
    public record Seed(String transport, String address, int priority) {
    }

    /** One group. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"name", "id", "founding", "seeds", "wire", "multicast", "parameters", "invite"})
    public record Group(String name,
                        String id,
                        String founding,
                        List<Seed> seeds,
                        Map<String, Integer> wire,
                        String multicast,
                        Map<String, String> parameters,
                        String invite) {

        public Group {
            Objects.requireNonNull(name, "name");
            seeds = seeds == null ? List.of() : List.copyOf(seeds);
            if (parameters != null) {
                for (String key : parameters.keySet()) {
                    if (FORBIDDEN_PARAMETERS.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                        throw new IllegalArgumentException("a beacon document never carries '" + key + "'");
                    }
                }
            }
        }
    }

    public BeaconDocument {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /**
     * Builds and signs a document.
     *
     * @param issuer the beacon's identity
     * @param issued when
     * @param ttl    how long a reader may reuse it
     * @param groups the groups
     * @return the signed document
     */
    public static BeaconDocument signed(PeerIdentity issuer, Instant issued, Duration ttl, List<Group> groups) {
        Objects.requireNonNull(issuer, "issuer");
        BeaconDocument unsigned = new BeaconDocument(VERSION, issuer.peerId().value(),
                Base64.getEncoder().encodeToString(issuer.rawPublicKey()), issued.toString(), ttl.toSeconds(),
                groups, null);
        byte[] signature = issuer.sign(unsigned.canonicalBytes());
        return unsigned.withSignature(Base64.getEncoder().encodeToString(signature));
    }

    /** The same document with a signature. */
    public BeaconDocument withSignature(String encoded) {
        return new BeaconDocument(v, issuer, issuerKey, issued, ttl, groups, encoded);
    }

    /** The bytes the signature covers: the document without its signature, canonically. */
    public byte[] canonicalBytes() {
        try {
            return MAPPER.writeValueAsBytes(withSignature(null));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The document as JSON, one line. */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The document as JSON bytes. */
    public byte[] toBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses a document; unknown fields are ignored.
     *
     * @param bytes the JSON
     * @return the document
     * @throws IOException when the bytes are not a document
     */
    public static BeaconDocument parse(byte[] bytes) throws IOException {
        BeaconDocument document = MAPPER.readValue(bytes, BeaconDocument.class);
        if (document.v() < 1) {
            throw new IOException("not a beacon document: v=" + document.v());
        }
        return document;
    }

    /**
     * Whether the signature verifies under the carried key and the key hashes
     * to the carried issuer. A document without a key or signature does not
     * verify.
     *
     * @return {@code true} when the document is the issuer's
     */
    public boolean verifies() {
        return signer().isPresent();
    }

    /**
     * The verified signer: the PeerId the carried key hashes to, when the
     * signature verifies under it and it matches {@code issuer}.
     *
     * @return the signer, or empty
     */
    public Optional<PeerId> signer() {
        if (issuerKey == null || signature == null || issuer == null) {
            return Optional.empty();
        }
        try {
            byte[] key = Base64.getDecoder().decode(issuerKey);
            if (key.length != Ed25519.RAW_PUBLIC_KEY_LENGTH) {
                return Optional.empty();
            }
            PeerId signer = PeerId.fromPublicKey(key);
            if (!signer.value().equals(issuer)) {
                return Optional.empty();
            }
            byte[] sig = Base64.getDecoder().decode(signature);
            return Ed25519.verifyRaw(key, canonicalBytes(), sig) ? Optional.of(signer) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The group with the given {@code aspace://} id or bare id, if any. */
    public Optional<Group> groupById(String id) {
        String wanted = DnsSdLocator.parseId(id).map(g -> g.value()).orElse(null);
        if (wanted == null) {
            return Optional.empty();
        }
        for (Group group : groups) {
            if (group.id() != null && DnsSdLocator.parseId(group.id()).map(g -> g.value().equals(wanted)).orElse(false)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    /** The group with the given name, if any. */
    public Optional<Group> groupByName(String name) {
        for (Group group : groups) {
            if (group.name().equals(name)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }
}
