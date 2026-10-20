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
import ai.badmonkey.agentspaces.spring.BootstrapChainFactory;

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
        HttpClient client = HttpClient.newBuilder().connectTimeout(fleetBlock.timeoutOrDefault()).build();
        validate(fleetBlock, groups, resolver, client);
        return (group, locators) -> compose(BootstrapSettings.merge(fleetBlock, groups.get(group.getName())),
                locators, resolver, client);
    }

    /**
     * The chain of one group.
     *
     * @param settings the group's merged settings; null or empty means the default chain
     * @param incoming the host's locators: the seeds locator first, then the applicable beans
     * @param resolver the resolver
     * @param client   the HTTP client the document locators share, or null for a default
     * @return the locator the node joins with
     */
    public static BeaconLocator compose(BootstrapSettings settings, List<BeaconLocator> incoming,
                                        DnsResolver resolver, HttpClient client) {
        Objects.requireNonNull(incoming, "incoming");
        if (settings == null || settings.isEmpty()) {
            return BootstrapChain.builder().addAll(incoming).build();
        }
        Objects.requireNonNull(resolver, "resolver");
        List<BeaconLocator> seeds = new ArrayList<>();
        List<BeaconLocator> beans = new ArrayList<>();
        for (BeaconLocator locator : incoming) {
            (SeedsLocator.NAME.equals(locator.name()) ? seeds : beans).add(locator);
        }
        List<BeaconLocator> fromProperties = LocatorAssembly.locators(settings, resolver, client);
        BootstrapChain.Builder builder = BootstrapChain.builder();
        if (names(settings).contains(SeedsLocator.NAME)) {
            builder.addAll(seeds);
        }
        if (settings.beanLocatorsFirstOrDefault()) {
            builder.addAll(beans).addAll(fromProperties);
        } else {
            builder.addAll(fromProperties).addAll(beans);
        }
        builder.timeout(settings.timeoutOrDefault());
        builder.acceptGroupIdFrom(LocatorAssembly.acceptGroupIdFrom(settings, resolver));
        if (settings.firstWinsOrDefault()) {
            builder.firstWins();
        }
        if (!settings.expandNamesOrDefault()) {
            builder.expandNames(null);
        } else {
            // ISSUE-BootstrapCore FR-8: names expand through the host's resolver (DoH, DNSSEC), not the platform's.
            builder.expandNames(host -> {
                try {
                    return resolver.addresses(host);
                } catch (java.io.IOException e) {
                    return java.util.List.of();
                }
            });
        }
        // ISSUE-BootstrapCore FR-3, FR-4, DB-C-12: the node paces, requires, and traces by the chain's policy.
        builder.locatePolicy(new ai.badmonkey.agentspaces.api.spi.LocatePolicy(
                java.util.Optional.ofNullable(settings.getRefreshMin()),
                java.util.Optional.ofNullable(settings.getRefreshMax()),
                settings.requireOrDefault(), settings.traceOrDefault()));
        return builder.build();
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

    private static List<String> names(BootstrapSettings settings) {
        List<String> names = new ArrayList<>();
        for (String name : settings.orderOrDefault()) {
            names.add(name.trim().toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
