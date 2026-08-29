package dev.telemetrylab.platform.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class MappingCatalog {
  private static final Pattern SAFE_VERSION =
      Pattern.compile("controller-a-mapping-[0-9]+\\.[0-9]+\\.[0-9]+");

  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
  private final ConcurrentHashMap<String, CompiledMapping> cache = new ConcurrentHashMap<>();

  public MappingResult map(String sourceTag, String mappingVersion) {
    CompiledMapping mapping = cache.computeIfAbsent(mappingVersion, this::load);
    for (CompiledRule rule : mapping.rules()) {
      Matcher matcher = rule.pattern().matcher(sourceTag);
      if (matcher.matches()) {
        String assetId = rule.assetIdTemplate().replace("${zone}", matcher.group("zone"));
        return new MappingResult(
            mapping.mappingVersion(),
            assetId,
            rule.signalId(),
            rule.expectedSourceUnit(),
            rule.canonicalUnit());
      }
    }
    throw new UnknownSourceTagException(sourceTag, mappingVersion);
  }

  public boolean exists(String mappingVersion) {
    try {
      cache.computeIfAbsent(mappingVersion, this::load);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private CompiledMapping load(String version) {
    if (version == null || !SAFE_VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("Invalid mapping version: " + version);
    }
    ClassPathResource resource = new ClassPathResource("config/mappings/" + version + ".yaml");
    try (InputStream input = resource.getInputStream()) {
      MappingFile file = yaml.readValue(input, MappingFile.class);
      if (!version.equals(file.mappingVersion())) {
        throw new IllegalStateException("Mapping filename and mappingVersion disagree");
      }
      List<CompiledRule> rules =
          file.rules().stream()
              .map(
                  rule ->
                      new CompiledRule(
                          Pattern.compile(rule.tagPattern()),
                          rule.assetIdTemplate(),
                          rule.signalId(),
                          rule.expectedSourceUnit(),
                          rule.canonicalUnit()))
              .toList();
      return new CompiledMapping(file.mappingVersion(), rules);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Mapping version is unavailable: " + version, exception);
    }
  }

  public record MappingResult(
      String mappingVersion,
      String assetId,
      String signalId,
      String expectedSourceUnit,
      String canonicalUnit) {}

  private record MappingFile(String mappingVersion, List<MappingRule> rules) {}

  private record MappingRule(
      String tagPattern,
      String assetIdTemplate,
      String signalId,
      String expectedSourceUnit,
      String canonicalUnit) {}

  private record CompiledMapping(String mappingVersion, List<CompiledRule> rules) {}

  private record CompiledRule(
      Pattern pattern,
      String assetIdTemplate,
      String signalId,
      String expectedSourceUnit,
      String canonicalUnit) {}

  public static final class UnknownSourceTagException extends RuntimeException {
    public UnknownSourceTagException(String sourceTag, String mappingVersion) {
      super("No rule in " + mappingVersion + " recognizes source tag " + sourceTag);
    }
  }
}
