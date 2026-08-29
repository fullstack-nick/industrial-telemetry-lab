package dev.telemetrylab.simulator;

import dev.telemetrylab.contracts.ControllerReading;
import dev.telemetrylab.contracts.ControllerReadingsPage;
import dev.telemetrylab.contracts.CursorGone;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(SimulatorProperties.class)
public class ControllerHistory {
  private static final int GOOD_QUALITY_CODE = 192;
  private static final int BAD_QUALITY_CODE = 0;

  private final SimulatorProperties properties;
  private final Clock clock;
  private final Random random;
  private final Deque<ControllerReading> history = new ArrayDeque<>();
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final AtomicReference<FaultSettings> faults =
      new AtomicReference<>(FaultSettings.healthy());
  private final Counter generated;
  private final Counter duplicated;
  private String sourceEpoch;
  private long nextSequence;
  private long generationTick;

  public ControllerHistory(
      SimulatorProperties properties, Clock clock, MeterRegistry meterRegistry) {
    this.properties = properties;
    this.clock = clock;
    this.random = new Random(properties.seed());
    this.sourceEpoch =
        UUID.nameUUIDFromBytes(("source-epoch-" + properties.seed()).getBytes()).toString();
    this.generated = meterRegistry.counter("controller_observations_generated_total");
    this.duplicated = meterRegistry.counter("controller_duplicate_observations_generated_total");
    Gauge.builder("controller_history_observations", history, Deque::size)
        .description("Observations currently retained by the controller simulator")
        .register(meterRegistry);
  }

  @PostConstruct
  void seedFirstReadings() {
    generateReadings();
  }

  @Scheduled(fixedRateString = "${telemetry.simulator.production-interval-ms:5000}")
  public void generateReadings() {
    FaultSettings activeFaults = faults.get();
    Instant timestamp = clock.instant();
    lock.writeLock().lock();
    try {
      generationTick++;
      for (int zone = 1; zone <= 12; zone++) {
        double phase = generationTick / 18.0 + zone / 5.0;
        append(
            sourceTag(zone, "TEMP_PV"),
            timestamp,
            24.0 + 3.2 * Math.sin(phase),
            "degC",
            activeFaults);
        append(
            sourceTag(zone, "RH_PV"),
            timestamp,
            48.0 + 12.0 * Math.cos(phase / 1.7),
            "%",
            activeFaults);
        append(
            sourceTag(zone, "FAN_OUT"),
            timestamp,
            42.0 + 22.0 * Math.sin(phase / 1.3),
            "%",
            activeFaults);
        if (activeFaults.newUnknownTagEnabled()) {
          append(
              sourceTag(zone, "TEMP_AUX_PV"),
              timestamp,
              23.6 + 3.0 * Math.sin(phase),
              "degC",
              activeFaults);
        }
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  public ControllerReadingsPage read(String requestedEpoch, long afterSequence, int limit) {
    FaultSettings activeFaults = faults.get();
    if (!activeFaults.connectionAvailable()) {
      throw new SourceUnavailableException();
    }
    delay(activeFaults.responseDelayMs());

    lock.readLock().lock();
    try {
      boolean sameEpoch = requestedEpoch == null || requestedEpoch.equals(sourceEpoch);
      long effectiveAfter = sameEpoch ? afterSequence : 0;
      long earliest = history.isEmpty() ? nextSequence : history.getFirst().sequence();
      long latest = history.isEmpty() ? nextSequence : history.getLast().sequence();
      if (sameEpoch && effectiveAfter > 0 && effectiveAfter < earliest - 1) {
        throw new CursorExpiredException(new CursorGone(sourceEpoch, earliest, latest));
      }

      List<ControllerReading> result = new ArrayList<>(limit);
      for (ControllerReading reading : history) {
        if (reading.sequence() > effectiveAfter) {
          result.add(reading);
          if (result.size() == limit) {
            break;
          }
        }
      }
      if (result.size() > 1 && random.nextDouble() < activeFaults.outOfOrderRate()) {
        Collections.swap(result, result.size() - 1, result.size() - 2);
      }
      long next =
          result.stream().mapToLong(ControllerReading::sequence).max().orElse(effectiveAfter);
      return new ControllerReadingsPage(sourceEpoch, next, result);
    } finally {
      lock.readLock().unlock();
    }
  }

  public FaultSettings faults() {
    return faults.get();
  }

  public FaultSettings updateFaults(FaultPatch patch) {
    return faults.updateAndGet(patch::applyTo);
  }

  public String restartSource() {
    lock.writeLock().lock();
    try {
      sourceEpoch = UUID.randomUUID().toString();
      nextSequence = 0;
      generationTick = 0;
      history.clear();
      generateReadingsWithoutLock();
      return sourceEpoch;
    } finally {
      lock.writeLock().unlock();
    }
  }

  private void generateReadingsWithoutLock() {
    FaultSettings activeFaults = faults.get();
    Instant timestamp = clock.instant();
    generationTick++;
    for (int zone = 1; zone <= 12; zone++) {
      double phase = generationTick / 18.0 + zone / 5.0;
      append(
          sourceTag(zone, "TEMP_PV"),
          timestamp,
          24.0 + 3.2 * Math.sin(phase),
          "degC",
          activeFaults);
      append(
          sourceTag(zone, "RH_PV"),
          timestamp,
          48.0 + 12.0 * Math.cos(phase / 1.7),
          "%",
          activeFaults);
      append(
          sourceTag(zone, "FAN_OUT"),
          timestamp,
          42.0 + 22.0 * Math.sin(phase / 1.3),
          "%",
          activeFaults);
    }
  }

  private void append(
      String tag,
      Instant observedAt,
      double nominalValue,
      String nominalUnit,
      FaultSettings activeFaults) {
    long sequence = ++nextSequence;
    double noise = (random.nextDouble() - 0.5) * 0.3;
    Instant effectiveTimestamp =
        random.nextDouble() < activeFaults.futureTimestampRate()
            ? observedAt.plus(Duration.ofMinutes(2))
            : observedAt;
    String effectiveUnit =
        random.nextDouble() < activeFaults.invalidUnitRate() ? "invalid-unit" : nominalUnit;
    int qualityCode =
        random.nextDouble() < activeFaults.badQualityRate() ? BAD_QUALITY_CODE : GOOD_QUALITY_CODE;
    ControllerReading reading =
        new ControllerReading(
            sequence,
            tag,
            effectiveTimestamp,
            Math.round((nominalValue + noise) * 100.0) / 100.0,
            effectiveUnit,
            qualityCode);
    retain(reading);
    generated.increment();
    if (random.nextDouble() < activeFaults.duplicateRate()) {
      retain(reading);
      duplicated.increment();
    }
  }

  private void retain(ControllerReading reading) {
    history.addLast(reading);
    while (history.size() > properties.historyCapacity()) {
      history.removeFirst();
    }
  }

  private static String sourceTag(int zone, String signal) {
    return "CTRL_A.ZONE[%02d].%s".formatted(zone, signal);
  }

  private static void delay(long delayMs) {
    if (delayMs == 0) {
      return;
    }
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new SourceUnavailableException();
    }
  }

  static final class SourceUnavailableException extends RuntimeException {}
}
