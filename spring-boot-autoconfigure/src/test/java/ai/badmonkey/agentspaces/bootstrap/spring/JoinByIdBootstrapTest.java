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
import ai.badmonkey.agentspaces.api.ad.GroupAdvertisement;
import ai.badmonkey.agentspaces.api.space.ConflictStrategyType;
import ai.badmonkey.agentspaces.bootstrap.DnsResolver;
import ai.badmonkey.agentspaces.bootstrap.InMemoryDnsResolver;
import ai.badmonkey.agentspaces.common.codec.CborCodec;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.peering.node.GroupFounding;
import ai.badmonkey.agentspaces.peering.node.PeerNode;
import ai.badmonkey.agentspaces.peering.node.SignedGroupAdvertisement;
import ai.badmonkey.agentspaces.spring.AgentSpacesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A group joined by id through a chain the properties build
 * (ISSUE-BootstrapRobustness §5.2 rows 1 to 4, FR-1, FR-2, FR-4 to FR-6).
 * Every test here has a seed that holds a signed founding, because only such
 * a group can be joined by id.
 */
class JoinByIdBootstrapTest {

    private static final String SRV = "_aspace._tcp.join.test";

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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

    /** A signed founding and its base64 CBOR, as {@code founding-document} takes it. */
    private record Founded(SignedGroupAdvertisement founding, String document, String id) {
        static Founded of(String name) {
            SignedGroupAdvertisement founding = GroupFounding.found(PeerIdentity.generate(), name,
                    GroupAdvertisement.MembershipPolicy.OPEN, ConflictStrategyType.LEASE_RACE,
                    GroupAdvertisement.GossipParameters.defaults(), Instant.now(), Duration.ofDays(1));
            String document = Base64.getEncoder().encodeToString(CborCodec.defaultCodec().toBytes(founding));
            return new Founded(founding, document, founding.advertisement().group().value());
        }
    }

    private static String[] common(int port) {
        return new String[] {
                "agentspaces.security.profile=dev-local",
                "agentspaces.transport.tls.enabled=false",
                "agentspaces.bind=127.0.0.1:" + port,
                "agentspaces.tick-millis=100",
                "agentspaces.groups[0].name=fleet",
                "agentspaces.groups[0].spaces[0].name=tasks",
                "agentspaces.groups[0].spaces[0].settle-window-millis=50"};
    }

