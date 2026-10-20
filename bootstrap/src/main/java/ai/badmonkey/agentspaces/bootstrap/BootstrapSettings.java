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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code agentspaces.bootstrap.*} keys (SRAD appendix D), bound by the
 * Spring Boot and Micronaut modules and by the plain-Java runtime. The same
 * class serves the fleet-wide block and a group's block; {@link #merge}
 * lays a group's set values over the fleet's. Plain getters and setters so
 * every binder reads it.
 */
public class BootstrapSettings {

    /** The default chain order; {@code seeds} and {@code multicast} belong to the host. */
    public static final List<String> DEFAULT_ORDER = List.of("seeds", "dns-sd", "dns-txt", "dns-srv", "k8s", "url",
            "document", "multicast");

    private List<String> domain;
    private List<String> order;
    private Boolean firstWins;
    private Duration timeout;
    private Duration refreshMin;
    private Duration refreshMax;
    private List<String> acceptGroupIdFrom;
    private Boolean insecureGroupId;
    private String beaconPeer;
    private String url;
    private Boolean urlAllowPlain;
    private List<String> urlAllowHosts;
    private Boolean urlAllowPrivate;
    private Boolean expandNames;
    private Boolean beanLocatorsFirst;
    private Boolean require;
    private Boolean trace;
    private String dnsSrv;
    private String dnsSrvScheme;
    private Boolean dnsSdAnyGroup;
    private Boolean dnsSdUdp;
    private String document;
    private K8s k8s;

    /** The Kubernetes convenience ({@code bootstrap.k8s.*}). */
    public static class K8s {
        private String service;
        private String namespace;
        private String portName;
        private String clusterDomain;
        private String protocol;

        /**
         * The port's protocol label, {@code tcp} (default) or {@code udp}: a
         * Service port with {@code protocol: UDP}, a DTLS listener, publishes
         * its SRV record under {@code _udp} (SRAD dtls-support DT-L-03).
         */
        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getPortName() {
            return portName;
        }

        public void setPortName(String portName) {
            this.portName = portName;
        }

        public String getClusterDomain() {
            return clusterDomain;
        }

        public void setClusterDomain(String clusterDomain) {
            this.clusterDomain = clusterDomain;
        }
    }

    /** A group's set values over the fleet's. */
    public static BootstrapSettings merge(BootstrapSettings fleet, BootstrapSettings group) {
        BootstrapSettings f = fleet == null ? new BootstrapSettings() : fleet;
        BootstrapSettings g = group == null ? new BootstrapSettings() : group;
        BootstrapSettings out = new BootstrapSettings();
        out.domain = g.domain != null ? g.domain : f.domain;
        out.order = g.order != null ? g.order : f.order;
        out.firstWins = g.firstWins != null ? g.firstWins : f.firstWins;
        out.timeout = g.timeout != null ? g.timeout : f.timeout;
        out.refreshMin = g.refreshMin != null ? g.refreshMin : f.refreshMin;
        out.refreshMax = g.refreshMax != null ? g.refreshMax : f.refreshMax;
        out.acceptGroupIdFrom = g.acceptGroupIdFrom != null ? g.acceptGroupIdFrom : f.acceptGroupIdFrom;
        out.insecureGroupId = g.insecureGroupId != null ? g.insecureGroupId : f.insecureGroupId;
        out.beaconPeer = g.beaconPeer != null ? g.beaconPeer : f.beaconPeer;
        out.url = g.url != null ? g.url : f.url;
        out.urlAllowPlain = g.urlAllowPlain != null ? g.urlAllowPlain : f.urlAllowPlain;
        out.urlAllowHosts = g.urlAllowHosts != null ? g.urlAllowHosts : f.urlAllowHosts;
        out.urlAllowPrivate = g.urlAllowPrivate != null ? g.urlAllowPrivate : f.urlAllowPrivate;
        out.expandNames = g.expandNames != null ? g.expandNames : f.expandNames;
        out.beanLocatorsFirst = g.beanLocatorsFirst != null ? g.beanLocatorsFirst : f.beanLocatorsFirst;
        out.require = g.require != null ? g.require : f.require;
        out.trace = g.trace != null ? g.trace : f.trace;
        out.dnsSrv = g.dnsSrv != null ? g.dnsSrv : f.dnsSrv;
        out.dnsSrvScheme = g.dnsSrvScheme != null ? g.dnsSrvScheme : f.dnsSrvScheme;
        out.dnsSdAnyGroup = g.dnsSdAnyGroup != null ? g.dnsSdAnyGroup : f.dnsSdAnyGroup;
        out.dnsSdUdp = g.dnsSdUdp != null ? g.dnsSdUdp : f.dnsSdUdp;
        out.document = g.document != null ? g.document : f.document;
        out.k8s = g.k8s != null ? g.k8s : f.k8s;
        return out;
    }

