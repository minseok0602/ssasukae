package experiment;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.ObjectMapper;

/** Credentials arrive on stdin and are never written to the event log or process arguments. */
public class StompProbe {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static synchronized void out(Object value) {
    System.out.println("EXPERIMENT:" + MAPPER.writeValueAsString(value));
    System.out.flush();
  }
  public static void main(String[] args) throws Exception {
    WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setDefaultHeartbeat(new long[] {0, 0});
    StompSession session = null;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
      String line;
      while ((line = reader.readLine()) != null) {
        Map<?, ?> command = MAPPER.readValue(line, Map.class);
        String op = command.get("op").toString();
        if (op.equals("connect")) {
          if (session != null && session.isConnected()) session.disconnect();
          StompHeaders headers = new StompHeaders();
          headers.add("Authorization", "Bearer " + command.get("token"));
          session = client.connectAsync(command.get("url").toString(), new WebSocketHttpHeaders(), headers,
              new StompSessionHandlerAdapter() {
                @Override public void handleTransportError(StompSession s, Throwable e) {
                  out(Map.of("kind", "transportError", "type", e.getClass().getSimpleName()));
                }
              }).get(10, TimeUnit.SECONDS);
          session.subscribe("/topic/rooms/" + command.get("roomId"), new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) {
              out(Map.of("kind", "event", "at", java.time.Instant.now().toString(),
                  "event", MAPPER.readValue((byte[]) payload, Map.class)));
            }
          });
          // Allow SUBSCRIBE to reach the inbound channel before issuing SEND.
          Thread.sleep(150);
          out(Map.of("kind", "connected"));
        } else if (op.equals("activate")) {
          session.send("/app/rooms/" + command.get("roomId") + "/performances/"
              + command.get("performanceId") + "/cards/activate", new byte[0]);
          out(Map.of("kind", "sent"));
        } else if (op.equals("exit")) {
          break;
        }
      }
    } finally {
      if (session != null && session.isConnected()) session.disconnect();
      client.stop();
    }
    System.exit(0);
  }
}
