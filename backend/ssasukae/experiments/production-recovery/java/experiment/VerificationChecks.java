package experiment;

import java.io.PrintWriter;
import java.nio.file.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public class VerificationChecks {
  public static void main(String[] args) throws Exception {
    var request=LauncherDiscoveryRequestBuilder.request().selectors(
        DiscoverySelectors.selectClass("com.ssafy.ssasukae.domain.card.service.CardServiceTest"),
        DiscoverySelectors.selectClass("com.ssafy.ssasukae.domain.card.service.CardRecoveryTest"),
        DiscoverySelectors.selectClass("com.ssafy.ssasukae.domain.card.recovery.CardRecoveryWorkerTest"),
        DiscoverySelectors.selectClass("com.ssafy.ssasukae.domain.card.redis.RedisCardStateStoreIntegrationTest"),
        DiscoverySelectors.selectClass("com.ssafy.ssasukae.global.websocket.relay.RedisWebSocketEventRelayIntegrationTest")).build();
    var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
    launcher.registerTestExecutionListeners(listener);launcher.execute(request);
    String file=System.getenv("REDIS_INTEGRATION_HOST")==null?"unit-tests.txt":"redis-tests.txt";
    try(var writer=new PrintWriter(Files.newBufferedWriter(Path.of("results",file)))) {
      listener.getSummary().printTo(writer);listener.getSummary().printFailuresTo(writer);
    }
    listener.getSummary().printTo(new PrintWriter(System.out));
    listener.getSummary().printFailuresTo(new PrintWriter(System.out));
    if(listener.getSummary().getTestsSucceededCount()==0||listener.getSummary().getTotalFailureCount()>0)System.exit(1);
  }
}
