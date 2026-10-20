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

import ai.badmonkey.agentspaces.api.ad.PeerAdvertisement;
import ai.badmonkey.agentspaces.api.spi.BeaconHint;
import ai.badmonkey.agentspaces.api.spi.BeaconLocator;
import ai.badmonkey.agentspaces.api.spi.BeaconRequest;
import ai.badmonkey.agentspaces.common.id.GroupId;
import ai.badmonkey.agentspaces.common.id.PeerId;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Fetches beacon documents (SRAD DB-L-07): HTTPS by default (plain HTTP only
 * when allowed), a deadline, a size cap, at most two redirects on the same
 * host, no credentials and no cookies, private-address targets refused
 * unless allowed, an optional host allow-list. The document's signature is
 * verified when it carries one; when a beacon PeerId is pinned, a document
 * not signed by it is refused. The group is selected by the request's
 * GroupId when known, else by name; its founding, seeds, wire hints, and
 * parameters become the hint, with the document's TTL.
 */
public final class UrlLocator implements BeaconLocator {

    /** The locator's name; the chain honours its GroupId claim by default. */
    public static final String NAME = "url";

    /**
     * The guards.
     *
     * @param allowPlain   permit {@code http://}
     * @param allowHosts   the only hosts fetched, or empty for any
     * @param allowPrivate permit loopback, link-local, and site-local targets
     * @param maxBytes     the largest document read
     * @param timeout      the per-request deadline
     * @param beaconPeer   the PeerId that must have signed, or empty
     */
    public record Options(boolean allowPlain, Set<String> allowHosts, boolean allowPrivate, int maxBytes,
                          Duration timeout, Optional<PeerId> beaconPeer) {

        public Options {
            allowHosts = Set.copyOf(allowHosts);
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(beaconPeer, "beaconPeer");
        }

        /** HTTPS only, any host, no private targets, 64 KiB, 3 s, no pin. */
        public static Options defaults() {
            return new Options(false, Set.of(), false, 64 * 1024, Duration.ofSeconds(3), Optional.empty());
        }

        public Options withAllowPlain(boolean value) {
            return new Options(value, allowHosts, allowPrivate, maxBytes, timeout, beaconPeer);
        }

        public Options withAllowPrivate(boolean value) {
            return new Options(allowPlain, allowHosts, value, maxBytes, timeout, beaconPeer);
        }

        public Options withAllowHosts(Set<String> hosts) {
            return new Options(allowPlain, hosts, allowPrivate, maxBytes, timeout, beaconPeer);
        }

        public Options withBeaconPeer(PeerId peer) {
            return new Options(allowPlain, allowHosts, allowPrivate, maxBytes, timeout, Optional.ofNullable(peer));
        }

        public Options withTimeout(Duration value) {
            return new Options(allowPlain, allowHosts, allowPrivate, maxBytes, value, beaconPeer);
        }
    }

    private final List<URI> urls;
    private final Options options;
    private final HttpClient client;

    /**
     * A locator over configured URLs.
     *
     * @param urls    the beacon document URLs, in order
     * @param options the guards
     * @param client  the client, or null for a default one that never follows redirects itself
     */
    public UrlLocator(List<URI> urls, Options options, HttpClient client) {
        this.urls = List.copyOf(Objects.requireNonNull(urls, "urls"));
        this.options = Objects.requireNonNull(options, "options");
        this.client = client != null ? client : HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(options.timeout())
                .build();
    }

