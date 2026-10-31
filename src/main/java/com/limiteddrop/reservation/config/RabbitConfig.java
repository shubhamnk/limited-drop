package com.limiteddrop.reservation.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnBean(ConnectionFactory.class)
public class RabbitConfig {

    public static final String EXCHANGE = "drop.events";
    public static final String NOTIFICATION_QUEUE = "drop.notification.queue";
    public static final String ANALYTICS_QUEUE = "drop.analytics.queue";
    public static final String AUDIT_QUEUE = "drop.audit.queue";
    public static final String DLQ = "drop.events.dlq";
    public static final String ROUTING_PATTERN = "drop.hold.*";

    @Bean
    TopicExchange dropEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue dropEventsDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }

    @Bean
    Queue analyticsQueue() {
        return QueueBuilder.durable(ANALYTICS_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }

    @Bean
    Queue auditQueue() {
        return QueueBuilder.durable(AUDIT_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }

    @Bean
    Binding notificationBinding(Queue notificationQueue, TopicExchange dropEventsExchange) {
        return BindingBuilder.bind(notificationQueue).to(dropEventsExchange).with(ROUTING_PATTERN);
    }

    @Bean
    Binding analyticsBinding(Queue analyticsQueue, TopicExchange dropEventsExchange) {
        return BindingBuilder.bind(analyticsQueue).to(dropEventsExchange).with(ROUTING_PATTERN);
    }

    @Bean
    Binding auditBinding(Queue auditQueue, TopicExchange dropEventsExchange) {
        return BindingBuilder.bind(auditQueue).to(dropEventsExchange).with(ROUTING_PATTERN);
    }

    @Bean
    Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        if (connectionFactory instanceof CachingConnectionFactory caching) {
            caching.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            caching.setPublisherReturns(true);
        }
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        template.setMandatory(true);
        template.setExchange(EXCHANGE);
        return template;
    }
}
