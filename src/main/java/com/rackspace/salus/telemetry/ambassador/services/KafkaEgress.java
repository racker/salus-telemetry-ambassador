/*
 *    Copyright 2018 Rackspace US, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *
 */

package com.rackspace.salus.telemetry.ambassador.services;

import com.rackspace.salus.telemetry.ambassador.config.AmbassadorProperties;
import com.rackspace.salus.telemetry.ambassador.types.KafkaMessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaEgress {

    private final KafkaTemplate kafkaTemplate;
    private final AmbassadorProperties ambassadorProperties;

    @Autowired
    public KafkaEgress(KafkaTemplate kafkaTemplate, AmbassadorProperties ambassadorProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.ambassadorProperties = ambassadorProperties;
    }

    public void send(String tenantId, KafkaMessageType messageType, String payload) {
        final String topic = ambassadorProperties.getKafkaTopics().get(messageType);
        if (topic == null) {
            throw new IllegalArgumentException(String.format("No topic configured for %s", messageType));
        }

        kafkaTemplate.send(topic, tenantId, payload);
    }
}
