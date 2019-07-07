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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.monitor_management.web.client.MonitorApi;
import com.rackspace.salus.monitor_management.web.model.BoundMonitorDTO;
import com.rackspace.salus.resource_management.web.client.ResourceApi;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstructionConfigure;
import com.rackspace.salus.telemetry.messaging.MonitorBoundEvent;
import com.rackspace.salus.telemetry.messaging.OperationType;
import com.rackspace.salus.telemetry.model.AgentType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(classes = {
    ResourceLabelsService.class,
    KafkaTopicProperties.class,
    ResourceLabelsServiceTest.TestConfig.class
})
public class MonitorEventListenerTest {

  @Mock
  EnvoyRegistry envoyRegistry;

  @Mock
  MonitorApi monitorApi;

  @MockBean
  ResourceApi resourceApi;

  @Captor
  ArgumentCaptor<EnvoyInstruction> envoyInstructionArg;

  private MonitorEventListener monitorEventListener;

  @Before
  public void setUp() throws Exception {
    monitorEventListener = new MonitorEventListener(new KafkaTopicProperties(), envoyRegistry, monitorApi, "host-0");
  }

  @Test
  public void handleMessage() {

    final UUID id1 = UUID.randomUUID();
    final UUID id2 = UUID.randomUUID();

    List<BoundMonitorDTO> boundMonitors = Arrays.asList(
        new BoundMonitorDTO()
        .setMonitorId(id1)
        .setResourceId("r-1")
        .setAgentType(AgentType.TELEGRAF)
        .setResourceTenant("t-1")
        .setRenderedContent("content1"),
        new BoundMonitorDTO()
        .setMonitorId(id2)
        .setResourceId("r-2")
        .setAgentType(AgentType.FILEBEAT)
        .setResourceTenant("t-2")
        .setRenderedContent("content2")
    );

    when(monitorApi.getBoundMonitors(any()))
        .thenReturn(boundMonitors);

    when(envoyRegistry.contains("e-1"))
        .thenReturn(true);

    Map<OperationType, List<BoundMonitorDTO>> changes = new HashMap<>();
    changes.put(OperationType.CREATE, boundMonitors);

    when(envoyRegistry.applyBoundMonitors(any(), any()))
        .thenReturn(changes);

    MonitorBoundEvent event = new MonitorBoundEvent()
        .setEnvoyId("e-1");

    monitorEventListener.handleMessage(event);

    verify(monitorApi).getBoundMonitors("e-1");

    verify(envoyRegistry).contains("e-1");

    verify(envoyRegistry).applyBoundMonitors("e-1", boundMonitors);

    verify(envoyRegistry, times(2)).sendInstruction(eq("e-1"), envoyInstructionArg.capture());

    final EnvoyInstructionConfigure configure0 = envoyInstructionArg.getAllValues().get(0)
        .getConfigure();
    assertThat(configure0, notNullValue());
    assertThat(configure0.getAgentType(), equalTo(TelemetryEdge.AgentType.TELEGRAF));
    assertThat(configure0.getOperationsList(), hasSize(1));
    assertThat(configure0.getOperations(0).getId(), equalTo(id1.toString()+"_r-1"));

    final EnvoyInstructionConfigure configure1 = envoyInstructionArg.getAllValues().get(1)
        .getConfigure();
    assertThat(configure1, notNullValue());
    assertThat(configure1.getAgentType(), equalTo(TelemetryEdge.AgentType.FILEBEAT));
    assertThat(configure1.getOperationsList(), hasSize(1));
    assertThat(configure1.getOperations(0).getId(), equalTo(id2.toString()+"_r-2"));

    verifyNoMoreInteractions(envoyRegistry, monitorApi);
  }
}