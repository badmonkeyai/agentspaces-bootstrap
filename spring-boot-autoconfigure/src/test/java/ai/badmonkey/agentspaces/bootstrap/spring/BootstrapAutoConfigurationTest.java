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
package ai.badmonkey.agentspaces.bootstrap.spring;

import ai.badmonkey.agentspaces.agent.AgentSpaces;
import ai.badmonkey.agentspaces.api.ad.PeerAdvertisement;
import ai.badmonkey.agentspaces.api.spi.BeaconHint;
import ai.badmonkey.agentspaces.api.spi.BeaconLocator;
import ai.badmonkey.agentspaces.api.spi.BeaconRequest;
import ai.badmonkey.agentspaces.bootstrap.BootstrapSettings;
import ai.badmonkey.agentspaces.bootstrap.DnsResolver;
import ai.badmonkey.agentspaces.bootstrap.InMemoryDnsResolver;
import ai.badmonkey.agentspaces.bootstrap.JndiDnsResolver;
import ai.badmonkey.agentspaces.peering.bootstrap.BootstrapChain;
import ai.badmonkey.agentspaces.peering.bootstrap.SeedsLocator;
import ai.badmonkey.agentspaces.peering.node.PeerNode;
import ai.badmonkey.agentspaces.spring.AgentSpacesAutoConfiguration;
import ai.badmonkey.agentspaces.spring.AgentSpacesProperties;
import ai.badmonkey.agentspaces.spring.BootstrapChainFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapAutoConfigurationTest {

    private static final BeaconRequest REQUEST = new BeaconRequest("fleet", Optional.empty(), "tcp", Set.of("tcp"));

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** This module alone: the properties and the two beans, no fabric. */
    private static ApplicationContextRunner alone() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootstrapAutoConfiguration.class));
    }

    /** The core starter and this module: a whole peer. */
    private static ApplicationContextRunner peer(int port, String founding) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootstrapAutoConfiguration.class,
                        AgentSpacesAutoConfiguration.class))
                .withPropertyValues(
                        "agentspaces.security.profile=dev-local",
                        "agentspaces.transport.tls.enabled=false",
                        "agentspaces.bind=127.0.0.1:" + port,
                        "agentspaces.tick-millis=100",
                        "agentspaces.groups[0].name=fleet",
                        "agentspaces.groups[0].founding=" + founding,
                        "agentspaces.groups[0].spaces[0].name=tasks",
                        "agentspaces.groups[0].spaces[0].settle-window-millis=50");
    }

    private static AgentSpacesProperties.Group group(String name) {
        AgentSpacesProperties.Group group = new AgentSpacesProperties.Group();
        group.setName(name);
        return group;
    }

    private static List<String> names(BeaconLocator locator) {
        return ((BootstrapChain) locator).locators().stream().map(BeaconLocator::name).toList();
    }

    /** A bean locator that records whether it was asked. */
    private static final class Recording implements BeaconLocator {
        final List<String> asked = new ArrayList<>();
        final int port;

        Recording(int port) {
            this.port = port;
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public BeaconHint locate(BeaconRequest request) {
            asked.add(request.groupName());
            return port == 0 ? BeaconHint.none()
                    : BeaconHint.of(List.of(new PeerAdvertisement.Endpoint("tcp", "127.0.0.1:" + port, 0)), name());
        }
    }

    @Test
    void thePropertiesBindAndTheGroupBlockOverridesTheFleetBlock() {
        alone().withPropertyValues(
                "agentspaces.bootstrap.domain=corp.example",
                "agentspaces.bootstrap.timeout=PT2S",
                "agentspaces.bootstrap.refresh-min=45s",
                "agentspaces.bootstrap.url-allow-hosts=a.example,b.example",
                "agentspaces.bootstrap.k8s.port-name=aspace",
                "agentspaces.groups[0].name=fleet",
                "agentspaces.groups[0].bootstrap.dns-srv=_aspace._tcp.x",
                "agentspaces.groups[0].bootstrap.timeout=1500ms",
                "agentspaces.groups[0].founding=t",
                "agentspaces.groups[0].seeds[0]=127.0.0.1:1",
                "agentspaces.groups[1].name=other")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BootstrapProperties properties = context.getBean(BootstrapProperties.class);
                    BootstrapSettings fleet = properties.settingsFor("fleet");
                    assertThat(fleet.getDomain()).containsExactly("corp.example");
                    assertThat(fleet.getTimeout()).isEqualTo(Duration.ofMillis(1500));
                    assertThat(fleet.getRefreshMin()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(fleet.getUrlAllowHosts()).containsExactly("a.example", "b.example");
                    assertThat(fleet.getK8s().getPortName()).isEqualTo("aspace");
                    assertThat(fleet.getDnsSrv()).isEqualTo("_aspace._tcp.x");
                    BootstrapSettings other = properties.settingsFor("other");
                    assertThat(other.getTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(other.getDnsSrv()).isNull();
                    assertThat(properties.groupSettings()).containsOnlyKeys("fleet");
                    assertThat(context.getBean(DnsResolver.class)).isInstanceOf(JndiDnsResolver.class);
                });
    }

    @Test
    void theChainFactoryComposesSeedsThenPropertiesThenBeansInTheConfiguredOrder() {
        alone().withPropertyValues(
                "agentspaces.bootstrap.order=seeds,url,dns-srv",
                "agentspaces.bootstrap.url=https://beacon.example/.well-known/agentspaces/beacon.json",
                "agentspaces.groups[0].name=fleet",
                "agentspaces.groups[0].bootstrap.dns-srv=_aspace._tcp.x")
                .run(context -> {
                    BootstrapChainFactory factory = context.getBean(BootstrapChainFactory.class);
                    List<BeaconLocator> incoming = List.of(SeedsLocator.of(List.of("127.0.0.1:1")), new Recording(0));
                    assertThat(names(factory.chainFor(group("fleet"), incoming)))
                            .containsExactly("seeds", "url", "dns-srv", "recording");
                    assertThat(names(factory.chainFor(group("other"), incoming)))
                            .containsExactly("seeds", "url", "recording");
                });
    }

    @Test
    void beanLocatorsFirstAndFirstWinsAreHonoured() {
        alone().withPropertyValues(
                "agentspaces.bootstrap.order=seeds,dns-srv",
                "agentspaces.bootstrap.dns-srv=_aspace._tcp.x",
                "agentspaces.bootstrap.bean-locators-first=true",
                "agentspaces.bootstrap.first-wins=true")
                .run(context -> {
                    BootstrapChainFactory factory = context.getBean(BootstrapChainFactory.class);
                    Recording first = new Recording(7001);
                    Recording second = new Recording(7002);
                    BeaconLocator chain = factory.chainFor(group("fleet"),
                            List.of(SeedsLocator.of(List.of()), first, second));
                    assertThat(names(chain)).containsExactly("seeds", "recording", "recording", "dns-srv");
                    BeaconHint hint = chain.locate(REQUEST);
                    assertThat(hint.endpoints()).extracting(PeerAdvertisement.Endpoint::address)
                            .containsExactly("127.0.0.1:7001");
                    assertThat(first.asked).containsExactly("fleet");
                    assertThat(second.asked).as("first-wins stops at the first answer").isEmpty();
                });
    }

    @Test
    void anEmptyBootstrapBlockYieldsTheDefaultChainOverTheIncomingLocators() {
        alone().withPropertyValues(
                "agentspaces.groups[0].name=fleet",
                "agentspaces.groups[0].founding=t",
                "agentspaces.groups[0].seeds[0]=127.0.0.1:1")
                .run(context -> {
                    BootstrapProperties properties = context.getBean(BootstrapProperties.class);
                    assertThat(properties.isEmpty()).isTrue();
                    BootstrapChainFactory factory = context.getBean(BootstrapChainFactory.class);
                    List<BeaconLocator> incoming = List.of(SeedsLocator.of(List.of("127.0.0.1:1")), new Recording(0));
                    BeaconLocator chain = factory.chainFor(group("fleet"), incoming);
                    assertThat(((BootstrapChain) chain).locators()).containsExactlyElementsOf(incoming);
                });
    }

    @Test
    void anUnauthenticatedGroupIdSourceWithoutInsecureRefusesTheContext() {
        peer(freePort(), "refused-v1")
                .withPropertyValues("agentspaces.bootstrap.accept-group-id-from=dns-sd")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasStackTraceContaining("insecure-group-id")
                            .hasStackTraceContaining("accept-group-id-from");
                });
        peer(freePort(), "refused-v2")
                .withPropertyValues("agentspaces.groups[0].bootstrap.order=seeds,consul")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasStackTraceContaining("agentspaces.groups[].bootstrap (group 'fleet')")
                            .hasStackTraceContaining("consul");
                });
    }

    /** The test's own resolver, which must replace the JNDI default. */
    @Configuration(proxyBeanMethods = false)
    static class InMemoryDns {
        static final InMemoryDnsResolver RECORDS = new InMemoryDnsResolver();

        @Bean
        DnsResolver inMemoryDnsResolver() {
            return RECORDS;
        }
    }

    @Test
    void aResolverBeanReplacesTheDefaultAndAnSrvRecordJoinsTheSecondPeer() {
        int seedPort = freePort();
        InMemoryDns.RECORDS.remove("_aspace._tcp.seeds.test").srv("_aspace._tcp.seeds.test", 10, 50, seedPort, "127.0.0.1");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentSpacesAutoConfiguration.class))
                .withPropertyValues(
                        "agentspaces.security.profile=dev-local",
                        "agentspaces.transport.tls.enabled=false",
                        "agentspaces.bind=127.0.0.1:" + seedPort,
                        "agentspaces.tick-millis=100",
                        "agentspaces.groups[0].name=fleet",
                        "agentspaces.groups[0].founding=srv-join-v1",
                        "agentspaces.groups[0].spaces[0].name=tasks",
                        "agentspaces.groups[0].spaces[0].settle-window-millis=50")
                .run(seed -> {
                    assertThat(seed).hasNotFailed();
                    AgentSpaces seedSpaces = seed.getBean(AgentSpaces.class);
                    peer(freePort(), "srv-join-v1")
                            .withUserConfiguration(InMemoryDns.class)
                            .withPropertyValues(
                                    "agentspaces.bootstrap.order=seeds,dns-srv",
                                    "agentspaces.groups[0].bootstrap.dns-srv=_aspace._tcp.seeds.test")
                            .run(joiner -> {
                                assertThat(joiner).hasNotFailed();
                                assertThat(joiner.getBean(DnsResolver.class)).isSameAs(InMemoryDns.RECORDS);
                                assertThat(joiner.getBeansOfType(DnsResolver.class)).hasSize(1);
                                PeerNode joinerNode = joiner.getBean(PeerNode.class);
                                boolean admitted = false;
                                for (int i = 0; i < 100 && !admitted; i++) {
                                    admitted = seedSpaces.group("fleet").runtime().membership()
                                            .member(joinerNode.peerId()).isPresent();
                                    Thread.sleep(50);
                                }
                                assertThat(admitted).as("the joiner found the seed through the SRV record").isTrue();
                                assertThat(InMemoryDns.RECORDS.queries()).isPositive();
                                assertThat(joinerNode.lastBeaconHint(seedSpaces.group("fleet").runtime().id()))
                                        .hasValueSatisfying(h -> assertThat(h.evidence()).contains("SRV _aspace._tcp.seeds.test"));
                            });
                });
    }
}
