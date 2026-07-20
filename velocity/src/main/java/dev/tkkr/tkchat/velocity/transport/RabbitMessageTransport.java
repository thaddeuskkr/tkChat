package dev.tkkr.tkchat.velocity.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.service.MessageTransport;
import dev.tkkr.tkchat.velocity.config.AppConfig;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class RabbitMessageTransport implements MessageTransport {
    private final AppConfig.RabbitMq config;
    private final String instanceId;
    private final Executor executor;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private Connection connection;
    private Channel publisher;
    private Channel consumer;

    public RabbitMessageTransport(AppConfig.RabbitMq config, String instanceId, Executor executor) {
        this.config = config;
        this.instanceId = instanceId;
        this.executor = executor;
    }

    @Override
    public void start(Consumer<ApprovedMessage> listener) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(config.uri);
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);
        factory.setConnectionTimeout(10_000);
        factory.setRequestedHeartbeat(30);
        connection = factory.newConnection("tkChat-" + instanceId);
        publisher = connection.createChannel();
        consumer = connection.createChannel();

        publisher.exchangeDeclare(config.exchange, "topic", true);
        consumer.exchangeDeclare(config.exchange, "topic", true);
        publisher.confirmSelect();

        String queue = config.queuePrefix + "." + instanceId;
        consumer.queueDeclare(queue, true, false, false, Map.of(
                "x-message-ttl", 60_000,
                "x-queue-type", "classic"
        ));
        consumer.queueBind(queue, config.exchange, "chat.#");
        consumer.queueBind(queue, config.exchange, "network.#");
        consumer.basicQos(100);

        DeliverCallback callback = (tag, delivery) -> {
            long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            try {
                ApprovedMessage message = mapper.readValue(delivery.getBody(), ApprovedMessage.class);
                listener.accept(message);
                consumer.basicAck(deliveryTag, false);
            } catch (Exception error) {
                consumer.basicNack(deliveryTag, false, false);
            }
        };
        consumer.basicConsume(queue, false, callback, tag -> {
        });
    }

    @Override
    public CompletionStage<Void> publish(ApprovedMessage message) {
        return CompletableFuture.runAsync(() -> {
            try {
                byte[] body = mapper.writeValueAsBytes(message);
                String routingKey = switch (message.routeKind()) {
                    case CHANNEL -> "chat.channel." + message.channelId();
                    case GROUP -> "chat.group." + message.routeId();
                    case DIRECT -> "chat.direct." + message.routeId();
                    case BROADCAST -> "network.broadcast";
                    case CHAT_CLEAR -> "network.chat_clear." + message.channelId();
                };
                AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                        .contentType("application/json")
                        .contentEncoding("utf-8")
                        .deliveryMode(2)
                        .messageId(message.messageId().toString())
                        .timestamp(java.util.Date.from(message.createdAt()))
                        .build();
                synchronized (this) {
                    publisher.basicPublish(config.exchange, routingKey, true, properties, body);
                    publisher.waitForConfirmsOrDie(config.publishConfirmTimeoutMillis);
                }
            } catch (IOException | InterruptedException | TimeoutException error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("Unable to publish chat message", error);
            }
        }, executor);
    }

    @Override
    public void close() {
        closeQuietly(consumer);
        closeQuietly(publisher);
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException | TimeoutException ignored) {
        }
    }
}
