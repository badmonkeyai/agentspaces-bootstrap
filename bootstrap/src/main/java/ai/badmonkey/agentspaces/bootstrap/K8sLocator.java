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

import ai.badmonkey.agentspaces.api.spi.BeaconHint;
import ai.badmonkey.agentspaces.api.spi.BeaconLocator;
import ai.badmonkey.agentspaces.api.spi.BeaconRequest;

import java.io.IOException;
import java.util.Objects;

/**
 * A Kubernetes headless service as a locator: the SRV records Kubernetes
 * publishes for a named port, {@code _<port>._<protocol>.<service>.<namespace>.svc.<cluster-domain>},
 * through {@link SrvLocator}. The protocol is {@code tcp} unless the port is
 * UDP (a DTLS listener), whose records sit under {@code _udp} and whose
 * endpoints default to the {@code dtls} scheme. No Kubernetes client is needed; the cluster's
 * DNS answers.
 */
public final class K8sLocator implements BeaconLocator {

    /** The locator's name. */
    public static final String NAME = "k8s";

    private final SrvLocator srv;
    private final String owner;

    /**
     * A locator.
     *
     * @param service       the service name
     * @param namespace     the namespace
     * @param portName      the named port ({@code aspace})
     * @param clusterDomain the cluster domain ({@code cluster.local})
     * @param scheme        the endpoint scheme, or null for the request's default
     * @param resolver      the resolver
     */
    public K8sLocator(String service, String namespace, String portName, String clusterDomain, String scheme,
                      DnsResolver resolver) {
        this(service, namespace, portName, clusterDomain, null, scheme, resolver);
    }

    /**
     * A locator for a port of either protocol.
     *
     * @param service       the service name
     * @param namespace     the namespace
     * @param portName      the named port ({@code aspace})
     * @param clusterDomain the cluster domain ({@code cluster.local})
     * @param protocol      {@code tcp} (null or blank) or {@code udp}
     * @param scheme        the endpoint scheme, or null for the request's default ({@code dtls} for udp)
     * @param resolver      the resolver
     */
    public K8sLocator(String service, String namespace, String portName, String clusterDomain, String protocol,
                      String scheme, DnsResolver resolver) {
        String label = protocol(protocol);
        this.owner = owner(service, namespace, portName, clusterDomain, label);
        boolean noScheme = scheme == null || scheme.isBlank();
        this.srv = new SrvLocator(owner, noScheme && label.equals("udp") ? "dtls" : scheme, resolver);
    }

    /** The SRV owner name Kubernetes publishes for a TCP port. */
    public static String owner(String service, String namespace, String portName, String clusterDomain) {
        return owner(service, namespace, portName, clusterDomain, "tcp");
    }

    /** The SRV owner name Kubernetes publishes for a port of the given protocol. */
    public static String owner(String service, String namespace, String portName, String clusterDomain,
                               String protocol) {
        Objects.requireNonNull(service, "service");
        String ns = namespace == null || namespace.isBlank() ? "default" : namespace.trim();
        String port = portName == null || portName.isBlank() ? "aspace" : portName.trim();
        String cluster = clusterDomain == null || clusterDomain.isBlank() ? "cluster.local" : clusterDomain.trim();
        return "_" + port + "._" + protocol(protocol) + "." + service.trim() + "." + ns + ".svc." + cluster;
    }

    private static String protocol(String protocol) {
        String label = protocol == null || protocol.isBlank() ? "tcp" : protocol.trim().toLowerCase(java.util.Locale.ROOT);
        if (!label.equals("tcp") && !label.equals("udp")) {
            throw new IllegalArgumentException("bootstrap.k8s.protocol must be 'tcp' or 'udp', not '" + protocol + "'");
        }
        return label;
    }

    /** The owner name this locator asks. */
    public String owner() {
        return owner;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public BeaconHint locate(BeaconRequest request) throws IOException {
        return srv.locate(request);
    }
}
