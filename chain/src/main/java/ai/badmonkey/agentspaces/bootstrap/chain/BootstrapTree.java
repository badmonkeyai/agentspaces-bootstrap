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

import ai.badmonkey.agentspaces.bootstrap.BootstrapSettings;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The bootstrap keys of an {@code agentspaces} configuration tree: the
 * fleet-wide {@code bootstrap} block and each group's {@code name} and
 * {@code bootstrap} block, nothing else. Bound from a nested map (kebab-case
 * keys, lists for repeated sections, durations as ISO-8601, a number of
 * milliseconds, or Spring's short form) by {@link #bind}, for hosts that
 * hold their configuration as a tree, Micronaut among them; every other key
 * of the tree is ignored, since it belongs to the host's own properties.
 */
public class BootstrapTree {

    private BootstrapSettings bootstrap;
    private List<Group> groups = new ArrayList<>();

    /** One group's name and bootstrap block. */
    public static class Group {
        private String name;
        private BootstrapSettings bootstrap;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public BootstrapSettings getBootstrap() {
            return bootstrap;
        }

        public void setBootstrap(BootstrapSettings bootstrap) {
            this.bootstrap = bootstrap;
        }
    }

    public BootstrapSettings getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(BootstrapSettings bootstrap) {
        this.bootstrap = bootstrap;
    }

    public List<Group> getGroups() {
        return groups;
    }

    public void setGroups(List<Group> groups) {
        this.groups = groups == null ? new ArrayList<>() : new ArrayList<>(groups);
    }

    /** The group blocks by name, groups without a block omitted. */
    public Map<String, BootstrapSettings> groupSettings() {
        Map<String, BootstrapSettings> out = new LinkedHashMap<>();
        for (Group group : groups) {
            if (group != null && group.getName() != null && group.getBootstrap() != null) {
                out.put(group.getName(), group.getBootstrap());
            }
        }
        return out;
    }

    /**
     * The merged settings of one group: its block over the fleet's.
     *
     * @param groupName the group
     * @return the merged settings, empty when neither block is set
     */
    public BootstrapSettings settingsFor(String groupName) {
        return BootstrapSettings.merge(bootstrap, groupSettings().get(groupName));
    }

    /** Whether no bootstrap key is set anywhere. */
    public boolean isEmpty() {
        if (bootstrap != null && !bootstrap.isEmpty()) {
            return false;
        }
        for (BootstrapSettings settings : groupSettings().values()) {
            if (!settings.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Binds the {@code agentspaces} subtree of a configuration.
     *
     * @param agentspaces the subtree: kebab-case keys, nested maps and lists
     * @return the tree
     */
    @SuppressWarnings("unchecked")
    public static BootstrapTree bind(Map<String, ?> agentspaces) {
        Objects.requireNonNull(agentspaces, "agentspaces");
        Map<String, Object> cleaned = (Map<String, Object>) splitLists(nestDotted(withoutIndexedKeys(agentspaces)));
        return mapper().convertValue(cleaned, BootstrapTree.class);
    }

    /** The list-valued keys, which a host may hand over as one comma-separated string. */
    static final Set<String> LIST_KEYS = Set.of("domain", "order", "accept-group-id-from", "url-allow-hosts");

    /** Splits a comma- or space-separated string under a list-valued key, as Boot does. */
    private static Object splitLists(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object v = entry.getValue();
                if (LIST_KEYS.contains(key) && v instanceof String text) {
                    List<String> parts = new ArrayList<>();
                    for (String part : text.split("[,\\s]+")) {
                        if (!part.isBlank()) {
                            parts.add(part.trim());
                        }
                    }
                    out.put(key, parts);
                } else {
                    out.put(key, splitLists(v));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(splitLists(item));
            }
            return copy;
        }
        return value;
    }

    /**
     * Nests dotted keys ({@code bootstrap.dns-srv}) into maps, which is how
     * some hosts present a block inside a list element; a nested block and a
     * dotted key for the same path merge, the dotted key winning.
     */
    @SuppressWarnings("unchecked")
    private static Object nestDotted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object nested = nestDotted(entry.getValue());
                String[] parts = key.split("\\.");
                Map<String, Object> node = out;
                for (int i = 0; i < parts.length - 1; i++) {
                    Object existing = node.get(parts[i]);
                    if (!(existing instanceof Map)) {
                        existing = new LinkedHashMap<String, Object>();
                        node.put(parts[i], existing);
                    }
                    node = (Map<String, Object>) existing;
                }
                String leaf = parts[parts.length - 1];
                if (nested instanceof Map<?, ?> more && node.get(leaf) instanceof Map<?, ?> had) {
                    Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) had);
                    merged.putAll((Map<String, Object>) more);
                    node.put(leaf, merged);
                } else {
                    node.put(leaf, nested);
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(nestDotted(item));
            }
            return copy;
        }
        return value;
    }

    private static ObjectMapper mapper() {
        SimpleModule durations = new SimpleModule("agentspaces-bootstrap-durations")
                .addDeserializer(Duration.class, new LenientDuration());
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .registerModule(durations);
    }

    /** Drops the {@code groups[0]}-style keys some hosts keep beside the list form. */
    private static Object withoutIndexedKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.contains("[")) {
                    continue;
                }
                copy.put(key, withoutIndexedKeys(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(withoutIndexedKeys(item));
            }
            return copy;
        }
        return value;
    }

    /**
     * Parses a duration: ISO-8601 ({@code PT3S}), a number of milliseconds,
     * or the short form ({@code 3s}, {@code 250ms}, {@code 5m}, {@code 2h},
     * {@code 1d}).
     *
     * @param text the text
     * @return the duration
     */
    public static Duration parseDuration(String text) {
        String t = Objects.requireNonNull(text, "text").trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("empty duration");
        }
        String upper = t.toUpperCase(Locale.ROOT);
        if (upper.startsWith("P") || upper.startsWith("-P") || upper.startsWith("+P")) {
            return Duration.parse(upper);
        }
        int i = t.length();
        while (i > 0 && Character.isLetter(t.charAt(i - 1))) {
            i--;
        }
        String number = t.substring(0, i).trim();
        String unit = t.substring(i).trim().toLowerCase(Locale.ROOT);
        long n = Long.parseLong(number);
        return switch (unit) {
            case "", "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("unknown duration unit '" + unit + "' in '" + text + "'");
        };
    }

    static final class LenientDuration extends JsonDeserializer<Duration> {
        @Override
        public Duration deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken().isNumeric()) {
                return Duration.ofMillis(parser.getLongValue());
            }
            return parseDuration(parser.getText());
        }
    }
}
