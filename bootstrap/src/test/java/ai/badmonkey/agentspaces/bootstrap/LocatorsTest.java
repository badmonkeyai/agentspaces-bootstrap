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

import ai.badmonkey.agentspaces.api.ad.GroupAdvertisement;
import ai.badmonkey.agentspaces.api.ad.PeerAdvertisement;
import ai.badmonkey.agentspaces.api.space.ConflictStrategyType;
import ai.badmonkey.agentspaces.api.spi.BeaconHint;
import ai.badmonkey.agentspaces.api.spi.BeaconRequest;
import ai.badmonkey.agentspaces.common.codec.CborCodec;
import ai.badmonkey.agentspaces.common.id.GroupId;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.peering.node.GroupFounding;
import ai.badmonkey.agentspaces.peering.node.SignedGroupAdvertisement;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The locators over an in-memory resolver and a loopback HTTP server. */
class LocatorsTest {

    private static final BeaconRequest REQUEST = new BeaconRequest("research-fleet", Optional.empty(), "tls",
            Set.of("tls", "tcp"));

    private final PeerIdentity beacon = PeerIdentity.generate();
    private final PeerIdentity founder = PeerIdentity.generate();
    private final SignedGroupAdvertisement founding = GroupFounding.found(founder, "research-fleet",
            GroupAdvertisement.MembershipPolicy.OPEN, ConflictStrategyType.LEASE_RACE,
            GroupAdvertisement.GossipParameters.defaults(), Instant.parse("2026-10-14T00:00:00Z"), Duration.ofDays(1));
    private final String groupId = founding.advertisement().group().value();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private BeaconDocument document() {
        return BeaconDocument.signed(beacon, Instant.parse("2026-10-14T00:00:00Z"), Duration.ofSeconds(300), List.of(
                new BeaconDocument.Group("research-fleet", "aspace://" + groupId,
                        Base64.getEncoder().encodeToString(CborCodec.defaultCodec().toBytes(founding)),
                        List.of(new BeaconDocument.Seed("tls", "seed-b.corp.example:7500", 1),
                                new BeaconDocument.Seed("tls", "seed-a.corp.example:7500", 0)),
                        Map.of("session", 1, "batch", 1), "239.255.42.99:7787", Map.of("region", "eu-west-1"), null)));
    }

    private String serve(byte[] body, String path) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static UrlLocator.Options loopback() {
        return UrlLocator.Options.defaults().withAllowPlain(true).withAllowPrivate(true);
    }

    @Test
    void srvRecordsOrderByPriorityThenWeightAndKeepTheName() throws Exception {
        InMemoryDnsResolver dns = new InMemoryDnsResolver()
                .srv("_aspace._tcp.seeds.example", 20, 0, 7500, "late.example")
                .srv("_aspace._tcp.seeds.example", 10, 100, 7500, "heavy.example")
                .srv("_aspace._tcp.seeds.example", 10, 1, 7500, "light.example");
        SrvLocator locator = new SrvLocator("_aspace._tcp.seeds.example", null, dns, new Random(1), Duration.ofMinutes(1));
        BeaconHint hint = locator.locate(REQUEST);
        assertThat(hint.endpoints()).extracting(PeerAdvertisement.Endpoint::transport).containsOnly("tls");
        assertThat(hint.endpoints()).extracting(PeerAdvertisement.Endpoint::address)
                .contains("heavy.example:7500", "light.example:7500", "late.example:7500");
        assertThat(hint.endpoints().get(2).address()).as("priority 20 after both 10s").isEqualTo("late.example:7500");
        int heavyFirst = 0;
        for (int seed = 0; seed < 200; seed++) {
            List<PeerAdvertisement.Endpoint> order = SrvLocator.endpoints(dns.srv("_aspace._tcp.seeds.example"), "tls",
                    new Random(seed));
            if (order.get(0).address().startsWith("heavy")) {
                heavyFirst++;
            }
        }
        assertThat(heavyFirst).as("weight 100 beats weight 1 almost always").isGreaterThan(180);
        assertThat(new SrvLocator("_aspace._tcp.nowhere.example", null, dns).locate(REQUEST).isEmpty()).isTrue();
    }

