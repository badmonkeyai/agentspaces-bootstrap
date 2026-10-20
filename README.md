# AgentSpaces Bootstrap

A peer joins a group by introducing itself to one member that is already in.
Everything after that first contact is the fabric's own business: the signed
self-advertisement spreads on the `peers` stream, membership is learned by
gossip, the lost-member retry re-dials what it last knew, and seeds are a way
in rather than a dependency. The first contact is the part the fabric cannot
do for itself, and the core answers it with a static list of `host:port`
strings per group and an opt-in UDP multicast datagram on one LAN segment. An
enterprise network has more answers than that, already in place for everything
else it runs: DNS names with several addresses, SRV records with priorities,
DNS-SD under a domain, a well-known name under the search domain DHCP handed
out, a document at a well-known HTTPS URI, the A and SRV records Kubernetes
publishes for every pod of a headless service. This project makes those
answers first-class: locators for each of them over a small `DnsResolver`
SPI, the beacon document they can point at, and the Spring Boot and Micronaut
wiring that binds `agentspaces.bootstrap.*` and composes each group's
bootstrap chain through the seams the core hosts expose. A standalone project
on the published AgentSpaces 0.4.0 libraries, JDK-only beyond them.

## Coordinates

| Artifact | What | Depends on |
|---|---|---|
| `ai.badmonkey.agentspaces:agentspaces-bootstrap:0.4.0` | the library: `DnsResolver` and the JNDI resolver, `SrvLocator`, `DnsSdLocator`, `TxtLocator`, `UrlLocator`, `K8sLocator`, `StaticDocumentLocator`, `DomainFinder`, `BeaconDocument`, `BootstrapSettings`, `LocatorAssembly` | `agentspaces-api`, `-common`, `-identity`, Jackson |
| `ai.badmonkey.agentspaces:agentspaces-bootstrap-chain:0.4.0` | `ChainComposer`, the composition both hosts share, and `BootstrapTree`, the nested-map binding of the keys | the library, `agentspaces-peering` (`BootstrapChain`), the core autoconfigure jar (the `BootstrapChainFactory` seam; it carries no Spring class) |
| `ai.badmonkey.agentspaces:agentspaces-bootstrap-spring-boot-autoconfigure:0.4.0` | `BootstrapProperties`, `BootstrapAutoConfiguration` | `chain`, Spring Boot 4.1 |
| `ai.badmonkey.agentspaces:agentspaces-bootstrap-micronaut:0.4.0` | `BootstrapFactory` | `chain`, Micronaut 5.2 |

An application adds the host module beside the core starter (`agentspaces-spring-boot-starter`)
or the core Micronaut factory (`agentspaces-micronaut`); the library and the
chain come transitively.

```xml
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-bootstrap-spring-boot-autoconfigure</artifactId>
  <version>0.4.0</version>
</dependency>
```

## Spring Boot: the default chain from properties

```yaml
agentspaces:
  keystore: ./peer-keys
  bind: 0.0.0.0:7500
  advertise: ${POD_IP:localhost}:7500            # what others dial (core starter)
  roles: [ rendezvous ]                          # on the beacon only
  bootstrap:                                     # fleet-wide defaults
    domain: auto                                 # or [ corp.example, fleet.svc.cluster.local ]
    order: [ seeds, dns-sd, dns-txt, url, multicast ]
    timeout: PT3S
    refresh-min: PT30S
    refresh-max: PT10M
    accept-group-id-from: [ url ]
    beacon-peer: ""                              # PeerId to pin the document signer, optional
  groups:
    - name: research-fleet
      join: aspace://z7f3a...                    # or founding: research-fleet-v1
      seeds: [ "seeds.corp.example:7500" ]       # one name, three addresses: all three dialed
      bootstrap:                                 # per-group overrides
        dns-srv: _aspace._tcp.agentspaces-seeds.fleet.svc.cluster.local
        url: https://agentspaces.corp.example/.well-known/agentspaces/beacon.json
  multicast: { enabled: false }                  # unchanged
```

With no `bootstrap` block anywhere, the chain is `seeds` then name expansion
and nothing else: the core's behaviour. With one, each group's chain is the
seeds first (when `order` names them), then the locators the properties ask
for in `order`, then the application's `BeaconLocator` beans (or the beans
first under `bean-locators-first`), with `timeout`, `first-wins`,
`accept-group-id-from`, and `expand-names` applied. A group's block overrides
the keys the fleet-wide block set. A misconfigured block (an unknown name in
`order`, `accept-group-id-from` naming a plain-DNS locator without
`insecure-group-id: true`) fails the context at start, naming the property.

