package dev.telemetrylab.platform.messaging;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression("'${telemetry.platform.mode:gateway}' != 'migrate'")
public class RabbitTopology {
  public static final String EXCHANGE = "telemetry.exchange";
  public static final String DEAD_LETTER_EXCHANGE = "telemetry.dead-letter.exchange";
  public static final String MAIN = "telemetry.main";
  public static final String RETRY_SHORT = "telemetry.retry.short";
  public static final String RETRY_MEDIUM = "telemetry.retry.medium";
  public static final String RETRY_LONG = "telemetry.retry.long";
  public static final String DEAD_LETTER = "telemetry.dead-letter";

  private static final long MAX_QUEUE_BYTES = 52_428_800L;

  @Bean
  DirectExchange telemetryExchange() {
    return new DirectExchange(EXCHANGE, true, false);
  }

  @Bean
  DirectExchange telemetryDeadLetterExchange() {
    return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
  }

  @Bean
  Queue telemetryMainQueue() {
    return quorumQueue(
        MAIN,
        Map.of(
            "x-dead-letter-exchange",
            DEAD_LETTER_EXCHANGE,
            "x-dead-letter-routing-key",
            DEAD_LETTER,
            "x-delivery-limit",
            5));
  }

  @Bean
  Queue telemetryRetryShortQueue() {
    return retryQueue(RETRY_SHORT, 5_000L);
  }

  @Bean
  Queue telemetryRetryMediumQueue() {
    return retryQueue(RETRY_MEDIUM, 30_000L);
  }

  @Bean
  Queue telemetryRetryLongQueue() {
    return retryQueue(RETRY_LONG, 120_000L);
  }

  @Bean
  Queue telemetryDeadLetterQueue() {
    return quorumQueue(DEAD_LETTER, Map.of());
  }

  @Bean
  Binding mainBinding(Queue telemetryMainQueue, DirectExchange telemetryExchange) {
    return BindingBuilder.bind(telemetryMainQueue).to(telemetryExchange).with(MAIN);
  }

  @Bean
  Binding shortRetryBinding(Queue telemetryRetryShortQueue, DirectExchange telemetryExchange) {
    return BindingBuilder.bind(telemetryRetryShortQueue).to(telemetryExchange).with(RETRY_SHORT);
  }

  @Bean
  Binding mediumRetryBinding(Queue telemetryRetryMediumQueue, DirectExchange telemetryExchange) {
    return BindingBuilder.bind(telemetryRetryMediumQueue).to(telemetryExchange).with(RETRY_MEDIUM);
  }

  @Bean
  Binding longRetryBinding(Queue telemetryRetryLongQueue, DirectExchange telemetryExchange) {
    return BindingBuilder.bind(telemetryRetryLongQueue).to(telemetryExchange).with(RETRY_LONG);
  }

  @Bean
  Binding deadLetterBinding(
      Queue telemetryDeadLetterQueue, DirectExchange telemetryDeadLetterExchange) {
    return BindingBuilder.bind(telemetryDeadLetterQueue)
        .to(telemetryDeadLetterExchange)
        .with(DEAD_LETTER);
  }

  @Bean
  RabbitTemplateCustomizer persistentMandatoryMessages() {
    return new RabbitTemplateCustomizer();
  }

  private static Queue retryQueue(String name, long timeToLiveMs) {
    return quorumQueue(
        name,
        Map.of(
            "x-message-ttl",
            timeToLiveMs,
            "x-dead-letter-exchange",
            EXCHANGE,
            "x-dead-letter-routing-key",
            MAIN,
            "x-delivery-limit",
            5));
  }

  private static Queue quorumQueue(String name, Map<String, Object> extraArguments) {
    java.util.HashMap<String, Object> arguments = new java.util.HashMap<>(extraArguments);
    arguments.put("x-queue-type", "quorum");
    arguments.put("x-overflow", "reject-publish");
    arguments.put("x-max-length-bytes", MAX_QUEUE_BYTES);
    return new Queue(name, true, false, false, arguments);
  }

  public static final class RabbitTemplateCustomizer {
    RabbitTemplateCustomizer() {}

    @org.springframework.beans.factory.annotation.Autowired
    void customize(RabbitTemplate template) {
      template.setMandatory(true);
      template.setBeforePublishPostProcessors(
          message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
          });
    }
  }
}
