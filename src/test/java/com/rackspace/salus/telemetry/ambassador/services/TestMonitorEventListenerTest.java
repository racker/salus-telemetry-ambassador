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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.telemetry.messaging.TestMonitorRequestEvent;
import com.rackspace.salus.telemetry.model.AgentType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest
public class TestMonitorEventListenerTest {

  @Configuration
  @Import({TestMonitorEventListener.class, KafkaTopicProperties.class})
  public static class TestConfig {

  }

  @MockBean
  EnvoyRegistry envoyRegistry;

  @Autowired
  TestMonitorEventListener testMonitorEventListener;

  @Test
  public void testConsumeTestMonitorEvent_contains() {

    when(envoyRegistry.contains(any()))
        .thenReturn(true);

    // EXECUTE

    testMonitorEventListener.consumeTestMonitorEvent(
        new TestMonitorRequestEvent()
            .setCorrelationId("id-1")
            .setTenantId("t-1")
            .setResourceId("r-1")
            .setEnvoyId("e-1")
            .setAgentType(AgentType.TELEGRAF)
            .setRenderedContent("content-1")
    );

    // VERIFY

    verify(envoyRegistry).contains("e-1");

    verify(envoyRegistry).sendInstruction(eq("e-1"), argThat(envoyInstruction -> {
      assertThat(envoyInstruction.getTestMonitor()).isNotNull();
      assertThat(envoyInstruction.getTestMonitor().getCorrelationId()).isEqualTo("id-1");
      assertThat(envoyInstruction.getTestMonitor().getContent()).isEqualTo("content-1");
      assertThat(envoyInstruction.getTestMonitor().getAgentType())
          .isEqualTo(TelemetryEdge.AgentType.TELEGRAF);

      return true;
    }));

    verifyNoMoreInteractions(envoyRegistry);
  }

  @Test
  public void testConsumeTestMonitorEvent_doesNotContain() {

    when(envoyRegistry.contains(any()))
        .thenReturn(false);

    // EXECUTE

    testMonitorEventListener.consumeTestMonitorEvent(
        new TestMonitorRequestEvent()
            .setCorrelationId("id-1")
            .setTenantId("t-1")
            .setResourceId("r-1")
            .setEnvoyId("e-1")
            .setAgentType(AgentType.TELEGRAF)
            .setRenderedContent("content-1")
    );

    // VERIFY

    verify(envoyRegistry).contains("e-1");

    // sendInstruction not called

    verifyNoMoreInteractions(envoyRegistry);
  }
}