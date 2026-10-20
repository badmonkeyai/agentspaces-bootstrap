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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The network domains a peer looks under without further configuration: the
 * parameter DHCP delivers portably, the search domain. Candidates in order:
 * the configured list; the {@code AGENTSPACES_DOMAIN} environment variable;
 * the {@code search} and {@code domain} lines of {@code /etc/resolv.conf};
 * on Windows the search list {@code ipconfig /all} prints. Each source that
 * cannot be read is skipped and the trace says so.
 */
public final class DomainFinder implements Supplier<List<String>> {

    private final List<String> configured;
    private final boolean auto;
    private volatile List<String> tried = List.of();

    private DomainFinder(List<String> configured, boolean auto) {
        this.configured = List.copyOf(configured);
        this.auto = auto;
    }

    /** Exactly these domains, in order. */
    public static DomainFinder of(List<String> domains) {
        return new DomainFinder(domains, false);
    }

    /** The host's search list. */
    public static DomainFinder auto() {
        return new DomainFinder(List.of(), true);
    }

    /** No domain at all: the DNS-SD and TXT locators have nowhere to look. */
    public static DomainFinder none() {
        return new DomainFinder(List.of(), false);
    }

    /** The sources tried by the last {@link #get()}, for the trace. */
    public List<String> tried() {
        return tried;
    }

    @Override
    public List<String> get() {
        Set<String> domains = new LinkedHashSet<>();
        List<String> sources = new ArrayList<>();
        for (String domain : configured) {
            add(domains, domain);
        }
        if (!configured.isEmpty()) {
            sources.add("configured");
        }
        if (auto) {
            String env = System.getenv("AGENTSPACES_DOMAIN");
            if (env != null && !env.isBlank()) {
                for (String domain : env.split("[,\\s]+")) {
                    add(domains, domain);
                }
                sources.add("AGENTSPACES_DOMAIN");
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                sources.add(windows(domains) ? "ipconfig" : "ipconfig (unreadable)");
            } else {
                sources.add(resolvConf(domains, Path.of("/etc/resolv.conf")) ? "/etc/resolv.conf"
                        : "/etc/resolv.conf (unreadable)");
            }
        }
        tried = List.copyOf(sources);
        return List.copyOf(domains);
    }

    /** Reads {@code search} and {@code domain} lines; package-private for tests. */
    static boolean resolvConf(Set<String> domains, Path path) {
        try {
            if (!Files.isReadable(path)) {
                return false;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("search ") || trimmed.startsWith("domain ")) {
                    for (String domain : trimmed.substring(7).trim().split("\\s+")) {
                        add(domains, domain);
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean windows(Set<String> domains) {
        try {
            Process process = new ProcessBuilder("ipconfig", "/all").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            boolean inList = false;
            for (String line : output.split("\\R")) {
                String t = line.trim();
                if (t.startsWith("DNS Suffix Search List") || t.startsWith("Connection-specific DNS Suffix")) {
                    int colon = t.indexOf(':');
                    if (colon >= 0) {
                        add(domains, t.substring(colon + 1).trim());
                    }
                    inList = t.startsWith("DNS Suffix Search List");
                } else if (inList && !t.isEmpty() && !t.contains(":")) {
                    add(domains, t);
                } else {
                    inList = false;
                }
            }
            return true;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static void add(Set<String> domains, String domain) {
        String d = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        if (d.endsWith(".")) {
            d = d.substring(0, d.length() - 1);
        }
        if (!d.isEmpty() && !d.equals("local") && !d.equals("localdomain")) {
            domains.add(d);
        }
    }
}
