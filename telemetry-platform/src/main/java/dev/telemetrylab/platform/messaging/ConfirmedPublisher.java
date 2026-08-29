package dev.telemetrylab.platform.messaging;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ConfirmedPublisher {
  private final RabbitTemplate rabbitTemplate;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RabbitTemplate is an application-scoped Spring infrastructure bean")
  public ConfirmedPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  public void publish(
      String routingKey, String json, Map<String, Object> headers, Duration timeout) {
    publishTo(RabbitTopology.EXCHANGE, routingKey, json, headers, timeout);
  }

  public void publishTo(
      String exchange,
      String routingKey,
      String json,
      Map<String, Object> headers,
      Duration timeout) {
    var builder =
        MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
            .setContentType("application/json")
            .setContentEncoding(StandardCharsets.UTF_8.name())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    headers.forEach(builder::setHeader);
    Message message = builder.build();
    CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
    rabbitTemplate.send(exchange, routingKey, message, correlation);
    try {
      CorrelationData.Confirm confirm =
          correlation.getFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!confirm.ack()) {
        throw new IllegalStateException(
            "Broker negatively acknowledged publish: " + confirm.reason());
      }
      if (correlation.getReturned() != null) {
        throw new IllegalStateException(
            "Broker returned unroutable publish: " + correlation.getReturned().getReplyText());
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for broker confirmation", exception);
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException exception) {
      throw new IllegalStateException("Broker confirmation was not received", exception);
    }
  }
}
