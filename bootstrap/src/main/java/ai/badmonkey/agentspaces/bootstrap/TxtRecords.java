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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** TXT key-value parsing (RFC 6763 §6, RFC 1464): {@code key=value} strings, first key wins, keys lower-cased. */
final class TxtRecords {

    private TxtRecords() {
    }

    static Map<String, String> parse(List<List<String>> records) {
        Map<String, String> out = new LinkedHashMap<>();
        for (List<String> record : records) {
            for (String string : record) {
                int eq = string.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = string.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = string.substring(eq + 1).trim();
                out.putIfAbsent(key, value);
            }
        }
        return out;
    }
}
