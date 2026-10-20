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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * SRV records (RFC 2782) at a configured owner name, one endpoint per record:
 * {@code scheme://target:port} at the record's priority, records of one
 * priority ordered weighted-random by their weights, so a registry that
 * spreads load by weight is obeyed. The target stays a name; the chain's
 * expansion turns it into addresses.
 */
public final class SrvLocator implements BeaconLocator {

    /** The locator's name. */
    public static final String NAME = "dns-srv";

    private final String owner;
    private final String scheme;
    private final DnsResolver resolver;
    private final Random random;
    private final Duration ttl;

    /**
     * A locator.
     *
     * @param owner    the owner name ({@code _aspace._tcp.seeds.fleet.svc.cluster.local})
     * @param scheme   the endpoint scheme, or null for the request's default
     * @param resolver the resolver
     */
    public SrvLocator(String owner, String scheme, DnsResolver resolver) {
        this(owner, scheme, resolver, new Random(), Duration.ofMinutes(5));
    }

    /**
     * A locator with its randomness and the hint's time to live chosen.
     *
     * @param owner    the owner name
     * @param scheme   the endpoint scheme, or null for the request's default
     * @param resolver the resolver
     * @param random   orders records of one priority by weight
     * @param ttl      the hint's time to live
     */
    public SrvLocator(String owner, String scheme, DnsResolver resolver, Random random, Duration ttl) {
        this.owner = Objects.requireNonNull(owner, "owner").trim();
        this.scheme = scheme == null || scheme.isBlank() ? null : scheme.trim();
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.random = Objects.requireNonNull(random, "random");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public BeaconHint locate(BeaconRequest request) throws IOException {
        List<DnsResolver.SrvRecord> records = resolver.srv(owner);
        List<PeerAdvertisement.Endpoint> endpoints = endpoints(records, scheme == null ? request.defaultScheme() : scheme,
                random);
        if (endpoints.isEmpty()) {
            return BeaconHint.none();
        }
        return new BeaconHint(endpoints, Optional.empty(), Optional.empty(), Map.of(), ttl,
                "SRV " + owner + " (" + records.size() + ")");
    }

    /**
     * Orders SRV records by priority, weighted-random within a priority (RFC
     * 2782), and renders endpoints.
     *
     * @param records the records
     * @param scheme  the endpoint scheme
     * @param random  the randomness
     * @return the endpoints, priorities by position
     */
    public static List<PeerAdvertisement.Endpoint> endpoints(List<DnsResolver.SrvRecord> records, String scheme,
                                                             Random random) {
        List<DnsResolver.SrvRecord> remaining = new ArrayList<>(records);
        remaining.sort(java.util.Comparator.comparingInt(DnsResolver.SrvRecord::priority));
        List<PeerAdvertisement.Endpoint> out = new ArrayList<>();
        int i = 0;
        while (i < remaining.size()) {
            int priority = remaining.get(i).priority();
            int j = i;
            while (j < remaining.size() && remaining.get(j).priority() == priority) {
                j++;
            }
            List<DnsResolver.SrvRecord> tier = new ArrayList<>(remaining.subList(i, j));
            while (!tier.isEmpty()) {
                int total = 0;
                for (DnsResolver.SrvRecord record : tier) {
                    total += Math.max(0, record.weight());
                }
                int pick = 0;
                if (total > 0) {
                    int draw = random.nextInt(total + 1);
                    int running = 0;
                    for (int k = 0; k < tier.size(); k++) {
                        running += Math.max(0, tier.get(k).weight());
                        if (running >= draw) {
                            pick = k;
                            break;
                        }
                    }
                }
                DnsResolver.SrvRecord chosen = tier.remove(pick);
                if (!chosen.target().isEmpty() && !chosen.target().equals(".")) {
                    out.add(new PeerAdvertisement.Endpoint(scheme, chosen.target() + ":" + chosen.port(), out.size()));
                }
            }
            i = j;
        }
        return out;
    }
}
