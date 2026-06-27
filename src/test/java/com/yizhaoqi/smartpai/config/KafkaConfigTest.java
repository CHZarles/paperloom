package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
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

        @SuppressWarnings("unchecked")
        ConsumerFactory<String, Object> consumerFactory = mock(ConsumerFactory.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                config.kafkaListenerContainerFactory(consumerFactory, kafkaTemplate);

        assertEquals(false, ReflectionTestUtils.getField(factory, "autoStartup"));
    }
}
