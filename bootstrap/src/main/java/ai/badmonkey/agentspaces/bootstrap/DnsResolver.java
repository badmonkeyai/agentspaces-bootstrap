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

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

/**
 * DNS lookups the locators need: addresses, SRV, PTR, and TXT. The JDK's JNDI
 * provider implements it with no dependency ({@link JndiDnsResolver}); an
 * in-memory one serves tests ({@link InMemoryDnsResolver}); the
 * {@code agentspaces-bootstrap-dns} project adds dnsjava with DNS over HTTPS
 * and DNSSEC validation. Names are absolute; a resolver applies no search
 * list, which is {@link DomainFinder}'s job.
 */
public interface DnsResolver {

    /** One SRV record (RFC 2782). */
    record SrvRecord(int priority, int weight, int port, String target) {
    }

    /**
     * Every address of a name, in the resolver's order; empty for an unknown name.
     *
     * @param name the host name
     * @return the addresses
     * @throws IOException when the resolver could not be asked
     */
    List<InetAddress> addresses(String name) throws IOException;

    /**
     * The SRV records at an owner name; empty when there are none.
     *
     * @param name the owner name ({@code _aspace._tcp.seeds.example})
     * @return the records
     * @throws IOException when the resolver could not be asked
     */
    List<SrvRecord> srv(String name) throws IOException;

    /**
     * The PTR targets at a name; empty when there are none.
     *
     * @param name the owner name ({@code _agentspaces._tcp.corp.example})
     * @return the target names, absolute, without a trailing dot
     * @throws IOException when the resolver could not be asked
     */
    List<String> ptr(String name) throws IOException;

    /**
     * The TXT records at a name, each as its list of character-strings.
     *
     * @param name the owner name
     * @return the records
     * @throws IOException when the resolver could not be asked
     */
    List<List<String>> txt(String name) throws IOException;

    /**
     * Whether answers from this resolver are authenticated (DNSSEC-validated
     * or fetched over an authenticated channel), so that a GroupId they carry
     * may be trusted as an operator would trust an HTTPS document. The JDK
     * resolver answers {@code false}.
     *
     * @return {@code true} when answers are authenticated
     */
    default boolean authenticated() {
        return false;
    }
}