    // ---- effective values with defaults

    /** The domains: {@code auto} (the default) reads the host's search list; empty means none. */
    public List<String> domainOrDefault() {
        return domain == null ? List.of("auto") : domain;
    }

    public List<String> orderOrDefault() {
        return order == null || order.isEmpty() ? DEFAULT_ORDER : order;
    }

    public boolean firstWinsOrDefault() {
        return Boolean.TRUE.equals(firstWins);
    }

    public Duration timeoutOrDefault() {
        return timeout == null ? Duration.ofSeconds(3) : timeout;
    }

    public Duration refreshMinOrDefault() {
        return refreshMin == null ? Duration.ofSeconds(30) : refreshMin;
    }

    public Duration refreshMaxOrDefault() {
        return refreshMax == null ? Duration.ofMinutes(10) : refreshMax;
    }

    public List<String> acceptGroupIdFromOrDefault() {
        return acceptGroupIdFrom == null ? List.of("url", "document") : acceptGroupIdFrom;
    }

    public boolean insecureGroupIdOrDefault() {
        return Boolean.TRUE.equals(insecureGroupId);
    }

    public boolean urlAllowPlainOrDefault() {
        return Boolean.TRUE.equals(urlAllowPlain);
    }

    public List<String> urlAllowHostsOrDefault() {
        return urlAllowHosts == null ? List.of() : urlAllowHosts;
    }

    public boolean urlAllowPrivateOrDefault() {
        return Boolean.TRUE.equals(urlAllowPrivate);
    }

    public boolean expandNamesOrDefault() {
        return expandNames == null || expandNames;
    }

    public boolean beanLocatorsFirstOrDefault() {
        return Boolean.TRUE.equals(beanLocatorsFirst);
    }

    public boolean requireOrDefault() {
        return Boolean.TRUE.equals(require);
    }

    public boolean traceOrDefault() {
        return Boolean.TRUE.equals(trace);
    }

    public boolean dnsSdAnyGroupOrDefault() {
        return Boolean.TRUE.equals(dnsSdAnyGroup);
    }

    /** Whether any key is set (a fleet with no block at all keeps today's behaviour). */
    public boolean isEmpty() {
        return domain == null && order == null && firstWins == null && timeout == null && refreshMin == null
                && refreshMax == null && acceptGroupIdFrom == null && insecureGroupId == null && beaconPeer == null
                && url == null && urlAllowPlain == null && urlAllowHosts == null && urlAllowPrivate == null
                && expandNames == null && beanLocatorsFirst == null && require == null && trace == null
                && dnsSrv == null && dnsSrvScheme == null && dnsSdAnyGroup == null && dnsSdUdp == null && document == null && k8s == null;
    }

    // ---- getters and setters

    public List<String> getDomain() {
        return domain;
    }

    public void setDomain(List<String> domain) {
        this.domain = domain == null ? null : new ArrayList<>(domain);
    }

    public List<String> getOrder() {
        return order;
    }

    public void setOrder(List<String> order) {
        this.order = order == null ? null : new ArrayList<>(order);
    }

    public Boolean getFirstWins() {
        return firstWins;
    }

