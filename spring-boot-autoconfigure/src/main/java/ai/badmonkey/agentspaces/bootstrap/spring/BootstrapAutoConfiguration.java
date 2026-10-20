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

import ai.badmonkey.agentspaces.bootstrap.DnsResolver;
import ai.badmonkey.agentspaces.bootstrap.JndiDnsResolver;
import ai.badmonkey.agentspaces.bootstrap.chain.ChainComposer;
import ai.badmonkey.agentspaces.spring.AgentSpacesAutoConfiguration;
import ai.badmonkey.agentspaces.spring.BootstrapChainFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The bootstrap seams of the AgentSpaces starter filled from
 * {@code agentspaces.bootstrap.*} (SRAD DB-C-02, DB-C-03, DB-C-07): a
 * {@link DnsResolver} over the JDK's JNDI provider and a
 * {@link BootstrapChainFactory} that composes each group's chain from the
 * configured seeds, the locators the properties ask for, and the
 * application's {@code BeaconLocator} beans. Both yield to an application
 * bean of the same type: define a {@code DnsResolver} to resolve over DoH or
 * with DNSSEC, a {@code BootstrapChainFactory} to replace the composition
 * policy wholesale. Runs before the starter so its beans are in place when
 * the groups join.
 */
@AutoConfiguration(before = AgentSpacesAutoConfiguration.class)
@EnableConfigurationProperties(BootstrapProperties.class)
public class BootstrapAutoConfiguration {

    /**
     * The resolver the DNS locators ask: the JDK's JNDI provider, which
     * follows the host's resolver configuration and authenticates nothing.
     *
     * @return the resolver
     */
    @Bean
    @ConditionalOnMissingBean
    public DnsResolver bootstrapDnsResolver() {
        return new JndiDnsResolver();
    }

    /**
     * The chain per group. With no bootstrap key anywhere, the starter's
     * default chain over the seeds and the bean locators; otherwise the
     * composition of {@link ChainComposer}. A misconfigured block refuses
     * the context here, naming the property (DB-C-10).
     *
     * @param properties the bound bootstrap keys
     * @param resolver   the resolver
     * @return the factory
     */
    @Bean
    @ConditionalOnMissingBean
    public BootstrapChainFactory bootstrapChainFactory(BootstrapProperties properties, DnsResolver resolver) {
        if (properties.isEmpty()) {
            return BootstrapChainFactory.defaults();
        }
        return ChainComposer.factory(properties.getBootstrap(), properties.groupSettings(), resolver);
    }
}
