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

import ai.badmonkey.agentspaces.bootstrap.BootstrapSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bootstrap keys of the {@code agentspaces} prefix (SRAD appendix D):
 * {@code agentspaces.bootstrap.*} fleet-wide and
 * {@code agentspaces.groups[].bootstrap.*} per group. The prefix is shared
 * with the starter's own {@code AgentSpacesProperties}; this class binds
 * only the keys it declares and ignores the rest ({@code founding},
 * {@code seeds}, {@code spaces}, and so on), which Boot allows because
 * unknown fields are ignored by default.
 */
@ConfigurationProperties(prefix = "agentspaces")
public class BootstrapProperties {

    /** The fleet-wide defaults ({@code agentspaces.bootstrap.*}). */
    private BootstrapSettings bootstrap = new BootstrapSettings();

    /** The groups, each with its name and its bootstrap block. */
    private List<GroupBootstrap> groups = new ArrayList<>();

    /** One group's bootstrap block ({@code agentspaces.groups[].bootstrap.*}). */
    public static class GroupBootstrap {

        /** The group's name, as in {@code agentspaces.groups[].name}. */
        private String name;

        /** The per-group overrides of the fleet-wide block. */
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
        this.bootstrap = bootstrap == null ? new BootstrapSettings() : bootstrap;
    }

    public List<GroupBootstrap> getGroups() {
        return groups;
    }

    public void setGroups(List<GroupBootstrap> groups) {
        this.groups = groups == null ? new ArrayList<>() : groups;
    }

    /** The group blocks by name, groups without a block omitted. */
    public Map<String, BootstrapSettings> groupSettings() {
        Map<String, BootstrapSettings> out = new LinkedHashMap<>();
        for (GroupBootstrap group : groups) {
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
}
