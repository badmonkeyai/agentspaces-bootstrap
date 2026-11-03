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
package ai.badmonkey.agentspaces.bootstrap.chain;

import ai.badmonkey.agentspaces.api.spi.BeaconLocator;
import ai.badmonkey.agentspaces.bootstrap.BootstrapSettings;
import ai.badmonkey.agentspaces.bootstrap.DnsResolver;
import ai.badmonkey.agentspaces.bootstrap.LocatorAssembly;
import ai.badmonkey.agentspaces.peering.bootstrap.BootstrapChain;
import ai.badmonkey.agentspaces.peering.bootstrap.SeedsLocator;
import ai.badmonkey.agentspaces.peering.bootstrap.BootstrapChainFactory;
import ai.badmonkey.agentspaces.peering.bootstrap.MulticastLocator;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The composition both hosts share (SRAD DB-C-01, DB-C-02, DB-C-07): one
 * {@link BootstrapChain} per group from the merged {@code agentspaces.bootstrap.*}
 * settings, the seeds locator the host passes first, the locators the
 * settings ask for, and the application's locator beans. With no bootstrap
 * keys at all, the chain is the host's default over the incoming locators,
 * so a fleet configured with seeds alone behaves exactly as before.
 *
 * <p>Order: the seeds first (when {@code order} names them), then the bean
 * locators and the property-built locators, beans last unless
 * {@code bean-locators-first}. The startup refusals of DB-C-10 that concern
 * these keys (an unknown name in {@code order}, {@code accept-group-id-from}
 * naming an unauthenticated locator without {@code insecure-group-id}) are
 * raised when the factory is made, so a context fails to start with the
 * property named.
 */
public final class ChainComposer {

    private static final System.Logger LOG = System.getLogger(ChainComposer.class.getName());

    private ChainComposer() {
    }

    /**
     * A factory over the fleet block and the per-group blocks.
     *
     * @param fleet    the fleet-wide settings, or null for none
     * @param perGroup the group blocks by group name; null values are ignored
     * @param resolver the resolver the DNS locators ask
     * @return the factory
     * @throws IllegalStateException when a block is misconfigured (DB-C-10)
     */
    public static BootstrapChainFactory factory(BootstrapSettings fleet, Map<String, BootstrapSettings> perGroup,
                                                DnsResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        BootstrapSettings fleetBlock = fleet == null ? new BootstrapSettings() : fleet;
        Map<String, BootstrapSettings> groups = new LinkedHashMap<>();
        if (perGroup != null) {
            perGroup.forEach((name, settings) -> {
                if (name != null && settings != null) {
                    groups.put(name, settings);
                }
            });
        }
        // One client per distinct connect timeout, so a group's own timeout applies to its
        // document fetches (review-110226-deadcode-parity B6).
        Map<java.time.Duration, HttpClient> clients = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.function.Function<java.time.Duration, HttpClient> clientFor = timeout ->
                clients.computeIfAbsent(timeout, t -> HttpClient.newBuilder().connectTimeout(t).build());
        validate(fleetBlock, groups, resolver, clientFor.apply(fleetBlock.timeoutOrDefault()));
        return (groupName, locators) -> {
            BootstrapSettings merged = BootstrapSettings.merge(fleetBlock, groups.get(groupName));
            warnWhenLeftOut(groupName, merged, locators);
            return compose(merged, locators, resolver, clientFor.apply(merged.timeoutOrDefault()));
        };
    }

