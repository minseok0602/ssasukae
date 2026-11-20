package experiment;

import java.io.*;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.ObjectMapper;

/** Headless protocol probe: automatic reconnect, subscribe-first snapshot hydration, card state model. */
public class FailoverProbe {
  private static final ObjectMapper JSON=new ObjectMapper();
  private final WebSocketStompClient client=new WebSocketStompClient(new StandardWebSocketClient());
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
  private final AtomicInteger epoch=new AtomicInteger();
  private final AtomicBoolean queued=new AtomicBoolean();
  private volatile boolean enabled=false;
  private volatile StompSession session;
  private volatile String base,token,node="";
  private volatile long roomId,performanceId,reconnectDelay;
  private volatile int connection;
  private volatile Map<?,?> activeCard,pendingCard;

  private static synchronized void out(Map<String,Object> item) {
    var result=new LinkedHashMap<String,Object>(item);
    result.put("at",Instant.now().toString());
    System.out.println("LBPROBE:"+JSON.writeValueAsString(result)); System.out.flush();
  }
  private void queue(int generation,long delay) {
    if (!enabled || epoch.get()!=generation || !queued.compareAndSet(false,true)) return;
    executor.schedule(() -> {
      queued.set(false);
      if (enabled && epoch.get()==generation) connect(generation);
    },delay,TimeUnit.MILLISECONDS);
  }
  private StompFrameHandler handler(java.util.function.Consumer<Map<?,?>> action) {
    return new StompFrameHandler() {
      public Type getPayloadType(StompHeaders h) { return byte[].class; }
      public void handleFrame(StompHeaders h,Object payload) { action.accept(JSON.readValue((byte[])payload,Map.class)); }
    };
  }
  private void connect(int generation) {
    StompSession connected=null;
    try {
      StompHeaders headers=new StompHeaders(); headers.add("Authorization","Bearer "+token);
      out(Map.of("kind","connecting","roomId",roomId));
      connected=client.connectAsync(base.replace("http://","ws://")+"/ws",new WebSocketHttpHeaders(),headers,
          new StompSessionHandlerAdapter() {
            @Override public void handleTransportError(StompSession s,Throwable error) {
              if (enabled && epoch.get()==generation && s==session) {
                out(Map.of("kind","disconnected","roomId",roomId,"node",node,
                    "connection",connection,"errorType",error.getClass().getSimpleName()));
                queue(generation,reconnectDelay);
              }
            }
          }).get(8,TimeUnit.SECONDS);
      if (!enabled || epoch.get()!=generation) { connected.disconnect(); return; }
      session=connected; int index=++connection;
      out(Map.of("kind","handshake","roomId",roomId,"connection",index));
      CountDownLatch identityReady=new CountDownLatch(1),roomReady=new CountDownLatch(1);
      AtomicReference<Map<?,?>> identity=new AtomicReference<>();
      connected.subscribe("/topic/rooms/"+roomId,handler(event -> {
        if ("EXPERIMENT_ROOM_PROBE".equals(event.get("eventType"))) { roomReady.countDown(); return; }
        applyEvent(event);
        out(Map.of("kind","event","roomId",roomId,"connection",index,"event",event));
      }));
      connected.subscribe("/user/queue/lb-probe",handler(value -> { identity.set(value);identityReady.countDown(); }));
      // Same ordering as useRoomSocket: subscribe, then fetch and hydrate the current snapshot.
      HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/experiment/lb/snapshot/"+roomId))
          .header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(4)).GET().build();
      HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
      if (response.statusCode()!=200) throw new IllegalStateException("Snapshot status "+response.statusCode());
      Map<?,?> envelope=JSON.readValue(response.body(),Map.class);
      Map<?,?> snapshot=(Map<?,?>)envelope.get("snapshot");
      hydrate(snapshot);
      // Probe both the authenticated user queue and the actual room topic after reconnect.
      long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
      while ((identityReady.getCount()>0 || roomReady.getCount()>0) && System.nanoTime()<until) {
        connected.send("/app/experiment/identity",new byte[0]);
        connected.send("/app/rooms/"+roomId+"/lb-probe",new byte[0]);
        Thread.sleep(100);
      }
      if (identity.get()==null || roomReady.getCount()>0) throw new IllegalStateException("Subscription roundtrip failed");
      node=identity.get().get("instance").toString();
      out(Map.of("kind","connected","roomId",roomId,"connection",index,"wsNode",node,
          "snapshotNode",envelope.get("instance"),"sessionId",identity.get().get("sessionId"),
          "authenticatedUser",identity.get().get("userId"),"roomSubscriptionProven",true,"snapshot",snapshot));
    } catch (Exception error) {
      if (connected!=null && connected.isConnected()) connected.disconnect();
      if (enabled && epoch.get()==generation) {
        out(Map.of("kind","retry","roomId",roomId,"errorType",error.getClass().getSimpleName()));
        queue(generation,reconnectDelay);
      }
    }
  }
  private synchronized void hydrate(Map<?,?> snapshot) {
    Map<?,?> card=(Map<?,?>)snapshot.get("activeCard");
    activeCard=card!=null && "ACTIVE".equals(card.get("roomCardStatus"))?card:null;
    pendingCard=card!=null && "PENDING".equals(card.get("roomCardStatus"))?card:null;
  }
  private synchronized void applyEvent(Map<?,?> event) {
    String type=event.get("eventType").toString();
    Map<?,?> payload=(Map<?,?>)event.get("payload");
    if (type.equals("CARD_ACTIVATION_SCHEDULED")) pendingCard=payload;
    if (type.equals("CARD_EFFECT_STARTED")) { activeCard=payload;pendingCard=null; }
    if (type.equals("CARD_EFFECT_ENDED") || type.equals("CARD_ACTIVATION_CANCELLED")) { activeCard=null;pendingCard=null; }
  }
  private void stop() {
    enabled=false;epoch.incrementAndGet();queued.set(false);
    if (session!=null && session.isConnected()) session.disconnect();
  }
  private void run() throws Exception {
    client.setDefaultHeartbeat(new long[]{0,0});
    try (var input=new BufferedReader(new InputStreamReader(System.in))) {
      String line;
      while ((line=input.readLine())!=null) {
        Map<?,?> command=JSON.readValue(line,Map.class);
        switch (command.get("op").toString()) {
          case "start" -> {
            stop();base=command.get("base").toString();token=command.get("token").toString();
            roomId=((Number)command.get("roomId")).longValue();
            performanceId=((Number)command.get("performanceId")).longValue();
            reconnectDelay=((Number)command.get("reconnectDelayMs")).longValue();
            connection=0;node="";activeCard=null;pendingCard=null;enabled=true;queue(epoch.get(),0);
          }
          case "activate" -> {
            session.send("/app/rooms/"+roomId+"/performances/"+performanceId+"/cards/activate",new byte[0]);
            out(Map.of("kind","sent","roomId",roomId));
          }
          case "status" -> {
            var result=new LinkedHashMap<String,Object>();result.put("kind","status");result.put("roomId",roomId);
            result.put("connection",connection);result.put("node",node);
            result.put("activeCard",activeCard);result.put("pendingCard",pendingCard);out(result);
          }
          case "stop" -> { stop();out(Map.of("kind","stopped")); }
          case "exit" -> { return; }
        }
      }
    } finally { stop();executor.shutdownNow();client.stop(); }
  }
  public static void main(String[] args) throws Exception { new FailoverProbe().run();System.exit(0); }
}