    /** A seed that holds the signed founding. */
    private static ApplicationContextRunner seed(int port, Founded founded) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootstrapAutoConfiguration.class,
                        AgentSpacesAutoConfiguration.class))
                .withPropertyValues(common(port))
                .withPropertyValues("agentspaces.groups[0].founding-document=" + founded.document());
    }

    /** A peer that joins by id, with the in-memory resolver, and whatever the test adds. */
    private static ApplicationContextRunner joiner(Founded founded, long timeoutMillis) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootstrapAutoConfiguration.class,
                        AgentSpacesAutoConfiguration.class))
                .withUserConfiguration(InMemoryDns.class)
                .withPropertyValues(common(freePort()))
                .withPropertyValues(
                        "agentspaces.groups[0].join=aspace://" + founded.id(),
                        "agentspaces.groups[0].join-timeout-millis=" + timeoutMillis);
    }

    private static boolean admitted(AgentSpaces seedSpaces, PeerNode joiner) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (seedSpaces.group("fleet").runtime().membership().member(joiner.peerId()).isPresent()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** Rows 1 and 2: a join by id through dns-srv alone, no seed configured, starts and is admitted. */
    @Test
    void aJoinByIdThroughThePropertyBuiltChainAloneStarts() {
        Founded founded = Founded.of("srv-only");
        int seedPort = freePort();
        InMemoryDns.RECORDS.remove(SRV).srv(SRV, 10, 50, seedPort, "127.0.0.1");
        seed(seedPort, founded).run(seed -> {
            assertThat(seed).hasNotFailed();
            AgentSpaces seedSpaces = seed.getBean(AgentSpaces.class);
            joiner(founded, 10_000)
                    .withPropertyValues(
                            "agentspaces.bootstrap.order=seeds,dns-srv",
                            "agentspaces.groups[0].bootstrap.dns-srv=" + SRV)
                    .run(joiner -> {
                        assertThat(joiner).as("no seed is needed when the chain can locate").hasNotFailed();
                        PeerNode joinerNode = joiner.getBean(PeerNode.class);
                        assertThat(admitted(seedSpaces, joinerNode)).isTrue();
                        assertThat(joinerNode.lastBeaconHint(seedSpaces.group("fleet").runtime().id()))
                                .hasValueSatisfying(h -> assertThat(h.evidence()).contains("SRV " + SRV));
                    });
        });
    }

    /** FR-2: an order of seeds alone with no seed refuses startup and names the remedies. */
    @Test
    void anOrderWithNothingToAskRefusesStartup() {
        Founded founded = Founded.of("nothing-to-ask");
        joiner(founded, 10_000)
                .withPropertyValues("agentspaces.bootstrap.order=seeds")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasStackTraceContaining("group 'fleet' joins by GroupId URI but its bootstrap chain"
                                    + " has nothing to ask: seeds (nothing configured)")
                            .hasStackTraceContaining("agentspaces.groups[].seeds")
                            .hasStackTraceContaining("agentspaces.bootstrap.order")
                            .hasStackTraceContaining("BeaconLocator bean");
                });
    }

    /** Row 3: seeds configured, an order that leaves them out, an SRV record present; the group joins through SRV. */
    @Test
    void seedsLeftOutByTheOrderStillJoinThroughTheRestOfTheChain() {
        Founded founded = Founded.of("seeds-left-out");
        int seedPort = freePort();
        InMemoryDns.RECORDS.remove(SRV).srv(SRV, 10, 50, seedPort, "127.0.0.1");
        seed(seedPort, founded).run(seed -> {
            AgentSpaces seedSpaces = seed.getBean(AgentSpaces.class);
            joiner(founded, 10_000)
                    .withPropertyValues(
                            "agentspaces.groups[0].seeds[0]=127.0.0.1:" + seedPort,
                            "agentspaces.bootstrap.order=dns-srv",
                            "agentspaces.groups[0].bootstrap.dns-srv=" + SRV)
                    .run(joiner -> {
                        assertThat(joiner).hasNotFailed();
                        PeerNode joinerNode = joiner.getBean(PeerNode.class);
                        assertThat(admitted(seedSpaces, joinerNode)).isTrue();
                        assertThat(joinerNode.lastBeaconHint(seedSpaces.group("fleet").runtime().id()))
                                .hasValueSatisfying(h -> assertThat(h.evidence())
                                        .as("the seeds were never asked, as the order says")
                                        .doesNotContain("seeds[").contains("SRV " + SRV));
                    });
        });
    }

    /** FR-4: the SRV record appears two seconds after the joiner starts, and the join still completes. */
    @Test
    void aLateBeaconIsFoundByTheReAskInsideTheTimeout() {
        Founded founded = Founded.of("late-beacon");
        int seedPort = freePort();
        InMemoryDns.RECORDS.remove(SRV);
        seed(seedPort, founded).run(seed -> {
            AgentSpaces seedSpaces = seed.getBean(AgentSpaces.class);
            long queriesBefore = InMemoryDns.RECORDS.queries();
            Thread late = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                InMemoryDns.RECORDS.srv(SRV, 10, 50, seedPort, "127.0.0.1");
            });
            joiner(founded, 10_000)
                    .withPropertyValues(
                            "agentspaces.bootstrap.order=seeds,dns-srv",
                            "agentspaces.groups[0].bootstrap.dns-srv=" + SRV)
                    .run(joiner -> {
                        late.join();
                        assertThat(joiner).as("the record came late, inside the timeout").hasNotFailed();
                        PeerNode joinerNode = joiner.getBean(PeerNode.class);
                        assertThat(admitted(seedSpaces, joinerNode)).isTrue();
                        assertThat(InMemoryDns.RECORDS.queries() - queriesBefore)
                                .as("asked at 0 s and 1 s, found at 3 s").isBetween(2L, 4L);
                    });
        });
    }

    /** Row 4: an unreachable seed and no record; the failure reports the attempts and the endpoint. */
    @Test
    void anUnreachableSeedFailsAtTheTimeoutWithAReport() {
        Founded founded = Founded.of("unreachable");
        InMemoryDns.RECORDS.remove(SRV);
        long started = System.nanoTime();
        joiner(founded, 3_000)
                .withPropertyValues(
                        "agentspaces.groups[0].seeds[0]=127.0.0.1:1",
                        "agentspaces.bootstrap.order=seeds,dns-srv",
                        "agentspaces.groups[0].bootstrap.dns-srv=" + SRV)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasStackTraceContaining("group 'fleet': no verified founding for " + founded.id())
                            .hasStackTraceContaining("within 3000 ms")
                            .hasStackTraceContaining("locate(s)")
                            .hasStackTraceContaining("0 of 1 endpoint(s) connected")
                            .hasStackTraceContaining("127.0.0.1:1")
                            .hasStackTraceContaining("seeds[1]");
                });
        assertThat((System.nanoTime() - started) / 1_000_000L).as("the timeout stays the bound").isLessThan(6_000);
    }

    /** FR-5: require with an empty first answer refuses at once, with one locate and no slices. */
    @Test
    void requireWithAnEmptyFirstAnswerRefusesWithoutRetrying() {
        Founded founded = Founded.of("required");
        InMemoryDns.RECORDS.remove(SRV);
        long queriesBefore = InMemoryDns.RECORDS.queries();
        long started = System.nanoTime();
        joiner(founded, 10_000)
                .withPropertyValues(
                        "agentspaces.bootstrap.order=dns-srv",
                        "agentspaces.groups[0].bootstrap.dns-srv=" + SRV,
                        "agentspaces.groups[0].bootstrap.require=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasStackTraceContaining("bootstrap.require");
                });
        assertThat(InMemoryDns.RECORDS.queries() - queriesBefore).isEqualTo(1L);
        assertThat((System.nanoTime() - started) / 1_000_000L).isLessThan(3_000);
    }

    /** review-110226-deadcode-parity B2: the default order with no domain and no URL has nothing to ask, and says so at once. */
    @Test
    void theDefaultOrderWithNothingConfiguredRefusesStartupAtOnce() {
        Founded founded = Founded.of("default-order");
        long started = System.nanoTime();
        joiner(founded, 10_000)
                .withPropertyValues(
                        "agentspaces.bootstrap.domain=",
                        "agentspaces.bootstrap.url-allow-plain=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasStackTraceContaining("has nothing to ask")
                            .hasStackTraceContaining("seeds (nothing configured)")
                            .hasStackTraceContaining("dns-sd (nothing configured)");
                });
        assertThat((System.nanoTime() - started) / 1_000_000L).as("refused before the join timeout").isLessThan(5_000);
    }
}
