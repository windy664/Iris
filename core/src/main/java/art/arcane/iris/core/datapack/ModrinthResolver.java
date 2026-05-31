/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.core.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ModrinthResolver {
    private static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "VolmitSoftware/Iris (datapack-ingest)";
    private static final String DATAPACK_LOADER = "datapack";

    private ModrinthResolver() {
    }

    public static ResolvedDatapack resolve(String rawUrl, String serverMcVersion) throws IOException {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty()) {
            throw new IOException("Empty datapack url");
        }

        ModrinthRef ref = parse(url);
        if (ref == null) {
            return directResolve(url);
        }

        JsonArray versions = getJsonArray(API + "/project/" + ref.slug + "/version");
        if (versions == null || versions.isEmpty()) {
            throw new IOException("Modrinth project '" + ref.slug + "' returned no versions");
        }

        JsonObject version = ref.versionToken == null
                ? selectLatestDatapackVersion(versions, serverMcVersion)
                : selectVersionByToken(versions, ref.versionToken);
        if (version == null) {
            String detail = ref.versionToken == null ? "no datapack-loader version" : "no version matching '" + ref.versionToken + "'";
            throw new IOException("Modrinth project '" + ref.slug + "' has " + detail);
        }

        JsonObject file = selectFile(version);
        if (file == null) {
            throw new IOException("Modrinth version for '" + ref.slug + "' has no downloadable file");
        }

        return toResolved(version, file, ref.slug);
    }

    private static ModrinthRef parse(String url) {
        if (!url.toLowerCase(Locale.ROOT).contains("modrinth.com/")) {
            return null;
        }

        String path = url.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }

        List<String> parts = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                parts.add(segment);
            }
        }

        int base = -1;
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).equalsIgnoreCase("modrinth.com")) {
                base = i;
                break;
            }
        }
        if (base < 0 || parts.size() < base + 3) {
            return null;
        }

        String slug = parts.get(base + 2);
        String token = null;
        for (int i = base + 3; i + 1 < parts.size(); i++) {
            if (parts.get(i).equalsIgnoreCase("version")) {
                token = parts.get(i + 1);
                break;
            }
        }
        return new ModrinthRef(slug, token);
    }

    private static JsonObject selectVersionByToken(JsonArray versions, String token) {
        JsonObject datapackMatch = null;
        JsonObject anyMatch = null;
        String normalizedToken = normalizeVersion(token);

        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            String id = optString(version, "id");
            String number = optString(version, "version_number");
            boolean matches = token.equals(id)
                    || token.equalsIgnoreCase(number)
                    || normalizedToken.equals(normalizeVersion(number));
            if (!matches) {
                continue;
            }
            if (anyMatch == null) {
                anyMatch = version;
            }
            if (isDatapack(version)) {
                datapackMatch = version;
                break;
            }
        }

        return datapackMatch != null ? datapackMatch : anyMatch;
    }

    private static JsonObject selectLatestDatapackVersion(JsonArray versions, String serverMcVersion) {
        JsonObject firstDatapack = null;
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            if (!isDatapack(version)) {
                continue;
            }
            if (firstDatapack == null) {
                firstDatapack = version;
            }
            if (serverMcVersion != null && !serverMcVersion.isBlank() && gameVersionsContains(version, serverMcVersion)) {
                return version;
            }
        }
        return firstDatapack;
    }

    private static JsonObject selectFile(JsonObject version) {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null || files.isEmpty()) {
            return null;
        }

        JsonObject primaryZip = null;
        JsonObject anyZip = null;
        JsonObject primary = null;
        JsonObject first = null;
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if (first == null) {
                first = file;
            }
            boolean isPrimary = file.has("primary") && file.get("primary").getAsBoolean();
            boolean isZip = optString(file, "filename").toLowerCase(Locale.ROOT).endsWith(".zip");
            if (isPrimary && primary == null) {
                primary = file;
            }
            if (isZip && anyZip == null) {
                anyZip = file;
            }
            if (isPrimary && isZip && primaryZip == null) {
                primaryZip = file;
            }
        }

        if (primaryZip != null) {
            return primaryZip;
        }
        if (anyZip != null) {
            return anyZip;
        }
        if (primary != null) {
            return primary;
        }
        return first;
    }

    private static ResolvedDatapack toResolved(JsonObject version, JsonObject file, String slug) {
        String downloadUrl = optString(file, "url");
        String filename = optString(file, "filename");
        String sha1 = null;
        if (file.has("hashes") && file.get("hashes").isJsonObject()) {
            sha1 = optString(file.getAsJsonObject("hashes"), "sha1");
        }
        return new ResolvedDatapack(downloadUrl, filename, sha1, optString(version, "id"), optString(version, "version_number"), slug);
    }

    private static ResolvedDatapack directResolve(String url) {
        String filename = url;
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) {
            filename = filename.substring(slash + 1);
        }
        int query = filename.indexOf('?');
        if (query >= 0) {
            filename = filename.substring(0, query);
        }
        if (filename.isBlank()) {
            filename = "datapack.zip";
        }
        return new ResolvedDatapack(url, filename, null, "direct", "direct", null);
    }

    private static boolean isDatapack(JsonObject version) {
        JsonArray loaders = version.getAsJsonArray("loaders");
        if (loaders == null) {
            return false;
        }
        for (JsonElement loader : loaders) {
            if (DATAPACK_LOADER.equalsIgnoreCase(loader.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean gameVersionsContains(JsonObject version, String mc) {
        JsonArray gameVersions = version.getAsJsonArray("game_versions");
        if (gameVersions == null) {
            return false;
        }
        for (JsonElement gv : gameVersions) {
            if (mc.equalsIgnoreCase(gv.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeVersion(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("v")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String optString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static JsonArray getJsonArray(String url) throws IOException {
        String body = httpGet(url);
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonArray()) {
            throw new IOException("Unexpected response from " + url);
        }
        return parsed.getAsJsonArray();
    }

    private static String httpGet(String url) throws IOException {
        URL target = URI.create(url).toURL();
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);

        int code = connection.getResponseCode();
        if (code != 200) {
            connection.disconnect();
            throw new IOException("HTTP " + code + " from " + url);
        }

        StringBuilder builder = new StringBuilder();
        try (InputStream input = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        } finally {
            connection.disconnect();
        }
        return builder.toString();
    }

    private static final class ModrinthRef {
        private final String slug;
        private final String versionToken;

        private ModrinthRef(String slug, String versionToken) {
            this.slug = slug;
            this.versionToken = versionToken;
        }
    }

    public static final class ResolvedDatapack {
        private final String downloadUrl;
        private final String fileName;
        private final String sha1;
        private final String versionId;
        private final String versionNumber;
        private final String projectSlug;

        public ResolvedDatapack(String downloadUrl, String fileName, String sha1, String versionId, String versionNumber, String projectSlug) {
            this.downloadUrl = downloadUrl;
            this.fileName = fileName;
            this.sha1 = sha1;
            this.versionId = versionId;
            this.versionNumber = versionNumber;
            this.projectSlug = projectSlug;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public String getFileName() {
            return fileName;
        }

        public String getSha1() {
            return sha1;
        }

        public String getVersionId() {
            return versionId;
        }

        public String getVersionNumber() {
            return versionNumber;
        }

        public String getProjectSlug() {
            return projectSlug;
        }
    }
}
