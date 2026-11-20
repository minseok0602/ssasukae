package experiment;

import java.io.*;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.ObjectMapper;

public class CrossNodeProbe {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static synchronized void out(Map<String,Object> value) {
    var item=new java.util.LinkedHashMap<String,Object>(value);item.put("at",Instant.now().toString());
    System.out.println("CROSS:"+JSON.writeValueAsString(item));System.out.flush();
  }
  private static StompFrameHandler handler(java.util.function.Consumer<Map<?,?>> action) {
    return new StompFrameHandler() {
      public Type getPayloadType(StompHeaders h){return byte[].class;}
      public void handleFrame(StompHeaders h,Object p){action.accept(JSON.readValue((byte[])p,Map.class));}
    };
  }
  public static void main(String[] args) throws Exception {
    var client=new WebSocketStompClient(new StandardWebSocketClient());client.setDefaultHeartbeat(new long[]{0,0});
    StompSession session=null;
    try(var reader=new BufferedReader(new InputStreamReader(System.in))) {
      Map<?,?> input=JSON.readValue(reader.readLine(),Map.class);
      var connect=new StompHeaders();connect.add("Authorization","Bearer "+input.get("token"));
      session=client.connectAsync(input.get("url").toString(),new WebSocketHttpHeaders(),connect,
          new StompSessionHandlerAdapter(){
            @Override public void handleTransportError(StompSession s,Throwable e){out(Map.of("kind","transportError","type",e.getClass().getSimpleName()));}
          }).get(10,TimeUnit.SECONDS);
      CountDownLatch identity=new CountDownLatch(1),room=new CountDownLatch(1);
      session.subscribe("/topic/rooms/"+input.get("roomId"),handler(value->{
        if("EXPERIMENT_ROOM_PROBE".equals(value.get("eventType")))room.countDown();
        else out(Map.of("kind","event","channel","room","event",value));
      }));
      session.subscribe("/user/queue/relay-test",handler(value->out(Map.of("kind","event","channel","private","event",value))));
      session.subscribe("/user/queue/lb-probe",handler(value->{out(Map.of("kind","identity","value",value));identity.countDown();}));
      for(int i=0;i<30&&(room.getCount()>0||identity.getCount()>0);i++) {
        session.send("/app/experiment/identity",new byte[0]);
        session.send("/app/rooms/"+input.get("roomId")+"/lb-probe",new byte[0]);Thread.sleep(100);
      }
      if(room.getCount()>0||identity.getCount()>0)throw new IllegalStateException("Subscription proof failed");
      out(Map.of("kind","ready"));
      while(reader.readLine()!=null)break;
    } finally {if(session!=null&&session.isConnected())session.disconnect();client.stop();}
    System.exit(0);
  }
}
