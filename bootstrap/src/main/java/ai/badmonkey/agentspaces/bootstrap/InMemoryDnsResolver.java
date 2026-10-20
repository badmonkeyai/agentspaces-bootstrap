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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A resolver over maps, for tests and for air-gapped fixtures; names are case-insensitive. */
public final class InMemoryDnsResolver implements DnsResolver {

    private final Map<String, List<InetAddress>> addresses = new ConcurrentHashMap<>();
    private final Map<String, List<SrvRecord>> srv = new ConcurrentHashMap<>();
    private final Map<String, List<String>> ptr = new ConcurrentHashMap<>();
    private final Map<String, List<List<String>>> txt = new ConcurrentHashMap<>();
    private final boolean authenticated;
    private volatile int queries;

    public InMemoryDnsResolver() {
        this(false);
    }

    /** @param authenticated what {@link #authenticated()} answers */
    public InMemoryDnsResolver(boolean authenticated) {
        this.authenticated = authenticated;
    }

    /** Adds A or AAAA records by literal. */
    public InMemoryDnsResolver address(String name, String... literals) {
        List<InetAddress> list = new ArrayList<>();
        for (String literal : literals) {
            try {
                list.add(InetAddress.getByName(literal));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(literal, e);
            }
        }
        addresses.computeIfAbsent(key(name), k -> new ArrayList<>()).addAll(list);
        return this;
    }

    /** Adds an SRV record. */
    public InMemoryDnsResolver srv(String name, int priority, int weight, int port, String target) {
        srv.computeIfAbsent(key(name), k -> new ArrayList<>()).add(new SrvRecord(priority, weight, port, key(target)));
        return this;
    }

    /** Adds a PTR record. */
    public InMemoryDnsResolver ptr(String name, String target) {
        ptr.computeIfAbsent(key(name), k -> new ArrayList<>()).add(key(target));
        return this;
    }

    /** Adds a TXT record of character-strings. */
    public InMemoryDnsResolver txt(String name, String... strings) {
        txt.computeIfAbsent(key(name), k -> new ArrayList<>()).add(List.of(strings));
        return this;
    }

    /** Forgets every record at a name, of every type. */
    public InMemoryDnsResolver remove(String name) {
        String k = key(name);
        addresses.remove(k);
        srv.remove(k);
        ptr.remove(k);
        txt.remove(k);
        return this;
    }

    /** How many lookups were made. */
    public int queries() {
        return queries;
    }

    @Override
    public List<InetAddress> addresses(String name) {
        queries++;
        return List.copyOf(addresses.getOrDefault(key(name), List.of()));
    }

    @Override
    public List<SrvRecord> srv(String name) {
        queries++;
        return List.copyOf(srv.getOrDefault(key(name), List.of()));
    }

    @Override
    public List<String> ptr(String name) {
        queries++;
        return List.copyOf(ptr.getOrDefault(key(name), List.of()));
    }

    @Override
    public List<List<String>> txt(String name) {
        queries++;
        return List.copyOf(txt.getOrDefault(key(name), List.of()));
    }

    @Override
    public boolean authenticated() {
        return authenticated;
    }

    private static String key(String name) {
        String k = name.trim().toLowerCase(Locale.ROOT);
        return k.endsWith(".") ? k.substring(0, k.length() - 1) : k;
    }
}
