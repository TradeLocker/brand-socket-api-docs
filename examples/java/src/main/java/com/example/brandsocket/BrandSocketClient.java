package com.example.brandsocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.engineio.client.transports.WebSocket;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Reference Brand Socket client for the JVM (io.socket:socket.io-client 2.x).
 *
 * <pre>
 * BRAND_API_KEY=... BRAND_SOCKET_URL=https://api-dev.tradelocker.com ACCOUNT_TYPE=LIVE mvn -q compile exec:java
 * </pre>
 *
 * The rules it follows are the ones integrations break most often: one process per API key and
 * account type (a {@code disconnected} status means another consumer took the key, so this process
 * stops); reconnect only after {@code shutdown} or {@code connectionError}, with a delay (10
 * attempts per 2 minutes per brand are refused); rebuild state from every snapshot up to
 * {@code Property SyncEnd}; figures as {@link BigDecimal}, IDs as strings; quote subscriptions with
 * an acknowledgement, sent after {@code connected}.
 */
public final class BrandSocketClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RETRYABLE_HANDSHAKE = Pattern.compile("^(conflict|internalServer|unknown)");

    private final String url = System.getenv().getOrDefault("BRAND_SOCKET_URL", "https://api.tradelocker.com");
    private final String key = System.getenv("BRAND_API_KEY");
    private final String type = System.getenv().getOrDefault("ACCOUNT_TYPE", "LIVE");
    private final List<String> instruments =
            List.of(System.getenv().getOrDefault("INSTRUMENTS", "").split(",")).stream().filter(s -> !s.isBlank()).toList();

    // State, rebuilt from every snapshot.
    private final Map<String, JsonNode> accounts = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> positions = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> orders = new ConcurrentHashMap<>();
    private volatile boolean synced;

    private final AtomicInteger attempt = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public static void main(String[] args) throws Exception {
        BrandSocketClient client = new BrandSocketClient();
        if (client.key == null || client.key.isBlank()) {
            System.err.println("BRAND_API_KEY is required");
            System.exit(2);
        }
        client.connect();
        Thread.currentThread().join();
    }

    private void connect() {
        accounts.clear();
        positions.clear();
        orders.clear();
        synced = false;

        IO.Options options = IO.Options.builder()
                .setPath("/brand-api/socket.io")
                .setTransports(new String[] {WebSocket.NAME}) // the server offers no long-polling
                .setQuery("type=" + type)
                .setExtraHeaders(Map.of(
                        "brand-api-key", List.of(key), // never in the URL
                        "User-Agent", List.of("my-brand-integration/1.0 (ops@example.com)")))
                .setReconnection(false) // we decide when to reconnect
                .build();
        Socket socket = IO.socket(URI.create(url + "/brand-socket"), options);

        socket.on("connection", args -> {
            JsonNode status = node(args[0]);
            System.out.println("connection " + status);
            String code = status.path("code").asText("");
            if (code.equals("connected")) {
                attempt.set(0);
                for (String instrument : instruments) {
                    JSONObject request = new JSONObject(Map.of("action", "SUBSCRIBE", "instrument", instrument));
                    // The ack answers every request; one sent before SyncEnd is answered after it.
                    socket.emit("subscriptions", new Object[] {request}, (Ack) ack -> System.out.println("subscribe " + node(ack[0])));
                }
                return;
            }
            if ("error".equals(status.path("status").asText()) && !synced && !code.equals("connectionError")) {
                // Handshake refused: the server closes the socket. Fix the cause before retrying;
                // only conflict.*, internalServer.* and unknown deserve a bounded retry.
                if (!RETRYABLE_HANDSHAKE.matcher(code).find()) {
                    System.err.println("handshake refused, not retrying: " + code);
                    stopped.set(true);
                }
                return;
            }
            if (code.equals("disconnected")) {
                // Another connection with this key and type replaced us. Stop: reconnecting here
                // makes two consumers replace each other forever.
                System.err.println("replaced or closed by the server; not reconnecting");
                stopped.set(true);
            }
            // `shutdown` and `connectionError` are followed by the disconnect below, which reconnects.
        });

        socket.on("stream", args -> apply(node(args[0])));
        socket.on("subscriptions", args -> System.out.println("quote " + node(args[0])));

        socket.on(Socket.EVENT_DISCONNECT, args -> {
            System.out.println("disconnect " + args[0]);
            if (stopped.get()) {
                return;
            }
            int n = attempt.incrementAndGet();
            long delayMs = Math.min(30_000, 1_000L << Math.min(n, 5)) + ThreadLocalRandom.current().nextInt(1_000);
            System.out.println("reconnecting in " + delayMs + " ms (attempt " + n + ")");
            new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                connect();
            }, "brand-socket-reconnect").start();
        });
        socket.on(Socket.EVENT_CONNECT_ERROR, args -> System.err.println("connect_error " + args[0]));
        socket.connect();
    }

    private void apply(JsonNode message) {
        switch (message.path("type").asText("")) {
            case "AccountStatus" -> {
                // Partial update: keep the fields the server did not resend.
                JsonNode previous = accounts.get(message.path("accountId").asText());
                accounts.put(message.path("accountId").asText(), previous == null ? message : merge(previous, message));
            }
            case "Position" -> positions.put(message.path("positionId").asText(), message);
            case "ClosePosition" -> positions.remove(message.path("positionId").asText()); // may be unknown to us
            case "OpenOrder" -> {
                if (message.path("isOpen").isBoolean() && !message.path("isOpen").asBoolean()) {
                    orders.remove(message.path("orderId").asText());
                } else {
                    orders.put(message.path("orderId").asText(), message);
                }
            }
            case "Property" -> {
                if (message.path("name").asText().equals("SyncEnd")) {
                    synced = true;
                    System.out.printf("SyncEnd: %d accounts, %d positions, %d open orders%n", accounts.size(), positions.size(), orders.size());
                }
            }
            default -> {
                // New message types can appear; ignore what you do not know.
            }
        }
    }

    /** Figures are decimal strings: parse with BigDecimal, never with double. */
    static BigDecimal figure(JsonNode message, String field) {
        JsonNode value = message.get(field);
        return value == null || value.isNull() ? null : new BigDecimal(value.asText());
    }

    private static JsonNode merge(JsonNode previous, JsonNode update) {
        var merged = previous.deepCopy();
        update.fields().forEachRemaining(e -> ((com.fasterxml.jackson.databind.node.ObjectNode) merged).set(e.getKey(), e.getValue()));
        return merged;
    }

    private static JsonNode node(Object socketIoArg) {
        try {
            return JSON.readTree(socketIoArg.toString());
        } catch (Exception e) {
            throw new IllegalStateException("unreadable payload: " + socketIoArg, e);
        }
    }
}