    @Test
    void dnsSdFindsInstancesUnderTheDomainFiltersByGroupAndFollowsTheUrl() throws Exception {
        String url = serve(document().toBytes(), "/.well-known/agentspaces/beacon.json");
        InMemoryDnsResolver dns = new InMemoryDnsResolver()
                .ptr("_agentspaces._tcp.corp.example", "research-fleet._agentspaces._tcp.corp.example")
                .ptr("_agentspaces._tcp.corp.example", "other._agentspaces._tcp.corp.example")
                .srv("research-fleet._agentspaces._tcp.corp.example", 10, 50, 7500, "seed-a.corp.example")
                .srv("research-fleet._agentspaces._tcp.corp.example", 10, 50, 7500, "seed-b.corp.example")
                .txt("research-fleet._agentspaces._tcp.corp.example", "v=1", "group=research-fleet",
                        "id=aspace://" + groupId, "scheme=tcp", "url=" + url)
                .srv("other._agentspaces._tcp.corp.example", 10, 50, 7500, "other.corp.example")
                .txt("other._agentspaces._tcp.corp.example", "v=1", "group=other");
        DnsSdLocator locator = new DnsSdLocator(DomainFinder.of(List.of("nowhere.example", "corp.example")), dns, false,
                UrlLocator.follower(loopback(), null));
        BeaconHint hint = locator.locate(REQUEST);
        assertThat(hint.endpoints()).extracting(PeerAdvertisement.Endpoint::address)
                .containsExactlyInAnyOrder("seed-a.corp.example:7500", "seed-b.corp.example:7500",
                        "seed-a.corp.example:7500", "seed-b.corp.example:7500")
                .doesNotContain("other.corp.example:7500");
        assertThat(hint.endpoints().get(0).transport()).as("the TXT scheme").isEqualTo("tcp");
        assertThat(hint.groupId()).contains(GroupId.of(groupId));
        assertThat(hint.founding()).isPresent();
        assertThat(hint.parameters()).containsEntry("wire.session", "1").containsEntry("region", "eu-west-1")
                .containsEntry("multicast", "239.255.42.99:7787").containsEntry("beacon", beacon.peerId().value());
        assertThat(hint.evidence()).contains("other._agentspaces._tcp.corp.example skipped").contains("GET " + url);

        DnsSdLocator any = new DnsSdLocator(DomainFinder.of(List.of("corp.example")), dns, true, null);
        assertThat(any.locate(REQUEST).endpoints()).extracting(PeerAdvertisement.Endpoint::address)
                .contains("other.corp.example:7500");
    }

    @Test
    void theTxtPointerFollowsItsUrlAndCarriesTheDocumentsTtl() throws Exception {
        String url = serve(document().toBytes(), "/beacon.json");
        InMemoryDnsResolver dns = new InMemoryDnsResolver().txt("agentspaces.corp.example", "v=1", "url=" + url);
        TxtLocator locator = new TxtLocator(DomainFinder.of(List.of("corp.example")), dns,
                UrlLocator.follower(loopback(), null));
        BeaconHint hint = locator.locate(REQUEST);
        assertThat(hint.endpoints()).extracting(PeerAdvertisement.Endpoint::address)
                .containsExactly("seed-a.corp.example:7500", "seed-b.corp.example:7500");
        assertThat(hint.ttl()).isEqualTo(Duration.ofSeconds(300));
        assertThat(hint.groupId()).contains(GroupId.of(groupId));
        assertThat(new TxtLocator(DomainFinder.of(List.of("nowhere.example")), dns,
                UrlLocator.follower(loopback(), null)).locate(REQUEST).isEmpty()).isTrue();
    }

    @Test
    void theUrlLocatorSelectsByIdOrNameVerifiesAndHonoursThePin() throws Exception {
        String url = serve(document().toBytes(), "/beacon.json");
        BeaconHint byName = new UrlLocator(List.of(URI.create(url)), loopback(), null).locate(REQUEST);
        assertThat(byName.endpoints()).hasSize(2);
        BeaconRequest byId = new BeaconRequest("whatever", Optional.of(GroupId.of(groupId)), "tls", Set.of("tls"));
        assertThat(new UrlLocator(List.of(URI.create(url)), loopback(), null).locate(byId).founding()).isPresent();
        BeaconRequest wrongId = new BeaconRequest("research-fleet", Optional.of(GroupId.of("zOther")), "tls", Set.of("tls"));
        assertThat(new UrlLocator(List.of(URI.create(url)), loopback(), null).locate(wrongId).isEmpty())
                .as("a known id never falls back to the name").isTrue();

        UrlLocator pinnedRight = new UrlLocator(List.of(URI.create(url)), loopback().withBeaconPeer(beacon.peerId()), null);
        assertThat(pinnedRight.locate(REQUEST).endpoints()).hasSize(2);
        UrlLocator pinnedWrong = new UrlLocator(List.of(URI.create(url)),
                loopback().withBeaconPeer(PeerIdentity.generate().peerId()), null);
        assertThatThrownBy(() -> pinnedWrong.locate(REQUEST)).isInstanceOf(IOException.class)
                .hasMessageContaining("pinned");
    }

