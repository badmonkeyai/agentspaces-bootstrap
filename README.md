# AgentSpaces Bootstrap

A new AgentSpaces peer has to find the group it wants to join. This project lets it find that group through the directory your network already runs: DNS names, SRV records, DNS-SD, a Kubernetes service, or a document at an HTTPS address. Spring Boot and Micronaut applications turn it on with a few properties, and you can add your own way of finding a group as a bean.

Peer, group, seed, locator, beacon, and chain each have a specific meaning here, and several of them sound alike. The next section introduces them one at a time, with one running example. If you know the terms already, skip to [Coordinates](#coordinates), or use the [terms at a glance](#the-terms-at-a-glance) table as a reference.

## The ideas, one at a time

The running example: Acme Corp runs a fleet of agents called `research-fleet`. Its network domain is `corp.example`. Each idea below appears in that fleet.

### Peer

A **peer** is one running AgentSpaces process: a Spring Boot service, a Micronaut service, a Python script. Each peer has its own identity key. A peer can listen for connections on an address, or it can only dial out, as a laptop behind a home router does.

*Example.* Acme starts a peer called `seed-a` on a server. It listens on `seed-a.corp.example:7500`.

### Group

A **group** is a named set of peers that share state and gossip about who belongs. Every group has a **group id**, which looks like `aspace://z7f3a...`. The id comes from one of two places, and that decides how a peer enters the group:

- **A founding text.** You give every peer the same text, such as `founding: research-fleet-v1`, and each peer derives the same group id from it by itself. There is no signed document and no secret to distribute. This fits one organization, where the operator controls every peer's configuration.
- **A signed founding.** A founder creates a signed document called the **founding**, and the group id is a hash of it. Members hold the document (a peer can be handed it as `founding-document`). A peer that has only the id, as `join: aspace://z7f3a...`, asks a member for the founding, checks that it hashes to exactly the id it asked for, and only then introduces itself. This fits an id that travels further than the configuration: a partner's setup, a printed credential, a registry.

A group started from a text has no founding to serve, so a peer cannot join it by id. Peers join a text group by being given the text.

*Example.* Acme's `research-fleet` uses a text. `seed-a` starts it with `founding: research-fleet-v1`, and every other Acme peer receives the same text. Partner Inc's `partner-fleet` has a signed founding, so Acme's peers can join it by id.

### Bootstrap: the first contact

Once a peer has met one member of a group, the fabric does the rest. The member's signed self-advertisement spreads over the `peers` stream, membership is learned by gossip, and if the peer later loses contact, the lost-member retry re-dials the members it last knew. A peer's **view** of a group is the list of members it currently knows.

A peer that has just started has an empty view and knows nobody. It has to dial something, and nothing inside the group can tell it what. Finding that first address is **bootstrap**. In this project the word means that one step. Admission and gossip start afterward, and the core handles them.

*Example.* Acme starts a new peer, `summarizer`, and configures it for `research-fleet`. `summarizer` knows the group's name and no addresses. Bootstrap is how it learns that `seed-a.corp.example:7500` is a place to start.

### Seed

A **seed** is an address that a newcomer dials first, written `host:port` or `scheme://host:port` (for example `tls://seed-a.corp.example:7500`). Any member that listens for connections can act as a seed. A peer that has joined no longer needs its seed.

*Example.* Acme configures `summarizer` with `seeds: [ "seed-a.corp.example:7500" ]`. `summarizer` dials `seed-a`, introduces itself, and learns the other members by gossip. It keeps working if `seed-a` goes away later.

### The core

**The core** means the AgentSpaces libraries this project builds on: the peering library, and the Spring Boot starter and Micronaut factory that host it. On its own, the core answers the bootstrap question in three ways:

1. A static list of seeds for each group.
2. **Name expansion.** When a seed is a name that DNS resolves to several addresses, the core dials every address of the name.
3. An opt-in UDP multicast datagram that peers on one LAN segment use to announce themselves.

*Example.* In DNS, `seeds.corp.example` has two A records and one AAAA record, so it has three addresses. Acme configures `seeds: [ "seeds.corp.example:7500" ]`, and the core dials all three.

A static list works until the addresses move. Kubernetes reschedules a seed pod and its address changes. A laptop joins the corporate Wi-Fi with no list at all. A partner organization holds only a group id and a URL. Enterprise networks already publish answers to "where is this service?" for everything else they run, and this project reads those answers.

### Locator

A **locator** is one way of finding where to dial. The peer tells it which group it wants, and the locator answers with a **hint**: addresses to dial, in priority order. A hint can also carry the group id the locator believes the group has, the signed founding document, a lifetime (how long the answer stays valid), and the **evidence** that produced it, such as `SRV research-fleet._agentspaces._tcp.corp.example` or `GET https://agentspaces.corp.example/...`. A locator that has nothing returns an empty hint.

A locator only suggests addresses, and it never admits anyone. The peer dials each address as it would dial any stranger, so the usual signature checks and admission policy apply. A wrong DNS answer can delay a join, and it cannot place a stranger in the group.

The seed list and multicast are locators too, named `seeds` and `multicast`, and they come with the core. This project adds the others:

| Locator name | What it asks | Acme example |
|---|---|---|
| `seeds` (core) | the configured list, with name expansion | `seeds.corp.example:7500` |
| `dns-sd` | DNS-SD (RFC 6763) under the network domain: PTR records at `_agentspaces._tcp.<domain>` name the instances, each instance's SRV records give hosts and ports, and its TXT record gives `group`, `id`, `scheme`, and `url` | the instance `research-fleet._agentspaces._tcp.corp.example`, with SRV records for `seed-a` and `seed-b` |
| `dns-txt` | one TXT record at `agentspaces.<domain>` that holds `url=`, the address of a beacon document | `agentspaces.corp.example` TXT `"url=https://agentspaces.corp.example/.well-known/agentspaces/beacon.json"` |
| `dns-srv` | SRV records (RFC 2782) at an owner name you choose | `_aspace._tcp.agentspaces-seeds.fleet.svc.cluster.local` |
| `k8s` | the SRV records Kubernetes publishes for a named port of a headless service, with no Kubernetes client | service `agentspaces-seeds`, namespace `fleet`, port `aspace` |
| `url` | fetches a beacon document from an HTTPS address | `https://agentspaces.corp.example/.well-known/agentspaces/beacon.json` |
| `document` | reads a beacon document from a file or classpath resource, for a network with no outside access | `/etc/agentspaces/beacon.json` |
| `multicast` (core) | the addresses this peer heard in LAN multicast datagrams | a lab where every peer shares one network segment |

The **network domain** is the DNS domain the host belongs to. `dns-sd` and `dns-txt` look under it. With `domain: auto`, the peer finds it the way the host does, from the `AGENTSPACES_DOMAIN` environment variable, the `search` and `domain` lines of `/etc/resolv.conf`, or on Windows the search list that `ipconfig /all` prints. A laptop on Acme's Wi-Fi receives the search domain `corp.example` from DHCP, so `dns-sd` finds `research-fleet` without any seeds configured.

The locators that read DNS do their lookups through a small interface, `DnsResolver`. The default implementation uses the JDK's JNDI provider and adds no dependency. The [`agentspaces-bootstrap-dns`](https://github.com/badmonkeyai/agentspaces-bootstrap-dns) project supplies one with DNS over HTTPS and DNSSEC validation.

### Beacon

The word **beacon** names three different things. This section keeps them apart.

1. **A beacon** is a peer that newcomers can find and dial first. It is an ordinary group member with a stable address, usually playing the `RENDEZVOUS` role (a peer that caches many members' advertisements and answers discovery queries). Nothing in the group protocol marks a peer as a beacon: the word describes how an operator uses a member. Operators run a beacon so that newcomers have somewhere to go. The [`agentspaces-beacon`](https://github.com/badmonkeyai/agentspaces-beacon) project is a ready-made one. It runs as a standalone peer, serves the document below, and prints the DNS records an operator registers.
2. **The beacon document** is a signed JSON file that a beacon serves at `/.well-known/agentspaces/beacon.json`. For each group it lists the name, the id, the seeds, and the wire hints, and it adds the signed founding when the beacon's group has one. It never carries a secret. The beacon builds the seed list from its live view of the group: itself first, then members with the rendezvous role, then the other members that listen for connections. For a group with a signed founding, a newcomer that holds the id and the document's URL can fetch the document, check the founding against the id, and learn the seeds. The `url` and `document` locators read it, and `dns-sd` and `dns-txt` follow a `url=` record to it.
3. **The multicast beacon** is the UDP datagram, off by default, that members send so that peers on the same LAN segment find each other without configuration. It is a core feature, and this project lists it in the chain as the `multicast` locator.

*Example.* Acme runs a beacon peer at `beacon.corp.example`. It serves this document at `https://agentspaces.corp.example/.well-known/agentspaces/beacon.json`:

```json
{
  "v": 1,
  "issuer": "zDnaCm6XCa...",
  "issuerKey": "<base64 Ed25519 public key>",
  "issued": "2026-10-21T19:35:30Z",
  "ttl": 300,
  "groups": [
    {
      "name": "research-fleet",
      "id": "aspace://zDXTnY2y...",
      "seeds": [
        { "transport": "tls", "address": "beacon.corp.example:7500", "priority": 0 },
        { "transport": "tls", "address": "seed-a.corp.example:7500", "priority": 1 },
        { "transport": "tls", "address": "seed-b.corp.example:7500", "priority": 2 }
      ],
      "wire": { "batch": 1, "hashes": 1, "tree": 1 }
    }
  ],
  "signature": "<base64 Ed25519 signature over every other field>"
}
```

`research-fleet` uses a text, so this group entry has no founding. Partner Inc runs its own beacon for `partner-fleet`, a group with a signed founding, so its document also carries a `founding` field: the base64 of the signed founding advertisement. Acme's `summarizer` holds `join: aspace://q9c2b...`, that document's URL, and one seed: the address of Partner Inc's beacon. The Spring Boot and Micronaut hosts need that seed to start a `join:` group (see the note under the chain example below). The `url` locator then fetches the document, `summarizer` checks the founding against the id, and it dials the seeds the document lists. A Python or TypeScript peer needs only the id and the URL.

### Chain

A **chain** is the ordered list of locators that one group uses. Every group has its own chain, because each group can need a different way of being found: `research-fleet` through DNS inside Acme, a partner's group through a URL. The host builds each group's chain from the fleet-wide `agentspaces.bootstrap.*` settings, then applies the overrides in that group's own `bootstrap` block. The core's `BootstrapChain` class implements the chain, and `ChainComposer` in this project builds it.

*Example.* `summarizer` belongs to two groups, and each has its own chain:

```yaml
agentspaces:
  groups:
    - name: research-fleet
      founding: research-fleet-v1
      seeds: [ "seeds.corp.example:7500" ]
      bootstrap:
        order: [ seeds, dns-sd, url ]          # research-fleet's chain
        url: https://agentspaces.corp.example/.well-known/agentspaces/beacon.json
    - name: partner-fleet
      join: aspace://q9c2b...
      seeds: [ "beacon.partner.example:7500" ]
      bootstrap:
        order: [ seeds, url ]                  # partner-fleet's chain
        url: https://partner.example/.well-known/agentspaces/beacon.json
        beacon-peer: z6Mk...partnerBeacon      # the PeerId that must have signed the document
```

A group that joins by id needs at least one configured seed besides its chain. When the Spring Boot and Micronaut hosts check that a `join:` group has a way in, they count the configured seeds, `BeaconLocator` beans, and the multicast locator, and they ignore the locators that `agentspaces.bootstrap.*` builds. A `join:` group with only a `bootstrap` block therefore fails at startup with "joins by GroupId URI but configures no seeds and no locator", so `partner-fleet` lists its beacon's address under `seeds`. A group started from a `founding:` text needs no seed for the check, and neither does a Python or TypeScript peer.

**How a chain answers.** When a peer asks a chain for a group, the chain works through these steps:

1. It picks the locators that apply to that group. A locator you define as a bean can limit itself to one group.
2. By default it asks all of them at once, each with its own deadline (`timeout`, 3 seconds). A locator that fails or times out contributes nothing, and the others carry on.
3. It merges the answers in chain order, so the first locator's addresses rank highest, and it removes duplicates.
4. It keeps the first founding document it finds. It accepts a group id claim only from the locators you named in `accept-group-id-from`.
5. It expands names into addresses last.
6. It sets the answer's lifetime to the shortest lifetime among the locators that contributed.

With `first-wins: true`, the chain asks one locator at a time and stops at the first one that returns addresses.

**Seeds first.** In the default `order`, `seeds` is the first entry. The chain therefore asks the configured list first and ranks its addresses first in the merged answer. With `first-wins: true`, a non-empty seed list also means that no later locator is asked. After the seeds, the chain runs the locators your properties name, in `order`, and then any `BeaconLocator` beans your application defines. `bean-locators-first: true` puts the beans before the property locators. When multicast is enabled, the host adds the `multicast` locator in the same place as the application's beans.

**When the chain runs.** A peer asks its group's chain when it joins, and again whenever its view of the group is empty. The re-asks come no sooner than `refresh-min` (30 seconds) and no later than `refresh-max` (10 minutes). If the first answer holds no addresses and no founding, the peer logs one line and waits for the next ask. With `require: true`, the application instead fails at startup and names the group. With `trace: true`, the peer logs every locate at INFO, with each locator's evidence.

*Example.* `summarizer` starts on a laptop inside Acme. Its chain for `research-fleet` is `[seeds, dns-sd, url]`:

```
  summarizer wants research-fleet
              |
              v
  research-fleet's chain: [seeds, dns-sd, url], all asked at once
  +--------------------+-----------------------+---------------------+
  | seeds              | dns-sd                | url                 |
  | seeds.corp.example | SRV: seed-a, seed-b   | document lists      |
  | :7500              | TXT: url=...          | beacon, seed-a,     |
  |                    |                       | and seed-b          |
  +--------------------+-----------------------+---------------------+
              |
              v
  merge in chain order, remove duplicates, expand names to addresses
              |
              v
  dial the addresses in order and introduce itself;
  from here gossip takes over
```

### What the peer checks

A chain suggests where to dial. The peer decides what to believe:

- **Addresses are only dialed.** Every connection goes through the same handshake, signature verification, and admission policy as a connection from any stranger.
- **A founding is verified when there is one.** When a peer joins by id, it checks the founding against the id it was configured with. A group started from a text has no founding, so the peer relies on the handshake, the signature checks, and the group's admission policy, as it does for every connection.
- **A group id claim needs a named source.** A locator can say what it believes the group id is. The chain accepts that claim only from the locators listed in `accept-group-id-from`, which default to `url` and `document`, the sources that arrive over HTTPS or from a file you control. Plain DNS answers carry no signature. Naming `dns-sd`, `dns-txt`, `dns-srv`, `k8s`, or `multicast` there stops the application at startup unless you set `insecure-group-id: true` or give the host a `DnsResolver` whose answers are authenticated, such as the DNSSEC-validating one in `agentspaces-bootstrap-dns`.
- **A document can be pinned.** `beacon-peer` names the PeerId that must have signed any document. The `url` locator also fetches over HTTPS by default, caps the size, follows at most two redirects on the same host, and refuses private addresses unless `url-allow-private` allows them.

*Example.* An attacker poisons the DNS answer for a seed name of Partner Inc's `partner-fleet`, and `summarizer` dials the attacker's host while joining that group by id. The host cannot show a founding that hashes to `aspace://q9c2b...`, so `summarizer` does not join it and asks the chain again later.

### Hosts and modules

A **host** is the framework that creates your peer: the Spring Boot starter (`agentspaces-spring-boot-starter`) or the Micronaut factory (`agentspaces-micronaut`). The host builds one chain per group from the `agentspaces.bootstrap.*` properties and from any `BeaconLocator` beans you define. This project provides the library of locators and the beacon document, the chain composition both hosts share, and one adapter for each host. [Coordinates](#coordinates) lists the four artifacts. The sections after it show the properties, the bean interfaces, and recipes for Kubernetes and Docker Compose.

## The terms at a glance

| Term | What it is | In the Acme example |
|---|---|---|
| Peer | one running AgentSpaces process | `seed-a`, `summarizer` |
| Group | a named set of peers that share state | `research-fleet` |
| Group id | the `aspace://` identifier of a group, derived from a founding text or from a signed founding | `aspace://zDXTnY2y...` |
| Founding text | a string every peer of a group receives; each peer derives the group id from it | `founding: research-fleet-v1` |
| Signed founding | a document a founder signs, which the group id is a hash of; members serve it to peers that join by id | `partner-fleet`'s, which Acme's peers verify before joining |
| View | the members a peer currently knows | empty when `summarizer` starts |
| Bootstrap | finding where to dial first | how `summarizer` learns about `seed-a` |
| Seed | an address a newcomer dials first | `seed-a.corp.example:7500` |
| Core | the AgentSpaces libraries this project builds on; they provide the seed list, name expansion, and multicast | the `seeds` list in `summarizer`'s configuration |
| Name expansion | dialing every address that a seed name resolves to | `seeds.corp.example` resolves to three addresses |
| Locator | one way of finding where to dial | `seeds`, `dns-sd`, `url` |
| Hint | a locator's answer: addresses, and optionally an id, a founding, a lifetime, and evidence | the SRV answer that lists `seed-a` and `seed-b` |
| Network domain | the DNS domain the host belongs to, usually the DHCP search domain | `corp.example` |
| Beacon | a peer that newcomers can find and dial first | `beacon.corp.example` |
| Beacon document | the signed JSON a beacon serves at `/.well-known/agentspaces/beacon.json` | the document in the Beacon section |
| Multicast beacon | the opt-in LAN datagram that members send | a lab on one network segment |
| Rendezvous | a role for a peer that caches many members' advertisements and answers discovery queries | `beacon.corp.example` |
| Chain | the ordered locators one group uses | `[seeds, dns-sd, url]` |
| Group's chain | each group has its own chain, built from fleet-wide settings plus the group's overrides | `research-fleet` uses `[seeds, dns-sd, url]` and `partner-fleet` uses `[seeds, url]` |
| Seeds first | `seeds` leads the default order, so the configured list is asked first and ranks first | `seeds.corp.example` heads the merged answer |
| First-wins | a chain setting that asks one locator at a time and stops at the first that returns addresses | `first-wins: true` |
| Host | the framework that creates the peer and builds the chains | Spring Boot or Micronaut |
| `DnsResolver` | the interface the DNS locators use for lookups | JNDI by default, DNS over HTTPS and DNSSEC in `agentspaces-bootstrap-dns` |

## Coordinates

| Artifact | What | Depends on |
|---|---|---|
| `ai.badmonkey.agentspaces:agentspaces-bootstrap:0.5.0` | the library: `DnsResolver` and the JNDI resolver, `SrvLocator`, `DnsSdLocator`, `TxtLocator`, `UrlLocator`, `K8sLocator`, `StaticDocumentLocator`, `DomainFinder`, `BeaconDocument`, `BootstrapSettings`, `LocatorAssembly` | `agentspaces-api`, `-common`, `-identity`, Jackson |
| `ai.badmonkey.agentspaces:agentspaces-bootstrap-chain:0.5.0` | `ChainComposer`, the composition both hosts share, and `BootstrapTree`, the nested-map binding of the keys | the library, `agentspaces-peering` (`BootstrapChain`), the core autoconfigure jar (the `BootstrapChainFactory` seam; it carries no Spring class) |
| `ai.badmonkey.agentspaces:agentspaces-bootstrap-spring-boot-autoconfigure:0.5.0` | `BootstrapProperties`, `BootstrapAutoConfiguration` | `chain`, Spring Boot 4.1 |
| `ai.badmonkey.agentspaces:agentspaces-bootstrap-micronaut:0.5.0` | `BootstrapFactory` | `chain`, Micronaut 5.2 |

An application adds the host module beside the core starter (`agentspaces-spring-boot-starter`)
or the core Micronaut factory (`agentspaces-micronaut`); the library and the
chain come transitively.

```xml
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-bootstrap-spring-boot-autoconfigure</artifactId>
  <version>0.5.0</version>
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

## Spring Boot: the bean interfaces

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
      AGENTSPACES_GROUPS_0_FOUNDING: fleet-v1        # the seeds' text: same text, same group
      AGENTSPACES_GROUPS_0_SEEDS_0: seeds:7500       # one name, three addresses
```

The workers use the same founding text as the seeds. A group started from a
text has no signed founding to serve, so a worker that set
`AGENTSPACES_GROUPS_0_JOIN` to the group's id would wait ten seconds and then
fail with `no seed served a verified founding advertisement`.

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
0.5.0 (the core's Micronaut host) must be in the local repository for the
Micronaut module's end-to-end test.

## Releasing

The `central-publish` profile attaches the sources and javadoc jars, signs
every artifact with GPG (the passphrase from `MAVEN_GPG_PASSPHRASE`, or a GPG
agent), and uploads through the Sonatype Central Portal using the `central`
server id in `settings.xml`. The dry run builds the jars without signing or
uploading anything:

```sh
mvn -Pcentral-publish -DskipTests -Dgpg.skip=true clean package
```

The release signs the artifacts and uploads them:

```sh
mvn -Pcentral-publish clean deploy
```

`autoPublish` is off, so the upload waits on the Portal as a validated bundle
until you publish it there. The bundle holds the four modules and their parent
POM, `agentspaces-bootstrap-parent`. Publish this project first:
`agentspaces-bootstrap-dns` and `agentspaces-beacon` depend on it.