    /**
     * The chain of one group. The {@code order} governs the position of
     * {@code seeds}, {@code multicast}, and every property-built locator; a
     * name the order leaves out is not asked (the host is warned at startup
     * about seeds and multicast it configured). The host's other locators,
     * its {@code BeaconLocator} beans, are always asked, before the rest under
     * {@code bean-locators-first} and after them otherwise.
     *
     * @param settings the group's merged settings; null or empty means the default chain
     * @param incoming the host's locators: the seeds locator, the multicast locator when on, then the beans
     * @param resolver the resolver, used for name expansion even with no key set; null only with empty settings
     * @param client   the HTTP client the document locators share, or null for a default
     * @return the locator the node joins with
     */
    public static BeaconLocator compose(BootstrapSettings settings, List<BeaconLocator> incoming,
                                        DnsResolver resolver, HttpClient client) {
        Objects.requireNonNull(incoming, "incoming");
        if (settings == null || settings.isEmpty()) {
            BootstrapChain.Builder plain = BootstrapChain.builder().addAll(incoming);
            if (resolver != null) {
                plain.expandNames(host -> addresses(resolver, host));
            }
            return plain.build();
        }
        Objects.requireNonNull(resolver, "resolver");
        List<BeaconLocator> seeds = new ArrayList<>();
        List<BeaconLocator> multicast = new ArrayList<>();
        List<BeaconLocator> beans = new ArrayList<>();
        for (BeaconLocator locator : incoming) {
            if (SeedsLocator.NAME.equals(locator.name())) {
                seeds.add(locator);
            } else if (MulticastLocator.NAME.equals(locator.name())) {
                multicast.add(locator);
            } else {
                beans.add(locator);
            }
        }
        Map<String, BeaconLocator> fromProperties = LocatorAssembly.locatorsByName(settings, resolver, client);
        List<BeaconLocator> ordered = new ArrayList<>();
        for (String name : names(settings)) {
            if (SeedsLocator.NAME.equals(name)) {
                ordered.addAll(seeds);
            } else if (MulticastLocator.NAME.equals(name)) {
                ordered.addAll(multicast);
            } else if (fromProperties.containsKey(name)) {
                ordered.add(fromProperties.get(name));
            }
        }
        BootstrapChain.Builder builder = BootstrapChain.builder();
        if (settings.beanLocatorsFirstOrDefault()) {
            // Beans go before the property-built locators and after the host's own
            // (seeds, multicast) that the order places ahead of them.
            int firstProperty = 0;
            while (firstProperty < ordered.size() && !fromProperties.containsValue(ordered.get(firstProperty))) {
                firstProperty++;
            }
            builder.addAll(ordered.subList(0, firstProperty)).addAll(beans)
                    .addAll(ordered.subList(firstProperty, ordered.size()));
        } else {
            builder.addAll(ordered).addAll(beans);
        }
        builder.timeout(settings.timeoutOrDefault());
        builder.acceptGroupIdFrom(LocatorAssembly.acceptGroupIdFrom(settings, resolver));
        if (settings.firstWinsOrDefault()) {
            builder.firstWins();
        }
        if (!settings.expandNamesOrDefault()) {
            builder.expandNames(null);
        } else {
            builder.expandNames(host -> addresses(resolver, host));
        }
        // ISSUE-BootstrapCore FR-3, FR-4, DB-C-12: the node paces, requires, and traces by the chain's policy.
        builder.locatePolicy(new ai.badmonkey.agentspaces.api.spi.LocatePolicy(
                java.util.Optional.ofNullable(settings.getRefreshMin()),
                java.util.Optional.ofNullable(settings.getRefreshMax()),
                settings.requireOrDefault(), settings.traceOrDefault()));
        return builder.build();
    }

    private static java.util.List<java.net.InetAddress> addresses(DnsResolver resolver, String host) {
        try {
            return resolver.addresses(host);
        } catch (java.io.IOException e) {
            return java.util.List.of();
        }
    }

    /**
     * The startup refusals (DB-C-10) for every block, before any group joins.
     *
     * @param fleet    the fleet block
     * @param perGroup the group blocks by name
     * @param resolver the resolver
     * @param client   the HTTP client, or null
     * @throws IllegalStateException naming the property
     */
    public static void validate(BootstrapSettings fleet, Map<String, BootstrapSettings> perGroup,
                                DnsResolver resolver, HttpClient client) {
        check(BootstrapSettings.merge(fleet, null), resolver, client, null);
        for (Map.Entry<String, BootstrapSettings> entry : perGroup.entrySet()) {
            check(BootstrapSettings.merge(fleet, entry.getValue()), resolver, client, entry.getKey());
        }
    }