## Spring Boot: the bean seams

Every step of "how this peer finds its group" is a bean the application may
define; the defaults are `@ConditionalOnMissingBean`. The core starter
collects the `BeaconLocator`, `Transport`, `PeerNodeCustomizer`, and
`PeerNodeConfigurer` beans; this module adds `DnsResolver` and
`BootstrapChainFactory`.

```java
@Configuration
class FleetBootstrapConfig {

    /** A locator for a registry the starter does not know. Applies to every group. */
    @Bean @Order(10)
    BeaconLocator cloudMapLocator(DnsResolver dns) {
        return new SrvLocator("_aspace._tcp.agentspaces.corp.internal", "tls", dns);
    }

    /** A locator for one group only: override appliesTo. */
    @Bean
    BeaconLocator partnerDocument() {
        UrlLocator partner = new UrlLocator(
                List.of(URI.create("https://partner.example/.well-known/agentspaces/beacon.json")),
                UrlLocator.Options.defaults(), null);
        return new BeaconLocator() {
            public String name() { return "partner"; }
            public BeaconHint locate(BeaconRequest r) throws IOException { return partner.locate(r); }
            public boolean appliesTo(String group) { return "partner-fleet".equals(group); }
        };
    }

    /** Replace the resolver (DNS over HTTPS, DNSSEC): the agentspaces-bootstrap-dns project. */
    @Bean
    DnsResolver dnsResolver() { return new DnsJavaResolver("https://dns.corp.example/dns-query"); }

    /** Touch the node builder without replacing the node (core starter seam). */
    @Bean
    PeerNodeCustomizer tenMinuteAds() { return b -> b.peerAdTtl(Duration.ofMinutes(10)); }

    /** Change the composition policy for every group: the locators arrive as [seeds, beans...]. */
    @Bean
    BootstrapChainFactory firstWins() {
        return (group, locators) -> BootstrapChain.builder().firstWins().addAll(locators).build();
    }
}
```

Rule of precedence: a `BootstrapChainFactory` bean wins over everything else;
otherwise the chain is the property order, with bean locators appended (or
prepended under `bean-locators-first`), then name expansion.

Beans in the module, by name and type:

| Bean | Type | Condition |
|---|---|---|
| `bootstrapDnsResolver` | `ai.badmonkey.agentspaces.bootstrap.DnsResolver` (`JndiDnsResolver`) | no `DnsResolver` bean |
| `bootstrapChainFactory` | `ai.badmonkey.agentspaces.spring.BootstrapChainFactory` | no `BootstrapChainFactory` bean |
| `agentspaces-ai.badmonkey...BootstrapProperties` | `BootstrapProperties` (`@ConfigurationProperties("agentspaces")`) | always |

`BootstrapAutoConfiguration` runs before the core `AgentSpacesAutoConfiguration`
and is registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`;
`META-INF/additional-spring-configuration-metadata.json` describes every key
for the IDE.

## Micronaut

The same keys bind through `BootstrapFactory` into a `BootstrapTree` (the
Micronaut environment's `agentspaces` subtree converted the way the core's
`FleetConfiguration` binds `AgentSpacesProperties`: kebab-case keys to the
POJO, durations in ISO-8601, milliseconds, or the `5m` short form). The seams
are `@Singleton` beans with `@Requires(missingBeans = ...)`: define a
`DnsResolver` or a `BootstrapChainFactory` bean and the default steps aside.
The core's `AgentSpacesFactory` collects `BeaconLocator`, `Transport`,
`PeerNodeCustomizer`, and `PeerNodeConfigurer` beans as `List<T>` injection
points (in `@Order` order) and takes the `BootstrapChainFactory` bean when
one exists. The factory is off under `agentspaces.enabled: false`, like the
core's.

```java
@Factory
class FleetBootstrapConfig {
    @Singleton
    BeaconLocator cloudMapLocator(DnsResolver dns) {
        return new SrvLocator("_aspace._tcp.agentspaces.corp.internal", "tls", dns);
    }
}
```

## Kubernetes recipe (abridged)

```yaml
apiVersion: v1
kind: Service
metadata: { name: agentspaces-seeds, namespace: fleet }
spec:
  clusterIP: None                       # headless: one A record per pod, SRV per named port
  selector: { app: agentspaces-seed }
  ports: [ { name: aspace, port: 7500 } ]
