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

import ai.badmonkey.agentspaces.api.ad.PeerAdvertisement;
import ai.badmonkey.agentspaces.api.spi.BeaconHint;
import ai.badmonkey.agentspaces.api.spi.BeaconLocator;
import ai.badmonkey.agentspaces.api.spi.BeaconRequest;
import ai.badmonkey.agentspaces.bootstrap.BootstrapSettings;
import ai.badmonkey.agentspaces.bootstrap.InMemoryDnsResolver;
import ai.badmonkey.agentspaces.peering.bootstrap.BootstrapChain;
import ai.badmonkey.agentspaces.peering.bootstrap.SeedsLocator;
import ai.badmonkey.agentspaces.spring.AgentSpacesProperties;
import ai.badmonkey.agentspaces.spring.BootstrapChainFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChainComposerTest {

    private static final BeaconRequest REQUEST = new BeaconRequest("fleet", Optional.empty(), "tcp", Set.of("tcp"));

    private static AgentSpacesProperties.Group group(String name) {
        AgentSpacesProperties.Group group = new AgentSpacesProperties.Group();
        group.setName(name);
        return group;
    }

    /** A bean locator that records whether it was asked. */
    private static final class Bean implements BeaconLocator {
        final String name;
        final List<String> asked = new ArrayList<>();
        final int port;

        Bean(String name, int port) {
            this.name = name;
            this.port = port;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public BeaconHint locate(BeaconRequest request) {
            asked.add(request.groupName());
            return port == 0 ? BeaconHint.none()
                    : BeaconHint.of(List.of(new PeerAdvertisement.Endpoint("tcp", "127.0.0.1:" + port, 0)), name);
        }
    }

    private static List<String> names(BeaconLocator locator) {
        return ((BootstrapChain) locator).locators().stream().map(BeaconLocator::name).toList();
    }

    @Test
    void noBootstrapKeysMeansTheDefaultChainOverTheIncomingLocators() {
        BootstrapChainFactory factory = ChainComposer.factory(null, Map.of(), new InMemoryDnsResolver());
        List<BeaconLocator> incoming = List.of(SeedsLocator.of(List.of("127.0.0.1:1")), new Bean("app", 0));
        assertThat(names(factory.chainFor(group("fleet"), incoming))).containsExactly("seeds", "app");
    }

    @Test
    void theGroupBlockOverridesTheFleetBlockAndTheOrderIsSeedsPropertiesBeans() {
        BootstrapSettings fleet = new BootstrapSettings();
        fleet.setOrder(List.of("seeds", "url", "dns-srv"));
        fleet.setUrl("https://a.example/.well-known/agentspaces/beacon.json");
        BootstrapSettings group = new BootstrapSettings();
        group.setDnsSrv("_aspace._tcp.seeds.example");
        BootstrapChainFactory factory = ChainComposer.factory(fleet, Map.of("fleet", group), new InMemoryDnsResolver());
        List<BeaconLocator> incoming = List.of(SeedsLocator.of(List.of()), new Bean("app", 0));
        assertThat(names(factory.chainFor(group("fleet"), incoming))).containsExactly("seeds", "url", "dns-srv", "app");
        // Another group sees the fleet block only: no dns-srv name, so none.
        assertThat(names(factory.chainFor(group("other"), incoming))).containsExactly("seeds", "url", "app");
    }

    @Test
    void beanLocatorsFirstAndAnOrderWithoutSeedsAreHonoured() {
        BootstrapSettings fleet = new BootstrapSettings();
        fleet.setOrder(List.of("dns-srv"));
        fleet.setDnsSrv("_aspace._tcp.seeds.example");
        fleet.setBeanLocatorsFirst(true);
        BootstrapChainFactory factory = ChainComposer.factory(fleet, Map.of(), new InMemoryDnsResolver());
        List<BeaconLocator> incoming = List.of(SeedsLocator.of(List.of("127.0.0.1:1")), new Bean("app", 0));
        assertThat(names(factory.chainFor(group("fleet"), incoming))).containsExactly("app", "dns-srv");
    }

    @Test
    void firstWinsStopsAtTheFirstLocatorWithEndpointsAndTheTimeoutIsApplied() throws Exception {
        BootstrapSettings fleet = new BootstrapSettings();
        fleet.setFirstWins(true);
        fleet.setTimeout(Duration.ofSeconds(1));
        BootstrapChainFactory factory = ChainComposer.factory(fleet, Map.of(), new InMemoryDnsResolver());
        Bean first = new Bean("first", 7001);
        Bean second = new Bean("second", 7002);
        BeaconLocator chain = factory.chainFor(group("fleet"), List.of(SeedsLocator.of(List.of()), first, second));
        BeaconHint hint = chain.locate(REQUEST);
        assertThat(hint.endpoints()).extracting(PeerAdvertisement.Endpoint::address).containsExactly("127.0.0.1:7001");
        assertThat(first.asked).containsExactly("fleet");
        assertThat(second.asked).isEmpty();
    }

    @Test
    void theSettingsBecomeTheChainsLocatePolicy() throws Exception {
        BootstrapSettings settings = new BootstrapSettings();
        settings.setRefreshMin(java.time.Duration.ofSeconds(10));
        settings.setRefreshMax(java.time.Duration.ofMinutes(2));
        settings.setRequire(true);
        settings.setTrace(true);
        BeaconLocator chain = ChainComposer.compose(settings, List.of(), new InMemoryDnsResolver(), null);
        assertThat(chain).isInstanceOf(BootstrapChain.class);
        ai.badmonkey.agentspaces.api.spi.LocatePolicy policy = chain.policy();
        assertThat(policy.minInterval()).contains(java.time.Duration.ofSeconds(10));
        assertThat(policy.maxInterval()).contains(java.time.Duration.ofMinutes(2));
        assertThat(policy.require()).isTrue();
        assertThat(policy.trace()).isTrue();
    }

    @Test
    void misconfigurationIsRefusedWhenTheFactoryIsMadeNamingTheProperty() {
        BootstrapSettings unknown = new BootstrapSettings();
        unknown.setOrder(List.of("seeds", "consul"));
        assertThatThrownBy(() -> ChainComposer.factory(unknown, Map.of(), new InMemoryDnsResolver()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentspaces.bootstrap.order").hasMessageContaining("consul");
        BootstrapSettings insecure = new BootstrapSettings();
        insecure.setAcceptGroupIdFrom(List.of("dns-sd"));
        assertThatThrownBy(() -> ChainComposer.factory(null, Map.of("fleet", insecure), new InMemoryDnsResolver()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("group 'fleet'").hasMessageContaining("insecure-group-id");
        // An authenticated resolver makes the same list acceptable.
        assertThat(ChainComposer.factory(null, Map.of("fleet", insecure), new InMemoryDnsResolver(true))).isNotNull();
    }

    @Test
    void theTreeBindsKebabCaseKeysAndLenientDurationsAndIgnoresTheHostsKeys() {
        Map<String, Object> tree = Map.of(
                "bind", "127.0.0.1:7500",
                "bootstrap", Map.of("domain", "corp.example", "timeout", "PT2S", "refresh-min", "45s",
                        "order", "seeds, url,dns-srv",               // one string, as a flat property arrives
                        "url-allow-hosts", List.of("a.example", "b.example"), "k8s", Map.of("port-name", "aspace")),
                "groups", List.of(Map.of("name", "fleet", "founding", "fleet-v1",
                        "bootstrap", Map.of("dns-srv", "_aspace._tcp.x", "first-wins", true),
                        "bootstrap.timeout", 1500,                  // the flattened form inside a list element
                        "bootstrap.k8s.service", "seeds")),
                "groups[0]", Map.of("name", "ignored"));
        BootstrapTree bound = BootstrapTree.bind(tree);
        BootstrapSettings merged = bound.settingsFor("fleet");
        assertThat(merged.getDomain()).containsExactly("corp.example");
        assertThat(merged.getOrder()).containsExactly("seeds", "url", "dns-srv");
        assertThat(merged.getTimeout()).isEqualTo(Duration.ofMillis(1500));
        assertThat(merged.getRefreshMin()).isEqualTo(Duration.ofSeconds(45));
        assertThat(merged.getUrlAllowHosts()).containsExactly("a.example", "b.example");
        assertThat(merged.getK8s().getPortName()).isNull(); // the group's k8s block replaces the fleet's
        assertThat(merged.getK8s().getService()).isEqualTo("seeds");
        assertThat(merged.getDnsSrv()).isEqualTo("_aspace._tcp.x");
        assertThat(merged.firstWinsOrDefault()).isTrue();
        assertThat(bound.settingsFor("other").getTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(bound.groupSettings()).containsOnlyKeys("fleet");
        assertThat(bound.isEmpty()).isFalse();
        assertThat(BootstrapTree.bind(Map.of("bind", "x", "groups", List.of(Map.of("name", "g")))).isEmpty()).isTrue();
        assertThat(BootstrapTree.parseDuration("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(BootstrapTree.parseDuration("250")).isEqualTo(Duration.ofMillis(250));
    }
}
