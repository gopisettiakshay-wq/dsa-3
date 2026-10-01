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
import java.util.Locale;
import java.util.Map;

/**
 * KMP Search Engine server. Run with: javac main.java && java main
 * Then open http://localhost:8080 in a browser.
 */
public class main {
    private static final int PORT = 8080;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext("/", main::servePage);
        server.createContext("/api/search", main::searchApi);
        server.setExecutor(null);
        server.start();
        System.out.println("KMP Search Engine is running at http://localhost:" + PORT);
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
            String query = caseSensitive ? pattern : pattern.toLowerCase(Locale.ROOT);
            int[] lps = buildLps(query);
            long comparisons = 0, characters = 0, totalMatches = 0;
            List<Object> results = new ArrayList<>();

            for (Object item : inputSources) {
                Map<String, Object> source = (Map<String, Object>) item;
                String name = (String) source.getOrDefault("name", "Untitled source");
                String text = (String) source.getOrDefault("text", "");
                MatchRun run = kmp(caseSensitive ? text : text.toLowerCase(Locale.ROOT), query, lps, overlap);
                comparisons += run.comparisons;
                characters += text.length();
                totalMatches += run.indices.size();
                List<Object> matches = new ArrayList<>();
                for (int index : run.indices) {
                    int[] location = lineAndColumn(text, index);
                    Map<String, Object> match = new LinkedHashMap<>();
                    match.put("index", index); match.put("line", location[0]); match.put("column", location[1]);
                    matches.add(match);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", name); result.put("text", text); result.put("matches", matches);
                results.add(result);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("lps", toList(lps)); response.put("matches", totalMatches);
            response.put("comparisons", comparisons); response.put("characters", characters);
            response.put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0); response.put("results", results);
            printSearchResults(pattern, caseSensitive, overlap, response);
            send(exchange, 200, "application/json; charset=utf-8", json(response));
        } catch (Exception error) {
            send(exchange, 400, "application/json; charset=utf-8", "{\"error\":\"Invalid search request.\"}");
        }
    }

    /** Print browser searches in IntelliJ's Run console as well as on the dashboard. */
    @SuppressWarnings("unchecked")
    private static void printSearchResults(String pattern, boolean caseSensitive,
                                           boolean overlap, Map<String, Object> response) {
        StringBuilder out = new StringBuilder("\n========== KMP SEARCH RESULTS ==========\n");
        out.append("Pattern: ").append(json(pattern)).append('\n');
        out.append("Case sensitive: ").append(caseSensitive).append('\n');
        out.append("Overlapping matches: ").append(overlap).append('\n');
        out.append("LPS table: ").append(response.get("lps")).append('\n');
        List<Object> results = (List<Object>) response.get("results");
        for (Object item : results) {
            Map<String, Object> result = (Map<String, Object>) item;
            List<Object> matches = (List<Object>) result.get("matches");
            out.append("\nSource: ").append(json(result.get("name"))).append('\n');
            out.append("Matches: ").append(matches.size()).append('\n');
            if (matches.isEmpty()) out.append("Pattern not found. You can edit and download this source in the browser.\n");
            for (Object hit : matches) {
                Map<String, Object> match = (Map<String, Object>) hit;
                out.append("  Index: ").append(match.get("index"))
                   .append(" | Line: ").append(match.get("line"))
                   .append(" | Column: ").append(match.get("column")).append('\n');
            }
        }
        out.append("\nSources searched: ").append(results.size()).append('\n');
        out.append("Total matches: ").append(response.get("matches")).append('\n');
        out.append("Character comparisons: ").append(response.get("comparisons")).append('\n');
        out.append("Characters analyzed: ").append(response.get("characters")).append('\n');
        out.append(String.format(Locale.ROOT, "Execution time: %.3f ms%n", response.get("elapsedMs")));
        out.append("Indices are zero-based; lines and columns start at 1.\n");
        out.append("========================================\n");
        System.out.print(out);
    }

    private static MatchRun kmp(String text, String pattern, int[] lps, boolean overlap) {
        List<Integer> indices = new ArrayList<>(); long comparisons = 0; int i = 0, j = 0;
        while (i < text.length()) { comparisons++;
            if (text.charAt(i) == pattern.charAt(j)) { i++; j++; if (j == pattern.length()) { indices.add(i - j); j = overlap ? lps[j - 1] : 0; } }
            else if (j > 0) j = lps[j - 1]; else i++;
        }
        return new MatchRun(indices, comparisons);
    }

    private static int[] buildLps(String pattern) {
        int[] lps = new int[pattern.length()];
        for (int i = 1, length = 0; i < pattern.length();) {
            if (pattern.charAt(i) == pattern.charAt(length)) lps[i++] = ++length;
            else if (length > 0) length = lps[length - 1]; else i++;
        }
        return lps;
    }

    private static int[] lineAndColumn(String text, int index) {
        int line = 1, column = 1;
        for (int i = 0; i < index; i++) { if (text.charAt(i) == '\n') { line++; column = 1; } else column++; }
        return new int[] { line, column };
    }

    private static List<Object> toList(int[] values) { List<Object> list = new ArrayList<>(); for (int value : values) list.add(value); return list; }
    private static void send(HttpExchange exchange, int status, String type, String text) throws IOException { byte[] bytes = text.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }

    private record MatchRun(List<Integer> indices, long comparisons) { }

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
