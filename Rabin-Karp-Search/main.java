import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rabin-Karp Search Engine server. Run with: javac main.java && java main
 * Then open http://localhost:8080 in a browser.
 */
public class main {
    private static final int PORT = Integer.getInteger("port", 8080);
    private static final long BASE = 257;
    private static final long MOD = 1_000_000_007L;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext("/", main::servePage);
        server.createContext("/api/search", main::searchApi);
        server.setExecutor(null);
        server.start();
        System.out.println("Rabin-Karp Search Engine is running at http://localhost:" + PORT);
        System.out.println("Press Ctrl+C to stop the server.");
    }

    private static void servePage(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { send(exchange, 405, "text/plain", "Method not allowed"); return; }
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        for (String location : List.of("index.html", "src/index.html", "src/main/resources/index.html")) {
            Path page = workingDirectory.resolve(location);
            if (Files.isRegularFile(page)) {
                send(exchange, 200, "text/html; charset=utf-8", Files.readString(page));
                return;
            }
        }
        send(exchange, 404, "text/plain; charset=utf-8",
                "Cannot find index.html.\n\nWorking directory: " + workingDirectory
                + "\n\nPlace index.html here or in its src folder."
                + "\nIn IntelliJ: Run > Edit Configurations > Working directory > select your project folder.");
    }

    @SuppressWarnings("unchecked")
    private static void searchApi(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, "application/json", "{\"error\":\"POST required\"}"); return; }
        try (InputStream body = exchange.getRequestBody()) {
            Map<String, Object> request = (Map<String, Object>) new JsonParser(new String(body.readAllBytes(), StandardCharsets.UTF_8)).parse();
            String pattern = (String) request.getOrDefault("pattern", "");
            boolean caseSensitive = Boolean.TRUE.equals(request.get("caseSensitive"));
            boolean overlap = !Boolean.FALSE.equals(request.get("overlap"));
            List<Object> inputSources = (List<Object>) request.getOrDefault("sources", List.of());
            if (pattern.isEmpty() || inputSources.isEmpty()) { send(exchange, 400, "application/json", "{\"error\":\"A pattern and at least one source are required.\"}"); return; }

            long started = System.nanoTime();
            String query = caseSensitive ? pattern : foldCase(pattern);
            long patternHash = hash(query);
            long windows = 0, hashHits = 0, collisions = 0;
            long comparisons = 0, characters = 0, totalMatches = 0;
            List<Object> results = new ArrayList<>();

            for (Object item : inputSources) {
                Map<String, Object> source = (Map<String, Object>) item;
                String name = (String) source.getOrDefault("name", "Untitled source");
                String text = (String) source.getOrDefault("text", "");
                MatchRun run = rabinKarp(caseSensitive ? text : foldCase(text), query, patternHash, overlap);
                comparisons += run.comparisons;
                windows += run.windows; hashHits += run.hashHits; collisions += run.collisions;
                characters += text.length();
                totalMatches += run.indices.size();
                List<Object> matches = new ArrayList<>();
                int cursor = 0, line = 1, column = 1;
                for (int index : run.indices) {
                    while (cursor < index) { if (text.charAt(cursor++) == '\n') { line++; column = 1; } else column++; }
                    int[] location = {line, column};
                    Map<String, Object> match = new LinkedHashMap<>();
                    match.put("index", index); match.put("line", location[0]); match.put("column", location[1]);
                    matches.add(match);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", name); result.put("text", text); result.put("matches", matches);
                results.add(result);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("patternHash", patternHash); response.put("base", BASE); response.put("modulus", MOD);
            response.put("windows", windows); response.put("hashHits", hashHits); response.put("collisions", collisions); response.put("matches", totalMatches);
            response.put("comparisons", comparisons); response.put("characters", characters);
            response.put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0); response.put("results", results);
            send(exchange, 200, "application/json; charset=utf-8", json(response));
        } catch (Exception error) {
            send(exchange, 400, "application/json; charset=utf-8", "{\"error\":\"Invalid search request.\"}");
        }
    }

    // Polynomial rolling hash. Equal hashes are always verified character by character.
    private static long hash(String value) {
        long h = 0;
        for (int i = 0; i < value.length(); i++) h = (h * BASE + value.charAt(i)) % MOD;
        return h;
    }

    private static MatchRun rabinKarp(String text, String pattern, long patternHash, boolean overlap) {
        if (pattern.isEmpty()) throw new IllegalArgumentException("Empty pattern");
        List<Integer> indices = new ArrayList<>();
        int n = text.length(), m = pattern.length(), nextAllowed = 0;
        long comparisons = 0, windows = 0, hashHits = 0, collisions = 0;
        if (m > n) return new MatchRun(indices, 0, 0, 0, 0);
        long high = 1, windowHash = 0;
        for (int i = 0; i < m; i++) {
            if (i > 0) high = (high * BASE) % MOD;
            windowHash = (windowHash * BASE + text.charAt(i)) % MOD;
        }
        for (int start = 0; start <= n - m; start++) {
            if (start >= nextAllowed) {
                windows++;
                if (windowHash == patternHash) {
                    hashHits++;
                    int matched = 0;
                    while (matched < m) {
                        comparisons++;
                        if (text.charAt(start + matched) != pattern.charAt(matched)) break;
                        matched++;
                    }
                    if (matched == m) {
                        indices.add(start);
                        if (!overlap) nextAllowed = start + m;
                    } else collisions++;
                }
            }
            if (start < n - m) {
                windowHash = (windowHash - text.charAt(start) * high % MOD + MOD) % MOD;
                windowHash = (windowHash * BASE + text.charAt(start + m)) % MOD;
            }
        }
        return new MatchRun(indices, comparisons, windows, hashHits, collisions);
    }

    // Simple UTF-16 case folding preserves original offsets; not full Unicode case folding.
    private static String foldCase(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) chars[i] = Character.toLowerCase(chars[i]);
        return new String(chars);
    }

    private static void send(HttpExchange exchange, int status, String type, String text) throws IOException { byte[] bytes = text.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }

    private record MatchRun(List<Integer> indices, long comparisons, long windows, long hashHits, long collisions) { }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String string) return "\"" + string.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) { List<String> fields = new ArrayList<>(); for (Map.Entry<?, ?> entry : map.entrySet()) fields.add(json(entry.getKey().toString()) + ":" + json(entry.getValue())); return "{" + String.join(",", fields) + "}"; }
        List<String> values = new ArrayList<>(); for (Object item : (List<?>) value) values.add(json(item)); return "[" + String.join(",", values) + "]";
    }

    /** Small JSON reader for the request format used by index.html; no external libraries needed. */
    private static final class JsonParser {
        private final String input; private int cursor;
        JsonParser(String input) { this.input = input; }
        Object parse() { Object value = value(); space(); if (cursor != input.length()) throw new IllegalArgumentException(); return value; }
        private Object value() { space(); char c = input.charAt(cursor); if (c == '{') return object(); if (c == '[') return array(); if (c == '\"') return string(); if (c == 't') { cursor += 4; return true; } if (c == 'f') { cursor += 5; return false; } if (c == 'n') { cursor += 4; return null; } return number(); }
        private Map<String, Object> object() { Map<String, Object> map = new LinkedHashMap<>(); cursor++; space(); if (take('}')) return map; do { space(); String key = string(); space(); need(':'); map.put(key, value()); space(); } while (take(',')); need('}'); return map; }
        private List<Object> array() { List<Object> list = new ArrayList<>(); cursor++; space(); if (take(']')) return list; do { list.add(value()); space(); } while (take(',')); need(']'); return list; }
        private String string() { need('\"'); StringBuilder out = new StringBuilder(); while (cursor < input.length()) { char c = input.charAt(cursor++); if (c == '\"') return out.toString(); if (c != '\\') { out.append(c); continue; } char e = input.charAt(cursor++); if (e == 'u') { out.append((char) Integer.parseInt(input.substring(cursor, cursor + 4), 16)); cursor += 4; } else out.append(switch (e) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case 'b' -> '\b'; case 'f' -> '\f'; default -> e; }); } throw new IllegalArgumentException(); }
        private Number number() { int start = cursor; while (cursor < input.length() && "-+0123456789.eE".indexOf(input.charAt(cursor)) >= 0) cursor++; return Double.parseDouble(input.substring(start, cursor)); }
        private void space() { while (cursor < input.length() && Character.isWhitespace(input.charAt(cursor))) cursor++; }
        private boolean take(char expected) { if (cursor < input.length() && input.charAt(cursor) == expected) { cursor++; return true; } return false; }
        private void need(char expected) { if (!take(expected)) throw new IllegalArgumentException(); }
    }
}
