package dev.telemetrylab.platform.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class QualityCatalog {
  private static final Pattern SAFE_VERSION =
      Pattern.compile("quality-rules-[0-9]+\\.[0-9]+\\.[0-9]+");

  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
  private final ConcurrentHashMap<String, QualityRules> cache = new ConcurrentHashMap<>();

  public QualityRules get(String version) {
    return cache.computeIfAbsent(version, this::load);
  }

  public boolean exists(String version) {
    try {
      get(version);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private QualityRules load(String version) {
    if (version == null || !SAFE_VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("Invalid quality-rules version: " + version);
    }
    ClassPathResource resource = new ClassPathResource("config/quality-rules/" + version + ".yaml");
    try (InputStream input = resource.getInputStream()) {
      QualityRules rules = yaml.readValue(input, QualityRules.class);
      if (!version.equals(rules.qualityRulesVersion())) {
        throw new IllegalStateException("Quality-rules filename and version disagree");
      }
      return rules;
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "Quality-rules version is unavailable: " + version, exception);
    }
  }

  public record QualityRules(
      String qualityRulesVersion,
      long futureTimestampFlagSeconds,
      long futureTimestampRejectSeconds,
      long lateArrivalFlagSeconds,
      int goodSourceQualityCode,
      Map<String, ValueRange> ranges) {
    public QualityRules {
      ranges = Map.copyOf(ranges);
    }
  }

  public record ValueRange(double minimum, double maximum) {}
}
