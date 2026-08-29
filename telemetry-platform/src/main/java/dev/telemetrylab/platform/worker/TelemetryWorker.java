package dev.telemetrylab.platform.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import dev.telemetrylab.contracts.BatchReference;
import dev.telemetrylab.platform.messaging.ConfirmedPublisher;
import dev.telemetrylab.platform.messaging.RabbitTopology;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "telemetry.platform.mode", havingValue = "worker")
class TelemetryWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(TelemetryWorker.class);
  private static final int MAXIMUM_ATTEMPTS = 5;

  private final ObjectMapper objectMapper;
  private final ProcessingService processingService;
  private final ConfirmedPublisher publisher;

  TelemetryWorker(
      ObjectMapper objectMapper,
      ProcessingService processingService,
      ConfirmedPublisher publisher) {
    this.objectMapper = objectMapper;
    this.processingService = processingService;
    this.publisher = publisher;
  }

  @RabbitListener(queues = RabbitTopology.MAIN)
  void consume(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    int currentAttempt = currentAttempt(message);
    String json = new String(message.getBody(), StandardCharsets.UTF_8);
    BatchReference reference = null;
    try {
      reference = objectMapper.readValue(json, BatchReference.class);
      processingService.process(reference);
      channel.basicAck(deliveryTag, false);
    } catch (Exception exception) {
      RuntimeException processingFailure =
          exception instanceof RuntimeException runtime
              ? runtime
              : new IllegalStateException(
                  "Queue message is not a valid batch reference", exception);
      int nextAttempt = currentAttempt + 1;
      processingService.recordFailure(reference, nextAttempt, processingFailure);
      try {
        Map<String, Object> headers = copyTraceHeaders(message);
        headers.put("x-processing-attempt", nextAttempt);
        String routing =
            nextAttempt >= MAXIMUM_ATTEMPTS
                ? RabbitTopology.DEAD_LETTER
                : retryRouting(nextAttempt);
        String exchange =
            nextAttempt >= MAXIMUM_ATTEMPTS
                ? RabbitTopology.DEAD_LETTER_EXCHANGE
                : RabbitTopology.EXCHANGE;
        publisher.publishTo(exchange, routing, json, headers, Duration.ofSeconds(10));
        channel.basicAck(deliveryTag, false);
        LOGGER.warn(
            "Processing failed; reference republished safely; batchId={} attempt={} destination={}"
                + " errorType={}",
            reference == null ? null : reference.batchId(),
            nextAttempt,
            routing,
            exception.getClass().getSimpleName());
      } catch (RuntimeException publishFailure) {
        channel.basicNack(deliveryTag, false, true);
        LOGGER.error("Retry publication failed; original delivery was requeued", publishFailure);
      }
    }
  }

  private static int currentAttempt(Message message) {
    Object value = message.getMessageProperties().getHeader("x-processing-attempt");
    return value instanceof Number number ? number.intValue() : 0;
  }

  private static String retryRouting(int attempt) {
    return switch (attempt) {
      case 1 -> RabbitTopology.RETRY_SHORT;
      case 2 -> RabbitTopology.RETRY_MEDIUM;
      default -> RabbitTopology.RETRY_LONG;
    };
  }

  private static Map<String, Object> copyTraceHeaders(Message message) {
    Map<String, Object> headers = new HashMap<>();
    Object traceParent = message.getMessageProperties().getHeader("traceparent");
    if (traceParent != null) {
      headers.put("traceparent", traceParent);
    }
    return headers;
  }
}
