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
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Objects;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/**
 * The JDK's DNS: addresses through {@link InetAddress}, SRV, PTR, and TXT
 * through the JNDI DNS provider, which uses the platform's servers when no
 * provider URL is given. No dependency, no DNSSEC, no search list.
 */
public final class JndiDnsResolver implements DnsResolver {

    private static final String FACTORY = "com.sun.jndi.dns.DnsContextFactory";

    private final String providerUrl;
    private final int timeoutMillis;

    /** The platform's servers, a 3 s timeout. */
    public JndiDnsResolver() {
        this(null, 3_000);
    }

    /**
     * A resolver.
     *
     * @param providerUrl   {@code dns://server[:port]}, or null for the platform's servers
     * @param timeoutMillis the query timeout
     */
    public JndiDnsResolver(String providerUrl, int timeoutMillis) {
        this.providerUrl = providerUrl;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public List<InetAddress> addresses(String name) throws IOException {
        try {
            return List.of(InetAddress.getAllByName(absolute(name)));
        } catch (UnknownHostException e) {
            return List.of();
        }
    }

    @Override
    public List<SrvRecord> srv(String name) throws IOException {
        List<SrvRecord> records = new ArrayList<>();
        for (String value : values(name, "SRV")) {
            String[] parts = value.trim().split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            try {
                records.add(new SrvRecord(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), stripDot(parts[3])));
            } catch (NumberFormatException e) {
                // Not an SRV record we understand.
            }
        }
        return records;
    }

    @Override
    public List<String> ptr(String name) throws IOException {
        List<String> targets = new ArrayList<>();
        for (String value : values(name, "PTR")) {
            targets.add(stripDot(value.trim()));
        }
        return targets;
    }

    @Override
    public List<List<String>> txt(String name) throws IOException {
        List<List<String>> records = new ArrayList<>();
        for (String value : values(name, "TXT")) {
            records.add(characterStrings(value));
        }
        return records;
    }

    /**
     * Splits a TXT RDATA as JNDI renders it ({@code "v=1" "url=https://..."})
     * into its character-strings; an unquoted value is one string.
     *
     * @param rdata the rendered record
     * @return the strings
     */
    static List<String> characterStrings(String rdata) {
        List<String> out = new ArrayList<>();
        String s = rdata.trim();
        if (!s.startsWith("\"")) {
            out.add(s);
            return out;
        }
        StringBuilder current = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (current == null) {
                if (c == '"') {
                    current = new StringBuilder();
                }
            } else if (c == '\\' && i + 1 < s.length()) {
                current.append(s.charAt(++i));
            } else if (c == '"') {
                out.add(current.toString());
                current = null;
            } else {
                current.append(c);
            }
        }
        if (current != null) {
            out.add(current.toString());
        }
        return out;
    }

    private List<String> values(String name, String type) throws IOException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", FACTORY);
        env.put("com.sun.jndi.dns.timeout.initial", Integer.toString(timeoutMillis));
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        if (providerUrl != null) {
            env.put("java.naming.provider.url", providerUrl);
        }
        DirContext context = null;
        try {
            context = new InitialDirContext(env);
            Attributes attributes = context.getAttributes(absolute(name), new String[]{type});
            Attribute attribute = attributes.get(type);
            if (attribute == null) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            NamingEnumeration<?> all = attribute.getAll();
            while (all.hasMore()) {
                values.add(Objects.toString(all.next()));
            }
            return values;
        } catch (NameNotFoundException e) {
            return List.of();
        } catch (NamingException e) {
            throw new IOException("DNS " + type + " " + name + ": " + e.getMessage(), e);
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (NamingException ignored) {
                    // Nothing to do.
                }
            }
        }
    }

    private static String absolute(String name) {
        return stripDot(name.trim());
    }

    private static String stripDot(String name) {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }
}
