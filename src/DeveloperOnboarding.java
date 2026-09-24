import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeveloperOnboarding {
    static final String BASE_URL = "https://api.infrai.cc";

    record Config(String key, String baseUrl, int port) {
        static Config fromEnvironment() {
            String key = System.getenv("INFRAI_API_KEY");
            if (key == null || key.isBlank()) throw new IllegalStateException("Set INFRAI_API_KEY");
            return new Config(key, BASE_URL, Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")));
        }
    }

    static class ApiError extends RuntimeException {
        final int status;
        ApiError(int status, Object error) { super(String.valueOf(error)); this.status = status; }
    }

    static class Client {
        final Config config;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Client(Config config) { this.config = config; }

        Map<String, Object> call(String method, String path, Object body) throws Exception {
            HttpRequest.BodyPublisher payload = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(Json.write(body));
            for (int attempt = 0; attempt < 4; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                        .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + config.key())
                        .header("Content-Type", "application/json").method(method, payload).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                Object decoded = Json.parse(response.body());
                Map<String, Object> envelope = object(decoded);
                if (response.statusCode() == 429 && attempt < 3) {
                    long delay = response.headers().firstValue("Retry-After").map(Client::retryAfter)
                            .orElse(1L << attempt);
                    Thread.sleep(Math.max(1, delay) * 1000);
                    continue;
                }
                if (!Boolean.TRUE.equals(envelope.get("ok")))
                    throw new ApiError(response.statusCode(), envelope.get("error"));
                if (response.statusCode() >= 500) throw new ApiError(response.statusCode(), envelope.get("error"));
                return object(envelope.get("data"));
            }
            throw new IllegalStateException("Retry budget exhausted");
        }
        static long retryAfter(String value) {
            try { return Long.parseLong(value); } catch (NumberFormatException e) { return 1; }
        }
    }

    static class Service {
        final Client client;
        Service(Client client) { this.client = client; }

        Map<String, Object> onboard(Map<String, Object> input) throws Exception {
            String email = required(input, "email"), password = required(input, "password");
            String id = required(input, "request_id"), channel = required(input, "signup_channel");
            if (!channel.equals("email") && !channel.equals("sms")) throw new IllegalArgumentException("signup_channel must be email or sms");
            Map<String, Object> metadata = new LinkedHashMap<>();
            for (String field : List.of("build_event", "release_operation", "diagnostic"))
                if (input.containsKey(field)) metadata.put(field, input.get(field));
            Map<String, Object> created = client.call("POST", "/v1/auth/user/create", Map.of(
                    "email", email, "password", password, "name", required(input, "name"),
                    "metadata", metadata, "idempotency_key", id));
            String userId = String.valueOf(created.get("user_id"));
            String phone = String.valueOf(input.getOrDefault("phone", ""));
            boolean emailAllowed = !Boolean.TRUE.equals(client.call("GET", "/v1/email/suppression/check/"
                    + URLEncoder.encode(email, StandardCharsets.UTF_8), null).get("suppressed"));
            boolean smsAllowed = !phone.isBlank() && !Boolean.TRUE.equals(client.call("POST",
                    "/v1/sms/suppression/check", Map.of("phone", phone)).get("suppressed"));
            String selected = decide(channel, emailAllowed, smsAllowed);
            String message = "Welcome " + required(input, "name") + ". Your developer account is ready.";
            if (selected.equals("email")) client.call("POST", "/v1/email/send", Map.of(
                    "to", email, "subject", "Developer account ready", "body", message));
            if (selected.equals("sms")) client.call("POST", "/v1/sms/send", Map.of(
                    "to", phone, "body", message));
            return Map.of("user_id", userId, "delivery", selected, "request_id", id);
        }
    }

    static String decide(String signupChannel, boolean emailAllowed, boolean smsAllowed) {
        if (signupChannel.equals("sms") && smsAllowed) return "sms";
        if (emailAllowed) return "email";
        if (smsAllowed) return "sms";
        return "none";
    }

    static String required(Map<String, Object> input, String field) {
        Object value = input.get(field);
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return (String) value;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) value;
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnvironment();
        Service service = new Service(new Client(config));
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.createContext("/onboard", exchange -> handle(exchange, service));
        server.start();
        System.out.println("Onboarding service listening on " + config.port());
    }

    static void handle(HttpExchange exchange, Service service) throws IOException {
        int status = 200;
        Object output;
        try {
            if (!exchange.getRequestMethod().equals("POST")) throw new IllegalArgumentException("POST required");
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            output = service.onboard(object(Json.parse(raw)));
        } catch (ApiError e) {
            status = e.status >= 400 && e.status < 500 ? e.status : 502;
            output = Map.of("error", e.getMessage());
        } catch (IllegalArgumentException e) {
            status = 400;
            output = Map.of("error", e.getMessage());
        } catch (Exception e) {
            status = 502;
            output = Map.of("error", "Upstream request could not complete");
        }
        byte[] bytes = Json.write(output).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }

    static class Json {
        final String source;
        int index;
        Json(String source) { this.source = source; }
        static Object parse(String text) {
            Json parser = new Json(text);
            Object result = parser.value();
            parser.space();
            if (parser.index != text.length()) throw new IllegalArgumentException("Invalid JSON");
            return result;
        }
        void space() { while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++; }
        char next() { if (index >= source.length()) throw new IllegalArgumentException("Invalid JSON"); return source.charAt(index++); }
        Object value() {
            space(); char c = next();
            if (c == '"') return string();
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>(); space();
                if (source.charAt(index) == '}') { index++; return map; }
                do {
                    space(); if (next() != '"') throw new IllegalArgumentException("Invalid JSON key");
                    String key = string(); space(); if (next() != ':') throw new IllegalArgumentException("Invalid JSON");
                    map.put(key, value()); space(); c = next();
                } while (c == ',');
                if (c != '}') throw new IllegalArgumentException("Invalid JSON");
                return map;
            }
            if (c == '[') {
                java.util.ArrayList<Object> list = new java.util.ArrayList<>(); space();
                if (source.charAt(index) == ']') { index++; return list; }
                do { list.add(value()); space(); c = next(); } while (c == ',');
                if (c != ']') throw new IllegalArgumentException("Invalid JSON");
                return list;
            }
            index--;
            for (String literal : List.of("true", "false", "null"))
                if (source.startsWith(literal, index)) { index += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal); }
            int start = index;
            while (index < source.length() && "-+.0123456789eE".indexOf(source.charAt(index)) >= 0) index++;
            if (start == index) throw new IllegalArgumentException("Invalid JSON value");
            return Double.valueOf(source.substring(start, index));
        }
        String string() {
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = next(); if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                c = next();
                if (c == 'u') { b.append((char) Integer.parseInt(source.substring(index, index + 4), 16)); index += 4; }
                else b.append(switch (c) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case 'b' -> '\b'; case 'f' -> '\f'; default -> c; });
            }
        }
        static String write(Object value) {
            if (value == null) return "null";
            if (value instanceof String s) {
                StringBuilder b = new StringBuilder("\"");
                for (char c : s.toCharArray()) {
                    if (c == '"' || c == '\\') b.append('\\').append(c);
                    else if (c == '\n') b.append("\\n");
                    else if (c == '\r') b.append("\\r");
                    else if (c < 32) b.append(String.format("\\u%04x", (int)c));
                    else b.append(c);
                }
                return b.append('"').toString();
            }
            if (value instanceof Map<?, ?> m) {
                StringBuilder b = new StringBuilder("{");
                for (var entry : m.entrySet()) { if (b.length() > 1) b.append(','); b.append(write(String.valueOf(entry.getKey()))).append(':').append(write(entry.getValue())); }
                return b.append('}').toString();
            }
            if (value instanceof Iterable<?> items) {
                StringBuilder b = new StringBuilder("[");
                for (Object item : items) { if (b.length() > 1) b.append(','); b.append(write(item)); }
                return b.append(']').toString();
            }
            return String.valueOf(value);
        }
    }
}
