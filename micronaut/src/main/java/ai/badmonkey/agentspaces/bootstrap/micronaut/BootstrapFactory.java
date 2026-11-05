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
package ai.badmonkey.agentspaces.bootstrap.micronaut;

import ai.badmonkey.agentspaces.bootstrap.DnsResolver;
import ai.badmonkey.agentspaces.bootstrap.JndiDnsResolver;
import ai.badmonkey.agentspaces.bootstrap.chain.BootstrapTree;
import ai.badmonkey.agentspaces.bootstrap.chain.ChainComposer;
import ai.badmonkey.agentspaces.spring.BootstrapChainFactory;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;

import java.util.Map;

/**
 * The bootstrap seams of the AgentSpaces Micronaut factory filled from
 * {@code agentspaces.bootstrap.*} (SRAD DB-C-02, DB-C-03, DB-C-07, DB-C-09):
 * the same keys the Spring Boot module binds, read from the Micronaut
 * environment's {@code agentspaces} subtree and converted into
 * {@link BootstrapTree} the way the core's {@code FleetConfiguration} binds
 * its own properties (Micronaut binds configuration only into classes it
 * compiled, and the settings POJO lives in the library). A
 * {@link DnsResolver} over the JDK's JNDI provider and a
 * {@link BootstrapChainFactory} composing each group's chain follow, each
 * yielding to an application bean of the same type.
 */
@Factory
@Requires(property = "agentspaces.enabled", notEquals = "false")
public class BootstrapFactory {

    /**
     * The bound bootstrap keys.
     *
     * @param environment the Micronaut environment
     * @return the tree
     */
    @Singleton
    @Requires(missingBeans = BootstrapTree.class)
    public BootstrapTree bootstrapTree(Environment environment) {
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = environment.getProperty("agentspaces", Map.class)
                .map(m -> (Map<String, Object>) m).orElse(Map.of());
        return BootstrapTree.bind((Map<String, Object>) resolved(tree, environment));
    }

    /**
     * The subtree with its placeholders resolved: a map read whole keeps {@code ${AGENTSPACES_BEACON:}} as text, so
     * each string leaf goes through the environment's placeholder resolver (an unset variable with an empty default
     * becomes the empty string the locators treat as unset).
     */
    @SuppressWarnings("unchecked")
    static Object resolved(Object value, Environment environment) {
        if (value instanceof String text) {
            return text.contains("${") ? environment.getPlaceholderResolver().resolvePlaceholders(text).orElse("") : text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), resolved(v, environment)));
            return out;
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            list.forEach(v -> out.add(resolved(v, environment)));
            return out;
        }
        return value;
    }

    /**
     * The resolver the DNS locators ask: the JDK's JNDI provider.
     *
     * @return the resolver
     */
    @Singleton
    @Requires(missingBeans = DnsResolver.class)
    public DnsResolver bootstrapDnsResolver() {
        return new JndiDnsResolver();
    }

    /**
     * The chain per group: the core's default with no bootstrap key anywhere,
     * the composition of {@link ChainComposer} otherwise. A misconfigured
     * block refuses the context here, naming the property (DB-C-10).
     *
     * @param tree     the bound keys
     * @param resolver the resolver
     * @return the factory
     */
    @Singleton
    @Requires(missingBeans = BootstrapChainFactory.class)
    public BootstrapChainFactory bootstrapChainFactory(BootstrapTree tree, DnsResolver resolver) {
        // The composer handles the no-key case too, so a DnsResolver bean expands seed
        // names even when no bootstrap key is set (review-110226-deadcode-parity B5).
        return BootstrapChainFactory.of(
                ChainComposer.factory(tree.getBootstrap(), tree.groupSettings(), resolver));
    }
}
