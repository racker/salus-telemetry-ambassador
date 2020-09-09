/*
 * Copyright 2020 Rackspace US, Inc.
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
import com.rackspace.salus.common.web.RemoteServiceCallException;
import com.rackspace.salus.monitor_management.web.client.MonitorApi;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.TestMonitorResults;
import com.rackspace.salus.telemetry.messaging.TestMonitorRequestEvent;
import com.rackspace.salus.telemetry.model.AgentType;
import com.rackspace.salus.telemetry.model.ConfigSelectorScope;
import com.rackspace.salus.telemetry.model.MonitorType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.client.RestClientException;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = {
    TestMonitorEventListener.class,
    KafkaTopicProperties.class,
    SimpleMeterRegistry.class
})
public class TestMonitorEventListenerTest {

  @MockBean
  EnvoyRegistry envoyRegistry;

  @MockBean
  TestMonitorResultsProducer resultsProducer;

  @MockBean
  MonitorApi monitorApi;

  @Autowired
  TestMonitorEventListener testMonitorEventListener;

  @Test
  public void testConsumeTestMonitorEvent_contains() {

    when(envoyRegistry.contains(any()))
        .thenReturn(true);

    when(envoyRegistry.getInstalledAgentVersions(any()))
        .thenReturn(Map.of(AgentType.TELEGRAF, "1.13.2"));

    when(monitorApi.translateMonitorContent(any(), any(), any(), any(), any()))
        .thenReturn("translated content");

    // EXECUTE

    testMonitorEventListener.consumeTestMonitorEvent(
        new TestMonitorRequestEvent()
            .setCorrelationId("id-1")
            .setTenantId("t-1")
            .setResourceId("r-1")
            .setEnvoyId("e-1")
            .setAgentType(AgentType.TELEGRAF)
            .setMonitorType(MonitorType.cpu)
            .setScope(ConfigSelectorScope.LOCAL)
            .setRenderedContent("original content")
            .setTimeout(3L)
    );

    // VERIFY

    verify(envoyRegistry).contains("e-1");

    verify(envoyRegistry).getInstalledAgentVersions("e-1");

    verify(envoyRegistry).sendInstruction(eq("e-1"), argThat(envoyInstruction -> {
      assertThat(envoyInstruction.getTestMonitor()).isNotNull();
      assertThat(envoyInstruction.getTestMonitor().getCorrelationId()).isEqualTo("id-1");
      assertThat(envoyInstruction.getTestMonitor().getContent()).isEqualTo("translated content");
      assertThat(envoyInstruction.getTestMonitor().getTimeout()).isEqualTo(3L);
      assertThat(envoyInstruction.getTestMonitor().getAgentType())
          .isEqualTo(TelemetryEdge.AgentType.TELEGRAF);

      return true;
    }));

    verify(monitorApi).translateMonitorContent(
        AgentType.TELEGRAF, "1.13.2", MonitorType.cpu, ConfigSelectorScope.LOCAL, "original content");

    verifyNoMoreInteractions(envoyRegistry, monitorApi);
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

    verifyNoMoreInteractions(envoyRegistry, monitorApi);
  }

  @Test
  public void testConsumeTestMonitorEvent_agentNotInstalled() {

    when(envoyRegistry.contains(any()))
        .thenReturn(true);

    when(envoyRegistry.getInstalledAgentVersions("e-1"))
        // simulate case where no agents are installed
        .thenReturn(Map.of());

    testMonitorEventListener.consumeTestMonitorEvent(
        new TestMonitorRequestEvent()
            .setCorrelationId("id-1")
            .setEnvoyId("e-1")
            .setAgentType(AgentType.TELEGRAF)
    );

    verify(envoyRegistry).contains("e-1");

    verify(envoyRegistry).getInstalledAgentVersions("e-1");

    verify(resultsProducer).send(
        TestMonitorResults.newBuilder()
            .setCorrelationId("id-1")
            .addErrors("Agent is not installed")
            .build()
    );

    verifyNoMoreInteractions(envoyRegistry, resultsProducer, monitorApi);
  }

  @Test
  public void testConsumeTestMonitorEvent_failedTranslate() {

    when(envoyRegistry.contains(any()))
        .thenReturn(true);

    when(envoyRegistry.getInstalledAgentVersions(any()))
        .thenReturn(Map.of(AgentType.TELEGRAF, "1.11.0"));

    when(monitorApi.translateMonitorContent(any(), any(), any(), any(), any()))
        .thenThrow(new RemoteServiceCallException("monitor-management",
            new RestClientException("something failed")));

    testMonitorEventListener.consumeTestMonitorEvent(
        new TestMonitorRequestEvent()
            .setCorrelationId("id-1")
            .setTenantId("t-1")
            .setResourceId("r-1")
            .setEnvoyId("e-1")
            .setAgentType(AgentType.TELEGRAF)
            .setMonitorType(MonitorType.http)
            .setScope(ConfigSelectorScope.REMOTE)
            .setRenderedContent("content that fails")
            .setTimeout(3L)
    );

    verify(envoyRegistry).contains("e-1");

    verify(envoyRegistry).getInstalledAgentVersions("e-1");

    verify(monitorApi).translateMonitorContent(
        AgentType.TELEGRAF, "1.11.0",
        MonitorType.http, ConfigSelectorScope.REMOTE,
        "content that fails");

    verify(resultsProducer).send(
        TestMonitorResults.newBuilder()
            .setCorrelationId("id-1")
            .addErrors("Internal error: Remote call to service monitor-management failed: something failed")
            .build()
    );

    verifyNoMoreInteractions(envoyRegistry, resultsProducer, monitorApi);
  }
}