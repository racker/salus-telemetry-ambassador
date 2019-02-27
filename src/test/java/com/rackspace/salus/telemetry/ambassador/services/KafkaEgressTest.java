/*
 * Copyright 2019 Rackspace US, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rackspace.salus.telemetry.ambassador.services;

import static org.junit.Assert.assertThat;
import static org.springframework.kafka.test.hamcrest.KafkaMatchers.hasKey;
import static org.springframework.kafka.test.hamcrest.KafkaMatchers.hasValue;
import static org.springframework.kafka.test.utils.KafkaTestUtils.getSingleRecord;

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.telemetry.messaging.KafkaMessageType;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
// Test with a "real", but embedded Kafka instance, gives us the EmbeddedKafkaBroker to autowire
@EmbeddedKafka(
    // We're only needing to test Kafka serializing interactions, so keep partitioning simple
    partitions = 1,
    // use some non-default topics to test via
    topics = {
        KafkaEgressTest.TOPIC_METRICS
    })
// Using @SpringBootTest mainly so we can get the standard properties binding bootstrap processing
// and the properties source. The loading and binding of {@link KafkaTopicProperties} is one of
// the main things we're testing in this suite.
@SpringBootTest(
    // tell Spring Boot Kafka auto-config about the embedded kafka endpoints
    properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    // slice our unit test app context down to just these specific pieces
    classes = {
        // ...the service to test
        KafkaEgress.class,
        // ...use standard Sprint Boot kafka auto-config to give us KafkaTemplate, etc
        KafkaAutoConfiguration.class,
        // ...and our additional test config
        KafkaEgressTest.TestConfig.class
    }
)
public class KafkaEgressTest {

  public static final String TOPIC_METRICS = "test.metrics.json";

  // Declare our own unit test Spring config
  @Configuration
  public static class TestConfig {

    // Adjust our standard topic properties to point metrics at our test topic
    @Bean
    public KafkaTopicProperties kafkaTopicProperties() {
      final KafkaTopicProperties properties = new KafkaTopicProperties();
      properties.setMetrics(TOPIC_METRICS);
      return properties;
    }
  }

  // IntelliJ gets confused finding this broker bean when @SpringBootTest is activated
  @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
  // Autowire the kafka broker registered via @EmbeddedKafka
  @Autowired
  private EmbeddedKafkaBroker embeddedKafka;

  // Autowire the service we're testing
  @Autowired
  KafkaEgress kafkaEgress;

  @Test
  public void testMetricsEncodedAsSent() {
    kafkaEgress.send("tenant-1", KafkaMessageType.METRIC, "{\"id\":1}");

    final Consumer<String, String> consumer = buildConsumer(
        StringDeserializer.class,
        StringDeserializer.class
    );

    embeddedKafka.consumeFromEmbeddedTopics(consumer, TOPIC_METRICS);
    final ConsumerRecord<String, String> record = getSingleRecord(consumer, TOPIC_METRICS, 500);

    // Use Hamcrest matchers provided by spring-kafka-test
    // https://docs.spring.io/spring-kafka/docs/2.2.4.RELEASE/reference/#hamcrest-matchers
    assertThat(record, hasKey("tenant-1"));
    assertThat(record, hasValue("{\"id\":1}"));
  }

  private <K,V> Consumer<K, V> buildConsumer(Class<? extends Deserializer> keyDeserializer,
                                                 Class<? extends Deserializer> valueDeserializer) {
    // Use the procedure documented at https://docs.spring.io/spring-kafka/docs/2.2.4.RELEASE/reference/#embedded-kafka-annotation

    final Map<String, Object> consumerProps = KafkaTestUtils
        .consumerProps("testMetricsEncodedAsSent", "true", embeddedKafka);
    // Since we're pre-sending the messages to test for, we need to read from start of topic
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    // We need to match the ser/deser used in expected application config
    consumerProps
        .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDeserializer.getName());
    consumerProps
        .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializer.getName());

    final DefaultKafkaConsumerFactory<K, V> consumerFactory =
        new DefaultKafkaConsumerFactory<>(consumerProps);
    return consumerFactory.createConsumer();
  }
}