    /** A locator with no configured URLs, used by locators that follow {@code url=} records. */
    public static UrlLocator follower(Options options, HttpClient client) {
        return new UrlLocator(List.of(), options, client);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public BeaconHint locate(BeaconRequest request) throws IOException {
        List<PeerAdvertisement.Endpoint> endpoints = new ArrayList<>();
        Map<String, String> parameters = new LinkedHashMap<>();
        Optional<GroupId> claimed = Optional.empty();
        Optional<byte[]> founding = Optional.empty();
        Duration ttl = null;
        List<String> evidence = new ArrayList<>();
        IOException lastError = null;
        for (URI url : urls) {
            BeaconHint hint;
            try {
                hint = fetch(url.toString(), request);
            } catch (IOException e) {
                lastError = e;
                evidence.add("GET " + url + " failed: " + e.getMessage());
                continue;
            }
            if (hint.isEmpty()) {
                evidence.add(hint.evidence());
                continue;
            }
            for (PeerAdvertisement.Endpoint endpoint : hint.endpoints()) {
                endpoints.add(new PeerAdvertisement.Endpoint(endpoint.transport(), endpoint.address(), endpoints.size()));
            }
            if (founding.isEmpty()) {
                founding = hint.founding();
            }
            if (claimed.isEmpty()) {
                claimed = hint.groupId();
            }
            hint.parameters().forEach(parameters::putIfAbsent);
            if (!hint.ttl().isZero() && (ttl == null || hint.ttl().compareTo(ttl) < 0)) {
                ttl = hint.ttl();
            }
            evidence.add(hint.evidence());
        }
        if (endpoints.isEmpty() && founding.isEmpty()) {
            if (lastError != null && urls.size() == 1) {
                throw lastError;
            }
            return BeaconHint.none();
        }
        return new BeaconHint(endpoints, claimed, founding, parameters, ttl == null ? BeaconHint.DEFAULT_TTL : ttl,
                String.join("; ", evidence));
    }

    /**
     * Fetches one document and reads the request's group out of it.
     *
     * @param url     the URL
     * @param request the request
     * @return the hint; {@link BeaconHint#none()} with evidence when the document has no such group
     * @throws IOException when the fetch fails or the guards refuse it
     */
    public BeaconHint fetch(String url, BeaconRequest request) throws IOException {
        byte[] body = get(URI.create(url.trim()), 0);
        BeaconDocument document;
        try {
            document = BeaconDocument.parse(body);
        } catch (IOException | RuntimeException e) {
            throw new IOException("not a beacon document at " + url + ": " + e.getMessage());
        }
        return hint(document, request, options, "GET " + url);
    }

    /**
     * Reads the request's group out of a document.
     *
     * @param document the document
     * @param request  the request
     * @param options  the guards (the pin)
     * @param evidence what to say
     * @return the hint
     * @throws IOException when the document's signature is required and does not verify
     */
    public static BeaconHint hint(BeaconDocument document, BeaconRequest request, Options options, String evidence)
            throws IOException {
        Optional<PeerId> signer = document.signer();
        if (document.signature() != null && signer.isEmpty()) {
            throw new IOException(evidence + ": the document's signature does not verify");
        }
        if (options.beaconPeer().isPresent()) {
            if (signer.isEmpty() || !signer.get().equals(options.beaconPeer().get())) {
                throw new IOException(evidence + ": the document is not signed by the pinned beacon "
                        + options.beaconPeer().get().display());
            }
        }
        Optional<BeaconDocument.Group> group = request.groupId().isPresent()
                ? document.groupById(request.groupId().get().value())
                : Optional.empty();
        if (group.isEmpty() && request.groupId().isEmpty()) {
            group = document.groupByName(request.groupName());
        }
        if (group.isEmpty()) {
            return new BeaconHint(List.of(), Optional.empty(), Optional.empty(), Map.of(), Duration.ZERO,
                    evidence + ": no group " + request.groupId().map(g -> g.value()).orElse(request.groupName()));
        }
        BeaconDocument.Group g = group.get();
        List<PeerAdvertisement.Endpoint> endpoints = new ArrayList<>();
        List<BeaconDocument.Seed> seeds = new ArrayList<>(g.seeds());
        seeds.sort(java.util.Comparator.comparingInt(BeaconDocument.Seed::priority));
        for (BeaconDocument.Seed seed : seeds) {
            String transport = seed.transport() == null ? request.defaultScheme() : seed.transport();
            endpoints.add(new PeerAdvertisement.Endpoint(transport, seed.address(), endpoints.size()));
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        if (g.wire() != null) {
            g.wire().forEach((k, v) -> parameters.put("wire." + k, Integer.toString(v)));
        }
        if (g.multicast() != null) {
            parameters.put("multicast", g.multicast());
        }
        if (g.parameters() != null) {
            g.parameters().forEach(parameters::putIfAbsent);
        }
        if (g.invite() != null) {
            parameters.put("invite", g.invite());
        }
        signer.ifPresent(s -> parameters.put("beacon", s.value()));
        Optional<byte[]> founding = Optional.empty();
        if (g.founding() != null && !g.founding().isBlank()) {
            try {
                founding = Optional.of(Base64.getDecoder().decode(g.founding()));
            } catch (IllegalArgumentException e) {
                throw new IOException(evidence + ": the founding of " + g.name() + " is not base64");
            }
        }
        Duration ttl = document.ttl() > 0 ? Duration.ofSeconds(document.ttl()) : BeaconHint.DEFAULT_TTL;
        return new BeaconHint(endpoints, DnsSdLocator.parseId(g.id()), founding, parameters, ttl,
                evidence + " (" + g.name() + ", " + endpoints.size() + " seeds)");
    }

    private byte[] get(URI url, int redirects) throws IOException {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && options.allowPlain())) {
            throw new IOException("refusing " + url + ": only https (set url-allow-plain for http)");
        }
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("refusing " + url + ": no host");
        }
        if (!options.allowHosts().isEmpty() && !options.allowHosts().contains(host.toLowerCase(Locale.ROOT))) {
            throw new IOException("refusing " + url + ": host not in url-allow-hosts");
        }
        if (!options.allowPrivate() && isPrivate(host)) {
            throw new IOException("refusing " + url + ": a private address (set url-allow-private)");
        }
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(options.timeout())
                .header("Accept", "application/json")
                .header("User-Agent", "agentspaces-bootstrap/1")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + url);
        }
        int status = response.statusCode();
        try (InputStream in = response.body()) {
            if (status == 301 || status == 302 || status == 307 || status == 308) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || redirects >= 2) {
                    throw new IOException("refusing " + url + ": redirect limit");
                }
                URI next = url.resolve(location);
                if (!host.equalsIgnoreCase(next.getHost())) {
                    throw new IOException("refusing " + url + ": redirect off host to " + next.getHost());
                }
                return get(next, redirects + 1);
            }
            if (status != 200) {
                throw new IOException("GET " + url + " answered " + status);
            }
            byte[] body = in.readNBytes(options.maxBytes() + 1);
            if (body.length > options.maxBytes()) {
                throw new IOException("refusing " + url + ": larger than " + options.maxBytes() + " bytes");
            }
            return body;
        }
    }

    /** Whether a host names a loopback, link-local, site-local, or unresolvable address. */
    static boolean isPrivate(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isAnyLocalAddress()) {
                    return true;
                }
                byte[] raw = address.getAddress();
                if (raw.length == 4 && (raw[0] & 0xff) == 100 && (raw[1] & 0xc0) == 0x40) {
                    return true; // 100.64/10, carrier-grade NAT
                }
                if (raw.length == 16 && (raw[0] & 0xfe) == 0xfc) {
                    return true; // fc00::/7, unique local
                }
            }
            return false;
        } catch (IOException e) {
            return false; // let the fetch fail with its own message
        }
    }
}