    public void setFirstWins(Boolean firstWins) {
        this.firstWins = firstWins;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getRefreshMin() {
        return refreshMin;
    }

    public void setRefreshMin(Duration refreshMin) {
        this.refreshMin = refreshMin;
    }

    public Duration getRefreshMax() {
        return refreshMax;
    }

    public void setRefreshMax(Duration refreshMax) {
        this.refreshMax = refreshMax;
    }

    public List<String> getAcceptGroupIdFrom() {
        return acceptGroupIdFrom;
    }

    public void setAcceptGroupIdFrom(List<String> acceptGroupIdFrom) {
        this.acceptGroupIdFrom = acceptGroupIdFrom == null ? null : new ArrayList<>(acceptGroupIdFrom);
    }

    public Boolean getInsecureGroupId() {
        return insecureGroupId;
    }

    public void setInsecureGroupId(Boolean insecureGroupId) {
        this.insecureGroupId = insecureGroupId;
    }

    public String getBeaconPeer() {
        return beaconPeer;
    }

    public void setBeaconPeer(String beaconPeer) {
        this.beaconPeer = beaconPeer;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Boolean getUrlAllowPlain() {
        return urlAllowPlain;
    }

    public void setUrlAllowPlain(Boolean urlAllowPlain) {
        this.urlAllowPlain = urlAllowPlain;
    }

    public List<String> getUrlAllowHosts() {
        return urlAllowHosts;
    }

    public void setUrlAllowHosts(List<String> urlAllowHosts) {
        this.urlAllowHosts = urlAllowHosts == null ? null : new ArrayList<>(urlAllowHosts);
    }

    public Boolean getUrlAllowPrivate() {
        return urlAllowPrivate;
    }

    public void setUrlAllowPrivate(Boolean urlAllowPrivate) {
        this.urlAllowPrivate = urlAllowPrivate;
    }

    public Boolean getExpandNames() {
        return expandNames;
    }

    public void setExpandNames(Boolean expandNames) {
        this.expandNames = expandNames;
    }

    public Boolean getBeanLocatorsFirst() {
        return beanLocatorsFirst;
    }

    public void setBeanLocatorsFirst(Boolean beanLocatorsFirst) {
        this.beanLocatorsFirst = beanLocatorsFirst;
    }

    public Boolean getRequire() {
        return require;
    }

    public void setRequire(Boolean require) {
        this.require = require;
    }

    public Boolean getTrace() {
        return trace;
    }

    public void setTrace(Boolean trace) {
        this.trace = trace;
    }

    public String getDnsSrv() {
        return dnsSrv;
    }

    public void setDnsSrv(String dnsSrv) {
        this.dnsSrv = dnsSrv;
    }

    public String getDnsSrvScheme() {
        return dnsSrvScheme;
    }

    public void setDnsSrvScheme(String dnsSrvScheme) {
        this.dnsSrvScheme = dnsSrvScheme;
    }

    /**
     * Whether the DNS-SD locator also browses {@code _agentspaces._udp} under
     * each domain, whose instances default to the {@code dtls} scheme (SRAD
     * dtls-support DT-L-04). Off by default: one more PTR query per domain.
     */
    public Boolean getDnsSdUdp() {
        return dnsSdUdp;
    }

    public void setDnsSdUdp(Boolean dnsSdUdp) {
        this.dnsSdUdp = dnsSdUdp;
    }

    /** {@link #getDnsSdUdp()}, false when unset. */
    public boolean dnsSdUdpOrDefault() {
        return Boolean.TRUE.equals(dnsSdUdp);
    }

    public Boolean getDnsSdAnyGroup() {
        return dnsSdAnyGroup;
    }

    public void setDnsSdAnyGroup(Boolean dnsSdAnyGroup) {
        this.dnsSdAnyGroup = dnsSdAnyGroup;
    }

    public String getDocument() {
        return document;
    }

    public void setDocument(String document) {
        this.document = document;
    }

    public K8s getK8s() {
        return k8s;
    }

    public void setK8s(K8s k8s) {
        this.k8s = k8s;
    }
}