---
# seeds: a StatefulSet of 3 with
#   agentspaces.bind: 0.0.0.0:7500
#   agentspaces.advertise: ${POD_IP}:7500            (downward API: status.podIP)
#   agentspaces.roles: [rendezvous] on ordinal 0
# workers: a Deployment with
#   agentspaces.groups[0].bootstrap.k8s: { service: agentspaces-seeds, namespace: fleet, port-name: aspace }
#   agentspaces.advertise: ${POD_IP}:7500 (or no bind: dial-only workers behind a RELAY seed)
```

`dig SRV _aspace._tcp.agentspaces-seeds.fleet.svc.cluster.local` shows the
three pods; the `k8s` locator asks exactly that. Equivalent, with no
convenience key: `bootstrap.dns-srv: _aspace._tcp.agentspaces-seeds.fleet.svc.cluster.local`.

## Compose recipe (abridged)

Compose's embedded DNS answers a service name with one A record per replica,
so a seed service scaled to three answers with three addresses and name
expansion (`expand-names`, on by default) dials all three:

```yaml
services:
  seeds:
    image: my-fleet:latest
    deploy: { replicas: 3 }
    environment:
      AGENTSPACES_BIND: 0.0.0.0:7500
      AGENTSPACES_GROUPS_0_NAME: fleet
      AGENTSPACES_GROUPS_0_FOUNDING: fleet-v1
  workers:
    image: my-fleet:latest
    environment:
      AGENTSPACES_GROUPS_0_NAME: fleet
      AGENTSPACES_GROUPS_0_JOIN: aspace://z7f3a...
      AGENTSPACES_GROUPS_0_SEEDS_0: seeds:7500       # one name, three addresses
```

## Properties

The keys this project binds, fleet-wide under `agentspaces.bootstrap.*` and
per group under `agentspaces.groups[].bootstrap.*` (a group's set keys win).

| Key | Default | Meaning |
|---|---|---|
| `domain` | `auto` | a list of domains, or `auto` for the host's search list, or empty for none |
| `order` | `[seeds, dns-sd, dns-txt, dns-srv, k8s, url, document, multicast]` | chain order; a locator absent from the list is not asked (`dns-srv`, `k8s`, `url`, `document` only appear when their key is set) |
| `first-wins` | `false` | stop at the first locator with endpoints |
| `timeout` | `PT3S` | per locator |
| `refresh-min` / `refresh-max` | `PT30S` / `PT10M` | re-ask pacing on an empty view (the chain's `LocatePolicy`; the node honours it) |
| `accept-group-id-from` | `[url, document]` | locators whose GroupId claim is honoured when none is configured |
| `insecure-group-id` | `false` | allow an unauthenticated locator (`dns-*`, `k8s`, `multicast`) in the list above |
| `beacon-peer` | empty | PeerId that must have signed any document |
| `url` | empty | beacon document URLs, comma or space separated |
| `url-allow-plain` / `url-allow-hosts` / `url-allow-private` | `false` / any / `false` | URL locator guards |
| `expand-names` | `true` | one endpoint per address of a seed name |
| `bean-locators-first` | `false` | place bean locators before property locators |
| `require` | `false` | fail startup, naming the group, when the first locate answers no endpoint and no founding |
| `trace` | `false` | log every locate at INFO with each locator's evidence |
| `dns-srv` | empty | the SRV owner name (`_aspace._tcp.seeds.example`) |
| `dns-srv-scheme` | the node's default | the scheme SRV and Kubernetes endpoints take |
| `dns-sd-any-group` | `false` | accept any DNS-SD instance, not only the group's own name |
| `document` | empty | a beacon document as is: a file path or a `classpath:` resource |
| `k8s.service` / `k8s.namespace` / `k8s.port-name` / `k8s.cluster-domain` | empty / the pod's / empty / `cluster.local` | the headless service whose pods are the seeds |

Unchanged and bound by the core starter: `agentspaces.groups[].seeds`,
`agentspaces.groups[].join`, `join-timeout-millis`, `agentspaces.advertise`,
`agentspaces.roles`, `agentspaces.multicast.*`.

## Building

```
mvn clean install
```

Java 21. The tests start real peers on loopback ports; `agentspaces-micronaut`
0.4.0 (the core's Micronaut host) must be in the local repository for the
Micronaut module's end-to-end test.