    private static void check(BootstrapSettings merged, DnsResolver resolver, HttpClient client, String group) {
        if (merged.isEmpty()) {
            return;
        }
        try {
            LocatorAssembly.locators(merged, resolver, client);
            LocatorAssembly.acceptGroupIdFrom(merged, resolver);
        } catch (IllegalStateException | IllegalArgumentException e) {
            String where = group == null ? "agentspaces.bootstrap" : "agentspaces.groups[].bootstrap (group '"
                    + group + "')";
            throw new IllegalStateException(where + ": " + e.getMessage(), e);
        }
    }

    /**
     * One WARNING per group at startup for each host locator its effective
     * {@code order} leaves out: configured seeds, or the multicast locator
     * when it is on (ISSUE-BootstrapRobustness FR-3, review B3). The chain is
     * still composed as the operator asked.
     */
    static void warnWhenLeftOut(String groupName, BootstrapSettings merged, List<BeaconLocator> incoming) {
        for (String message : new String[] {seedsLeftOut(groupName, merged, incoming),
                                            multicastLeftOut(groupName, merged, incoming)}) {
            if (message != null) {
                LOG.log(System.Logger.Level.WARNING, message);
            }
        }
    }

    /** Kept for callers of the 0.5.0 name; see {@link #warnWhenLeftOut}. */
    static void warnWhenSeedsAreLeftOut(String groupName, BootstrapSettings merged, List<BeaconLocator> incoming) {
        warnWhenLeftOut(groupName, merged, incoming);
    }

    /**
     * The seeds warning, or null when the order names {@code seeds}, the
     * settings are empty (the default chain keeps every incoming locator), or
     * the group has no seed to lose.
     *
     * @param groupName the group
     * @param merged    its merged settings
     * @param incoming  the host's locators, the seeds locator among them
     * @return the message, or null when there is nothing to warn about
     */
    static String seedsLeftOut(String groupName, BootstrapSettings merged, List<BeaconLocator> incoming) {
        if (merged == null || merged.isEmpty() || names(merged).contains(SeedsLocator.NAME)) {
            return null;
        }
        for (BeaconLocator locator : incoming) {
            if (locator instanceof SeedsLocator seeds && !seeds.isEmpty()) {
                int count = seeds.locate(new ai.badmonkey.agentspaces.api.spi.BeaconRequest(groupName,
                        java.util.Optional.empty(), "tcp", java.util.Set.of("tcp"))).endpoints().size();
                return "group '" + groupName + "' lists " + count + " seed(s) but its bootstrap order "
                        + names(merged) + " leaves out '" + SeedsLocator.NAME + "', so the seeds are never asked;"
                        + " add '" + SeedsLocator.NAME + "' to agentspaces.bootstrap.order or"
                        + " agentspaces.groups[].bootstrap.order";
            }
        }
        return null;
    }

    /** The multicast warning, or null when the order names {@code multicast} or the host passed none. */
    static String multicastLeftOut(String groupName, BootstrapSettings merged, List<BeaconLocator> incoming) {
        if (merged == null || merged.isEmpty() || names(merged).contains(MulticastLocator.NAME)) {
            return null;
        }
        for (BeaconLocator locator : incoming) {
            if (MulticastLocator.NAME.equals(locator.name())) {
                return "group '" + groupName + "' has the multicast beacon on but its bootstrap order "
                        + names(merged) + " leaves out '" + MulticastLocator.NAME + "', so it is never asked;"
                        + " add '" + MulticastLocator.NAME + "' to agentspaces.bootstrap.order or"
                        + " agentspaces.groups[].bootstrap.order";
            }
        }
        return null;
    }

    private static List<String> names(BootstrapSettings settings) {
        List<String> names = new ArrayList<>();
        for (String name : settings.orderOrDefault()) {
            names.add(name.trim().toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
