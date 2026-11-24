package experiment;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Runs the repository's existing card regression tests without a Gradle download. */
public class UnitChecks {
  public static void main(String[] args) throws Exception {
    var listener = new SummaryGeneratingListener();
    var launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(listener);
    launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(
        DiscoverySelectors.selectClass("com.ssafy.ssasukae.domain.card.service.CardServiceTest")).build());
    try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(Path.of("results/unit-tests.txt")))) {
      listener.getSummary().printTo(writer);
      listener.getSummary().printFailuresTo(writer);
    }
    listener.getSummary().printTo(new PrintWriter(System.out));
    listener.getSummary().printFailuresTo(new PrintWriter(System.out));
    if (listener.getSummary().getTestsSucceededCount() == 0 || listener.getSummary().getTotalFailureCount() > 0)
      System.exit(1);
  }
}