    @Test
    void aTamperedDocumentAndTheGuardsAreRefused() throws Exception {
        BeaconDocument tampered = document().withSignature(Base64.getEncoder().encodeToString(new byte[64]));
        String url = serve(tampered.toBytes(), "/beacon.json");
        assertThatThrownBy(() -> new UrlLocator(List.of(URI.create(url)), loopback(), null).locate(REQUEST))
                .isInstanceOf(IOException.class).hasMessageContaining("signature");
        assertThatThrownBy(() -> new UrlLocator(List.of(URI.create(url)), UrlLocator.Options.defaults(), null)
                .locate(REQUEST)).isInstanceOf(IOException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> new UrlLocator(List.of(URI.create(url)),
                UrlLocator.Options.defaults().withAllowPlain(true), null).locate(REQUEST))
                .isInstanceOf(IOException.class).hasMessageContaining("private");
        assertThatThrownBy(() -> new UrlLocator(List.of(URI.create(url)),
                loopback().withAllowHosts(Set.of("beacon.example")), null).locate(REQUEST))
                .isInstanceOf(IOException.class).hasMessageContaining("url-allow-hosts");
    }

    @Test
    void theDocumentRoundTripsVerifiesAndRefusesSecrets() throws Exception {
        BeaconDocument document = document();
        BeaconDocument parsed = BeaconDocument.parse(document.toBytes());
        assertThat(parsed).isEqualTo(document);
        assertThat(parsed.verifies()).isTrue();
        assertThat(parsed.signer()).contains(beacon.peerId());
        assertThat(parsed.groupById("aspace://" + groupId)).isPresent();
        assertThat(parsed.groupById(groupId)).isPresent();
        assertThat(parsed.groupByName("research-fleet")).isPresent();
        BeaconDocument unknownFields = BeaconDocument.parse(
                document.toJson().replace("\"v\":1", "\"v\":1,\"future\":{\"x\":1}").getBytes(StandardCharsets.UTF_8));
        assertThat(unknownFields.groups()).hasSize(1);
        assertThatThrownBy(() -> new BeaconDocument.Group("g", null, null, List.of(), null, null,
                Map.of("content-key", "x"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(document.toJson()).startsWith("{\"v\":1,\"issuer\":\"" + beacon.peerId().value() + "\"");
    }

    @Test
    void theStaticDocumentAndTheKubernetesOwnerName() throws Exception {
        Path file = Files.createTempFile("beacon", ".json");
        Files.write(file, document().toBytes());
        BeaconHint hint = StaticDocumentLocator.file(file, UrlLocator.Options.defaults()).locate(REQUEST);
        assertThat(hint.endpoints()).hasSize(2);
        assertThat(hint.evidence()).startsWith("file ");
        assertThat(StaticDocumentLocator.file(Path.of("/nowhere/beacon.json"), UrlLocator.Options.defaults())
                .locate(REQUEST).isEmpty()).isTrue();
        assertThat(K8sLocator.owner("agentspaces-seeds", "fleet", "aspace", null))
                .isEqualTo("_aspace._tcp.agentspaces-seeds.fleet.svc.cluster.local");
        InMemoryDnsResolver dns = new InMemoryDnsResolver()
                .srv("_aspace._tcp.agentspaces-seeds.fleet.svc.cluster.local", 0, 0, 7500,
                        "agentspaces-seed-0.agentspaces-seeds.fleet.svc.cluster.local");
        assertThat(new K8sLocator("agentspaces-seeds", "fleet", "aspace", null, null, dns).locate(REQUEST).endpoints())
                .extracting(PeerAdvertisement.Endpoint::address)
                .containsExactly("agentspaces-seed-0.agentspaces-seeds.fleet.svc.cluster.local:7500");
    }

    /** SRAD dtls-support DT-L-03, DT-L-04: a UDP (DTLS) port is found under _udp and dials as dtls. */
    @Test
    void udpPortsAreFoundUnderTheUdpLabelAndDialAsDtls() throws Exception {
        assertThat(K8sLocator.owner("agentspaces-seeds", "fleet", "aspace-dtls", null, "udp"))
                .isEqualTo("_aspace-dtls._udp.agentspaces-seeds.fleet.svc.cluster.local");
        InMemoryDnsResolver dns = new InMemoryDnsResolver()
                .srv("_aspace-dtls._udp.agentspaces-seeds.fleet.svc.cluster.local", 0, 0, 7502, "seed-0.fleet")
                .ptr("_agentspaces._udp.corp.example", "fleet._agentspaces._udp.corp.example")
                .srv("fleet._agentspaces._udp.corp.example", 10, 50, 7502, "seed-a.corp.example")
                .txt("fleet._agentspaces._udp.corp.example", "v=1", "group=research-fleet");
        List<PeerAdvertisement.Endpoint> k8s = new K8sLocator("agentspaces-seeds", "fleet", "aspace-dtls", null,
                "udp", null, dns).locate(REQUEST).endpoints();
        assertThat(k8s).extracting(e -> e.transport() + "://" + e.address()).containsExactly("dtls://seed-0.fleet:7502");
        assertThatThrownBy(() -> new K8sLocator("s", "n", "p", null, "sctp", null, dns))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bootstrap.k8s.protocol");

        DnsSdLocator tcpOnly = new DnsSdLocator(DomainFinder.of(List.of("corp.example")), dns, false, null);
        assertThat(tcpOnly.locate(REQUEST).endpoints()).as("udp browsing is opt-in").isEmpty();
        DnsSdLocator udp = new DnsSdLocator(DomainFinder.of(List.of("corp.example")), dns, false, null)
                .browsingUdp(true);
        assertThat(udp.locate(REQUEST).endpoints()).extracting(e -> e.transport() + "://" + e.address())
                .containsExactly("dtls://seed-a.corp.example:7502");
    }

    @Test
    void theAssemblyFollowsTheOrderAndRefusesAnInsecureGroupIdSource() throws Exception {
        BootstrapSettings settings = new BootstrapSettings();
        settings.setDomain(List.of("corp.example"));
        settings.setUrl("https://beacon.example/.well-known/agentspaces/beacon.json");
        settings.setDnsSrv("_aspace._tcp.seeds.example");
        settings.setOrder(List.of("seeds", "url", "dns-srv", "dns-sd", "k8s"));
        InMemoryDnsResolver dns = new InMemoryDnsResolver();
        assertThat(LocatorAssembly.locators(settings, dns, null)).extracting(l -> l.name())
                .containsExactly("url", "dns-srv", "dns-sd");
        assertThat(LocatorAssembly.acceptGroupIdFrom(settings, dns)).containsExactlyInAnyOrder("url", "document");
        settings.setAcceptGroupIdFrom(List.of("dns-sd"));
        assertThatThrownBy(() -> LocatorAssembly.acceptGroupIdFrom(settings, dns))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("insecure-group-id");
        settings.setInsecureGroupId(true);
        assertThat(LocatorAssembly.acceptGroupIdFrom(settings, dns)).containsExactly("dns-sd");
        assertThat(LocatorAssembly.acceptGroupIdFrom(settings, new InMemoryDnsResolver(true))).containsExactly("dns-sd");
        BootstrapSettings bad = new BootstrapSettings();
        bad.setOrder(List.of("carrier-pigeon"));
        assertThatThrownBy(() -> LocatorAssembly.locators(bad, dns, null)).isInstanceOf(IllegalStateException.class);
        BootstrapSettings group = new BootstrapSettings();
        group.setUrl("https://other.example/b.json");
        BootstrapSettings merged = BootstrapSettings.merge(settings, group);
        assertThat(merged.getUrl()).isEqualTo("https://other.example/b.json");
        assertThat(merged.getDomain()).containsExactly("corp.example");
    }

    @Test
    void resolvConfAndJndiTxtRenderingsParse() throws Exception {
        Path resolv = Files.createTempFile("resolv", ".conf");
        Files.writeString(resolv, "nameserver 10.0.0.2\nsearch corp.example fleet.svc.cluster.local\ndomain Corp.Example.\n");
        java.util.Set<String> domains = new java.util.LinkedHashSet<>();
        assertThat(DomainFinder.resolvConf(domains, resolv)).isTrue();
        assertThat(domains).containsExactly("corp.example", "fleet.svc.cluster.local");
        assertThat(JndiDnsResolver.characterStrings("\"v=1\" \"url=https://a.example/b?c=d\" \"x=\\\"q\\\"\""))
                .containsExactly("v=1", "url=https://a.example/b?c=d", "x=\"q\"");
        assertThat(JndiDnsResolver.characterStrings("v=1")).containsExactly("v=1");
        assertThat(DomainFinder.of(List.of("Corp.Example.", "local")).get()).containsExactly("corp.example");
    }
}
