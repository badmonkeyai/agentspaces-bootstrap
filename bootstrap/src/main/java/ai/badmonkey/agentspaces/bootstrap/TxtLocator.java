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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The one-record pointer: {@code agentspaces.<domain>} TXT with {@code url=}
 * (and optionally {@code id=}), the WPAD-style convention for a network that
 * would rather publish one record than a service instance. The URL is
 * followed through the given {@link UrlLocator}.
 */
public final class TxtLocator implements BeaconLocator {

    /** The locator's name. */
    public static final String NAME = "dns-txt";
    /** The label under the domain. */
    public static final String LABEL = "agentspaces";

    private final Supplier<List<String>> domains;
    private final DnsResolver resolver;
    private final UrlLocator follow;
    private final Duration ttl;

    /**
     * A locator.
     *
     * @param domains  the candidate domains, in order
     * @param resolver the resolver
     * @param follow   the URL locator that fetches what the record points at
     */
    public TxtLocator(Supplier<List<String>> domains, DnsResolver resolver, UrlLocator follow) {
        this(domains, resolver, follow, Duration.ofMinutes(5));
    }

    /**
     * A locator with its time to live chosen.
     *
     * @param domains  the candidate domains
     * @param resolver the resolver
     * @param follow   the URL locator
     * @param ttl      the hint's time to live when no document sets one
     */
    public TxtLocator(Supplier<List<String>> domains, DnsResolver resolver, UrlLocator follow, Duration ttl) {
        this.domains = Objects.requireNonNull(domains, "domains");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.follow = Objects.requireNonNull(follow, "follow");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
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
        List<String> evidence = new ArrayList<>();
        Duration shortest = ttl;
        for (String domain : domains.get()) {
            String name = LABEL + "." + domain;
            Map<String, String> txt = TxtRecords.parse(resolver.txt(name));
            if (txt.isEmpty()) {
                continue;
            }
            evidence.add("TXT " + name);
            if (claimed.isEmpty() && txt.containsKey("id")) {
                claimed = DnsSdLocator.parseId(txt.get("id"));
            }
            if (txt.containsKey("url")) {
                BeaconHint fetched = follow.fetch(txt.get("url"), request);
                if (!fetched.isEmpty()) {
                    for (PeerAdvertisement.Endpoint endpoint : fetched.endpoints()) {
                        endpoints.add(new PeerAdvertisement.Endpoint(endpoint.transport(), endpoint.address(),
                                endpoints.size()));
                    }
                    if (founding.isEmpty()) {
                        founding = fetched.founding();
                    }
                    if (claimed.isEmpty()) {
                        claimed = fetched.groupId();
                    }
                    fetched.parameters().forEach(parameters::putIfAbsent);
                    if (!fetched.ttl().isZero() && fetched.ttl().compareTo(shortest) < 0) {
                        shortest = fetched.ttl();
                    }
                    evidence.add(fetched.evidence());
                }
            }
        }
        if (endpoints.isEmpty() && founding.isEmpty()) {
            return BeaconHint.none();
        }
        return new BeaconHint(endpoints, claimed, founding, parameters, shortest, String.join("; ", evidence));
    }
}
