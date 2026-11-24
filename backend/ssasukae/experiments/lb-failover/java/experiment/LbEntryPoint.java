package experiment;

import org.springframework.boot.SpringApplication;

/** Adds read-only observation and the real room snapshot service to the existing experiment. */
public class LbEntryPoint {
  public static void main(String[] args) {
    SpringApplication.run(new Class<?>[] {CardExperimentApplication.class, LbWiring.class, LbApi.class}, args);
    System.out.println("LB_APPLICATION_READY instance="+System.getenv("INSTANCE"));
  }
}
