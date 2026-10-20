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

import ai.badmonkey.agentspaces.api.spi.BeaconHint;
import ai.badmonkey.agentspaces.api.spi.BeaconLocator;
import ai.badmonkey.agentspaces.api.spi.BeaconRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A beacon document from a file or a classpath resource: an air-gapped
 * site's copy, a test fixture, or the file a beacon writes for an ingress to
 * serve. Read on every locate, so an updated file is seen at the next re-ask.
 */
public final class StaticDocumentLocator implements BeaconLocator {

    /** The locator's name. */
    public static final String NAME = "document";

    private final Path path;
    private final String resource;
    private final UrlLocator.Options options;

    private StaticDocumentLocator(Path path, String resource, UrlLocator.Options options) {
        this.path = path;
        this.resource = resource;
        this.options = Objects.requireNonNull(options, "options");
    }

    /** A document at a path. */
    public static StaticDocumentLocator file(Path path, UrlLocator.Options options) {
        return new StaticDocumentLocator(Objects.requireNonNull(path, "path"), null, options);
    }

    /** A document on the classpath ({@code classpath:beacon.json} or a bare resource name). */
    public static StaticDocumentLocator resource(String name, UrlLocator.Options options) {
        String n = Objects.requireNonNull(name, "name").trim();
        return new StaticDocumentLocator(null, n.startsWith("classpath:") ? n.substring("classpath:".length()) : n,
                options);
    }

    /** A document at a path or on the classpath, by the {@code classpath:} prefix. */
    public static StaticDocumentLocator of(String location, UrlLocator.Options options) {
        String l = Objects.requireNonNull(location, "location").trim();
        return l.startsWith("classpath:") ? resource(l, options) : file(Path.of(l), options);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public BeaconHint locate(BeaconRequest request) throws IOException {
        byte[] bytes;
        String evidence;
        if (path != null) {
            if (!Files.isReadable(path)) {
                return BeaconHint.none();
            }
            bytes = Files.readAllBytes(path);
            evidence = "file " + path;
        } else {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = StaticDocumentLocator.class.getClassLoader();
            }
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    return BeaconHint.none();
                }
                bytes = in.readAllBytes();
            }
            evidence = "classpath:" + resource;
        }
        return UrlLocator.hint(BeaconDocument.parse(bytes), request, options, evidence);
    }
}
