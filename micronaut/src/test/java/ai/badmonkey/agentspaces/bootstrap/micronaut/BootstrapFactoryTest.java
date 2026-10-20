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
package ai.badmonkey.agentspaces.bootstrap.micronaut;

import ai.badmonkey.agentspaces.agent.AgentSpaces;
import ai.badmonkey.agentspaces.api.ad.PeerAdvertisement;
import ai.badmonkey.agentspaces.api.spi.BeaconHint;
import ai.badmonkey.agentspaces.api.spi.BeaconLocator;
import ai.badmonkey.agentspaces.api.spi.BeaconRequest;
import ai.badmonkey.agentspaces.bootstrap.BootstrapSettings;
import ai.badmonkey.agentspaces.bootstrap.DnsResolver;
import ai.badmonkey.agentspaces.bootstrap.JndiDnsResolver;
import ai.badmonkey.agentspaces.bootstrap.chain.BootstrapTree;
import ai.badmonkey.agentspaces.peering.bootstrap.BootstrapChain;
import ai.badmonkey.agentspaces.peering.bootstrap.SeedsLocator;
import ai.badmonkey.agentspaces.peering.node.PeerNode;
import ai.badmonkey.agentspaces.spring.AgentSpacesProperties;
import ai.badmonkey.agentspaces.spring.BootstrapChainFactory;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapFactoryTest {

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One founded group on a plain tcp port, as the core's own tests do, TLS off. */
    private static Map<String, Object> peer(int port, String founding) {
        Map<String, Object> props = new HashMap<>();
        props.put("agentspaces.security.profile", "dev-local");
        props.put("agentspaces.transport.tls.enabled", "false");
        props.put("agentspaces.bind", "127.0.0.1:" + port);
        props.put("agentspaces.tick-millis", "100");
        props.put("agentspaces.groups[0].name", "fleet");
        props.put("agentspaces.groups[0].founding", founding);
        props.put("agentspaces.groups[0].spaces[0].name", "tasks");
        props.put("agentspaces.groups[0].spaces[0].settle-window-millis", "50");
        return props;
    }

    private static AgentSpacesProperties.Group group(String name) {
        AgentSpacesProperties.Group group = new AgentSpacesProperties.Group();
        group.setName(name);
        return group;
    }

    private static List<String> names(BeaconLocator locator) {
        return ((BootstrapChain) locator).locators().stream().map(BeaconLocator::name).toList();
    }

    private static final class Recording implements BeaconLocator {
        final List<String> asked = new ArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public BeaconHint locate(BeaconRequest request) {
            asked.add(request.groupName());
            return BeaconHint.none();
        }
    }

    @Test
    void theTreeBindsAndTheFactoryComposesFromTheEnvironment() {
        Map<String, Object> props = peer(freePort(), "mn-bind-v1");
        props.put("agentspaces.bootstrap.domain", "corp.example");
        props.put("agentspaces.bootstrap.timeout", "PT2S");
        props.put("agentspaces.bootstrap.order", "seeds,url,dns-srv");
        props.put("agentspaces.bootstrap.url", "https://beacon.example/.well-known/agentspaces/beacon.json");
        props.put("agentspaces.groups[0].bootstrap.dns-srv", "_aspace._tcp.x");
        props.put("agentspaces.groups[0].bootstrap.timeout", "1500ms");
        props.put("agentspaces.groups[0].bootstrap.first-wins", "true");
        try (ApplicationContext ctx = ApplicationContext.run(props)) {
            BootstrapTree tree = ctx.getBean(BootstrapTree.class);
            BootstrapSettings fleet = tree.settingsFor("fleet");
            assertThat(fleet.getDomain()).containsExactly("corp.example");
            assertThat(fleet.getTimeout()).isEqualTo(Duration.ofMillis(1500));
            assertThat(fleet.getDnsSrv()).isEqualTo("_aspace._tcp.x");
            assertThat(fleet.firstWinsOrDefault()).isTrue();
            assertThat(tree.settingsFor("other").getTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(ctx.getBean(DnsResolver.class)).isInstanceOf(JndiDnsResolver.class);
            BootstrapChainFactory factory = ctx.getBean(BootstrapChainFactory.class);
            List<BeaconLocator> incoming = List.of(SeedsLocator.of(List.of("127.0.0.1:1")), new Recording());
            assertThat(names(factory.chainFor(group("fleet"), incoming)))
                    .containsExactly("seeds", "url", "dns-srv", "recording");
            assertThat(names(factory.chainFor(group("other"), incoming)))
                    .containsExactly("seeds", "url", "recording");
            // The fabric joined its group through the composed chain.
            assertThat(ctx.getBean(AgentSpaces.class).groupNames()).containsExactly("fleet");
        }
    }

    @Test
    void noBootstrapKeysMeansTheCoreDefaultChain() {
        try (ApplicationContext ctx = ApplicationContext.run(peer(freePort(), "mn-default-v1"))) {
            assertThat(ctx.getBean(BootstrapTree.class).isEmpty()).isTrue();
            BootstrapChainFactory factory = ctx.getBean(BootstrapChainFactory.class);
            List<BeaconLocator> incoming = List.of(SeedsLocator.of(List.of("127.0.0.1:1")), new Recording());
            assertThat(((BootstrapChain) factory.chainFor(group("fleet"), incoming)).locators())
                    .containsExactlyElementsOf(incoming);
        }
    }

    @Test
    void aResolverBeanReplacesTheDefaultAndAnSrvRecordJoinsTheSecondPeer() throws Exception {
        int seedPort = freePort();
        InMemoryDns.RECORDS.remove("_aspace._tcp.seeds.test").srv("_aspace._tcp.seeds.test", 10, 50, seedPort, "127.0.0.1");
        Map<String, Object> joinerProps = peer(freePort(), "mn-srv-join-v1");
        joinerProps.put("test.in-memory-dns", "true");
        joinerProps.put("agentspaces.bootstrap.order", "seeds,dns-srv");
        joinerProps.put("agentspaces.groups[0].bootstrap.dns-srv", "_aspace._tcp.seeds.test");
        try (ApplicationContext seed = ApplicationContext.run(peer(seedPort, "mn-srv-join-v1"));
             ApplicationContext joiner = ApplicationContext.run(joinerProps)) {
            assertThat(joiner.getBean(DnsResolver.class)).isSameAs(InMemoryDns.RECORDS);
            assertThat(joiner.getBeansOfType(DnsResolver.class)).hasSize(1);
            AgentSpaces seedSpaces = seed.getBean(AgentSpaces.class);
            PeerNode joinerNode = joiner.getBean(PeerNode.class);
            boolean admitted = false;
            for (int i = 0; i < 100 && !admitted; i++) {
                admitted = seedSpaces.group("fleet").runtime().membership().member(joinerNode.peerId()).isPresent();
                Thread.sleep(50);
            }
            assertThat(admitted).as("the joiner found the seed through the SRV record").isTrue();
            assertThat(InMemoryDns.RECORDS.queries()).isPositive();
            assertThat(joinerNode.lastBeaconHint(seedSpaces.group("fleet").runtime().id()))
                    .hasValueSatisfying(h -> assertThat(h.evidence()).contains("SRV _aspace._tcp.seeds.test"));
            assertThat(h(joinerNode, seedSpaces)).extracting(PeerAdvertisement.Endpoint::address)
                    .contains("127.0.0.1:" + seedPort);
        }
    }

    private static List<PeerAdvertisement.Endpoint> h(PeerNode node, AgentSpaces seedSpaces) {
        return node.lastBeaconHint(seedSpaces.group("fleet").runtime().id()).map(BeaconHint::endpoints).orElse(List.of());
    }

    @Test
    void anUnauthenticatedGroupIdSourceWithoutInsecureRefusesTheContext() {
        Map<String, Object> props = peer(freePort(), "mn-refused-v1");
        props.put("agentspaces.bootstrap.accept-group-id-from", "dns-sd");
        assertThatThrownBy(() -> {
            try (ApplicationContext ctx = ApplicationContext.run(props)) {
                ctx.getBean(AgentSpaces.class);
            }
        }).hasStackTraceContaining("insecure-group-id").hasStackTraceContaining("accept-group-id-from");
    }

    @Test
    void agentspacesEnabledFalseKeepsTheBeansOut() {
        Map<String, Object> props = peer(freePort(), "mn-off-v1");
        props.put("agentspaces.enabled", "false");
        try (ApplicationContext ctx = ApplicationContext.run(props)) {
            assertThat(ctx.containsBean(BootstrapChainFactory.class)).isFalse();
            assertThat(ctx.containsBean(DnsResolver.class)).isFalse();
        }
    }
}
