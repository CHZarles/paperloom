package com.yizhaoqi.smartpai.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class KafkaConfigTest {

    @Test
    void listenerFactoryUsesConfiguredAutoStartupFlag() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "paperProcessingDltTopic", "paper-processing-dlt");
        ReflectionTestUtils.setField(config, "listenerAutoStartup", false);
        ReflectionTestUtils.setField(config, "listenerConcurrency", 3);

        @SuppressWarnings("unchecked")
        ConsumerFactory<String, Object> consumerFactory = mock(ConsumerFactory.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                config.kafkaListenerContainerFactory(consumerFactory, kafkaTemplate);

        assertEquals(false, ReflectionTestUtils.getField(factory, "autoStartup"));
        assertEquals(3, ReflectionTestUtils.getField(factory, "concurrency"));
    }

    @Test
    void consumerFactoryUsesLongRunningPaperProcessingSettings() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "127.0.0.1:9092");
        ReflectionTestUtils.setField(config, "paperProcessingGroupId", "paper-processing-group");
        ReflectionTestUtils.setField(config, "trustedPackages", "*");
        ReflectionTestUtils.setField(config, "maxPollRecords", 1);
        ReflectionTestUtils.setField(config, "maxPollIntervalMs", 7_200_000);

        @SuppressWarnings("unchecked")
        DefaultKafkaConsumerFactory<String, Object> factory =
                (DefaultKafkaConsumerFactory<String, Object>) config.consumerFactory();

        assertEquals(false, factory.getConfigurationProperties().get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertEquals(1, factory.getConfigurationProperties().get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
        assertEquals(7_200_000, factory.getConfigurationProperties().get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
    }
}
