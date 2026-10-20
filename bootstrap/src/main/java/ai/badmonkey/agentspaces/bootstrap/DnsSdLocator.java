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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Wide-area DNS-SD (RFC 6763) under each candidate domain: the PTR records of
 * {@code _agentspaces._tcp.<domain>} name the instances; each instance's SRV
 * records give hosts and ports and its TXT record the parameters of appendix
 * B ({@code v}, {@code group}, {@code id}, {@code scheme}, {@code url},
 * {@code multicast}). An instance whose {@code group} is not the request's
 * group name is skipped unless {@code anyGroup}; a {@code url} is followed
 * through the given {@link UrlLocator} when one is set, so a record set that
 * points at a beacon document yields its founding too. The {@code id} claim
 * is returned for the chain to judge.
 */
public final class DnsSdLocator implements BeaconLocator {

    /** The locator's name. */
    public static final String NAME = "dns-sd";
    /** The DNS-SD service type. */
    public static final String SERVICE = "_agentspaces._tcp";

    /** The service type of UDP (DTLS) instances, browsed when asked (SRAD dtls-support DT-L-04). */
    public static final String SERVICE_UDP = "_agentspaces._udp";

    private boolean browseUdp;

    /**
     * Also browses {@link #SERVICE_UDP} under each domain; its instances default
     * to the {@code dtls} scheme unless their TXT record names another.
     *
     * @param browse whether to browse
     * @return this locator
     */
    public DnsSdLocator browsingUdp(boolean browse) {
        this.browseUdp = browse;
        return this;
    }

    private final Supplier<List<String>> domains;
    private final DnsResolver resolver;
    private final boolean anyGroup;
    private final UrlLocator follow;
    private final Random random;
    private final Duration ttl;

    /**
     * A locator.
     *
     * @param domains  the candidate domains, in order ({@link DomainFinder})
     * @param resolver the resolver
     * @param anyGroup whether to accept instances whose {@code group} differs from the request's
     * @param follow   the URL locator to fetch {@code url=} records through, or null
     */
    public DnsSdLocator(Supplier<List<String>> domains, DnsResolver resolver, boolean anyGroup, UrlLocator follow) {
        this(domains, resolver, anyGroup, follow, new Random(), Duration.ofMinutes(5));
    }

    /**
     * A locator with its randomness and time to live chosen.
     *
     * @param domains  the candidate domains
     * @param resolver the resolver
     * @param anyGroup whether to accept instances of any group
     * @param follow   the URL locator, or null
     * @param random   orders SRV records within a priority
     * @param ttl      the hint's time to live
     */
    public DnsSdLocator(Supplier<List<String>> domains, DnsResolver resolver, boolean anyGroup, UrlLocator follow,
                        Random random, Duration ttl) {
        this.domains = Objects.requireNonNull(domains, "domains");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.anyGroup = anyGroup;
        this.follow = follow;
        this.random = Objects.requireNonNull(random, "random");
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
        List<String[]> services = new ArrayList<>();
        for (String domain : domains.get()) {
            services.add(new String[]{SERVICE + "." + domain, request.defaultScheme()});
            if (browseUdp) {
                services.add(new String[]{SERVICE_UDP + "." + domain, "dtls"});
            }
        }
        for (String[] browse : services) {
            String service = browse[0];
            String defaultScheme = browse[1];
            for (String instance : resolver.ptr(service)) {
                Map<String, String> txt = TxtRecords.parse(resolver.txt(instance));
                String group = txt.get("group");
                if (!anyGroup && group != null && !group.equalsIgnoreCase(request.groupName())) {
                    evidence.add(instance + " skipped (group " + group + ")");
                    continue;
                }
                String scheme = txt.getOrDefault("scheme", defaultScheme);
                List<DnsResolver.SrvRecord> records = resolver.srv(instance);
                for (PeerAdvertisement.Endpoint endpoint : SrvLocator.endpoints(records, scheme, random)) {
                    endpoints.add(new PeerAdvertisement.Endpoint(endpoint.transport(), endpoint.address(),
                            endpoints.size()));
                }
                evidence.add(instance + " (" + records.size() + " SRV)");
                if (claimed.isEmpty() && txt.containsKey("id")) {
                    claimed = parseId(txt.get("id"));
                }
                txt.forEach((k, v) -> {
                    if (!k.equals("group") && !k.equals("id")) {
                        parameters.putIfAbsent(k, v);
                    }
                });
                if (follow != null && txt.containsKey("url")) {
                    BeaconHint fetched = follow.fetch(txt.get("url"), request);
                    if (!fetched.isEmpty()) {
                        for (PeerAdvertisement.Endpoint endpoint : fetched.endpoints()) {
                            endpoints.add(new PeerAdvertisement.Endpoint(endpoint.transport(), endpoint.address(),
                                    endpoints.size()));
                        }
                        if (founding.isEmpty()) {
                            founding = fetched.founding();
                        }
                        fetched.parameters().forEach(parameters::putIfAbsent);
                        evidence.add(fetched.evidence());
                    }
                }
            }
        }
        if (endpoints.isEmpty() && founding.isEmpty()) {
            return BeaconHint.none();
        }
        return new BeaconHint(endpoints, claimed, founding, parameters, ttl, String.join("; ", evidence));
    }

    static Optional<GroupId> parseId(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String id = value.trim();
        if (id.toLowerCase(Locale.ROOT).startsWith("aspace://")) {
            id = id.substring("aspace://".length());
        }
        int slash = id.indexOf('/');
        if (slash >= 0) {
            id = id.substring(0, slash);
        }
        if (id.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(GroupId.of(id));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
