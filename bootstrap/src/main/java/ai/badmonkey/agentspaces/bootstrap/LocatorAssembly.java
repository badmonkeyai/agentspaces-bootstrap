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

import ai.badmonkey.agentspaces.api.spi.BeaconLocator;
import ai.badmonkey.agentspaces.common.id.PeerId;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Turns settings into locators, in the configured order (SRAD DB-C-07): the
 * ones this library provides. {@code seeds} and {@code multicast} are the
 * host's and are skipped here; the host places the seeds first. The URL
 * guards and the pin are shared by every locator that fetches a document.
 */
public final class LocatorAssembly {

    private LocatorAssembly() {
    }

    /**
     * The locators the settings ask for, in order.
     *
     * @param settings the merged settings of one group
     * @param resolver the resolver
     * @param client   the HTTP client, or null for a default
     * @return the locators
     */
    public static List<BeaconLocator> locators(BootstrapSettings settings, DnsResolver resolver, HttpClient client) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(resolver, "resolver");
        UrlLocator.Options options = urlOptions(settings);
        UrlLocator follower = UrlLocator.follower(options, client);
        DomainFinder domains = domains(settings);
        List<BeaconLocator> out = new ArrayList<>();
        for (String name : settings.orderOrDefault()) {
            switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "dns-sd" -> out.add(new DnsSdLocator(domains, resolver, settings.dnsSdAnyGroupOrDefault(), follower)
                        .browsingUdp(settings.dnsSdUdpOrDefault()));
                case "dns-txt" -> out.add(new TxtLocator(domains, resolver, follower));
                case "dns-srv" -> {
                    if (settings.getDnsSrv() != null && !settings.getDnsSrv().isBlank()) {
                        out.add(new SrvLocator(settings.getDnsSrv(), settings.getDnsSrvScheme(), resolver));
                    }
                }
                case "k8s" -> {
                    BootstrapSettings.K8s k8s = settings.getK8s();
                    if (k8s != null && k8s.getService() != null && !k8s.getService().isBlank()) {
                        out.add(new K8sLocator(k8s.getService(), k8s.getNamespace(), k8s.getPortName(),
                                k8s.getClusterDomain(), k8s.getProtocol(), settings.getDnsSrvScheme(), resolver));
                    }
                }
                case "url" -> {
                    if (settings.getUrl() != null && !settings.getUrl().isBlank()) {
                        List<URI> urls = new ArrayList<>();
                        for (String url : settings.getUrl().split("[,\\s]+")) {
                            if (!url.isBlank()) {
                                urls.add(URI.create(url.trim()));
                            }
                        }
                        out.add(new UrlLocator(urls, options, client));
                    }
                }
                case "document" -> {
                    if (settings.getDocument() != null && !settings.getDocument().isBlank()) {
                        out.add(StaticDocumentLocator.of(settings.getDocument(), options));
                    }
                }
                case "seeds", "multicast" -> {
                    // The host's.
                }
                default -> throw new IllegalStateException("agentspaces.bootstrap.order names an unknown locator '"
                        + name + "'; known: " + BootstrapSettings.DEFAULT_ORDER);
            }
        }
        return out;
    }

    /** The locator names whose GroupId claim is honoured, after the insecure check. */
    public static Set<String> acceptGroupIdFrom(BootstrapSettings settings, DnsResolver resolver) {
        Set<String> names = new HashSet<>();
        for (String name : settings.acceptGroupIdFromOrDefault()) {
            String n = name.trim().toLowerCase(Locale.ROOT);
            boolean unauthenticated = n.startsWith("dns") || n.equals("k8s") || n.equals("multicast");
            if (unauthenticated && !settings.insecureGroupIdOrDefault() && !resolver.authenticated()) {
                throw new IllegalStateException("agentspaces.bootstrap.accept-group-id-from names '" + n
                        + "', an unauthenticated locator; set agentspaces.bootstrap.insecure-group-id: true to"
                        + " accept a group identity from plain DNS, or use a DNSSEC-validating resolver");
            }
            names.add(n);
        }
        return names;
    }

    /** The URL guards from the settings. */
    public static UrlLocator.Options urlOptions(BootstrapSettings settings) {
        UrlLocator.Options options = UrlLocator.Options.defaults()
                .withAllowPlain(settings.urlAllowPlainOrDefault())
                .withAllowPrivate(settings.urlAllowPrivateOrDefault())
                .withTimeout(settings.timeoutOrDefault());
        Set<String> hosts = new HashSet<>();
        for (String host : settings.urlAllowHostsOrDefault()) {
            hosts.add(host.trim().toLowerCase(Locale.ROOT));
        }
        options = options.withAllowHosts(hosts);
        if (settings.getBeaconPeer() != null && !settings.getBeaconPeer().isBlank()) {
            options = options.withBeaconPeer(PeerId.of(settings.getBeaconPeer().trim()));
        }
        return options;
    }

    /** The domain finder from the settings. */
    public static DomainFinder domains(BootstrapSettings settings) {
        List<String> domain = settings.domainOrDefault();
        if (domain.size() == 1 && domain.get(0).trim().equalsIgnoreCase("auto")) {
            return DomainFinder.auto();
        }
        List<String> fixed = new ArrayList<>();
        for (String d : domain) {
            if (!d.isBlank() && !d.trim().equalsIgnoreCase("auto")) {
                fixed.add(d.trim());
            }
        }
        return fixed.isEmpty() ? DomainFinder.none() : DomainFinder.of(fixed);
    }
}
