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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstructionInstall;
import com.rackspace.salus.telemetry.entities.AgentInstall;
import com.rackspace.salus.telemetry.entities.AgentRelease;
import com.rackspace.salus.telemetry.entities.BoundAgentInstall;
import com.rackspace.salus.telemetry.messaging.AgentInstallChangeEvent;
import com.rackspace.salus.telemetry.messaging.OperationType;
import com.rackspace.salus.telemetry.model.AgentType;
import com.rackspace.salus.telemetry.repositories.BoundAgentInstallRepository;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.junit4.SpringRunner;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamFactoryImpl;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = {
    AgentInstallsListener.class,
    KafkaTopicProperties.class,
    AgentInstallsListenerTest.TestConfig.class,
    MeterRegistryTestConfig.class
}, properties = {
    "localhost.name=test-host"
})
public class AgentInstallsListenerTest {
  @TestConfiguration
  static class TestConfig {
    @Bean
    RetryTemplate retryTemplate() {
      final RetryTemplate retryTemplate = new RetryTemplate();
      retryTemplate.setRetryPolicy(new SimpleRetryPolicy(2));
      return retryTemplate;
    }
  }

  @MockBean
  EnvoyRegistry envoyRegistry;

  @MockBean
  BoundAgentInstallRepository boundAgentInstallRepository;

  @MockBean
  MonitorBindingService monitorBindingService;

  @Captor
  ArgumentCaptor<EnvoyInstruction> envoyInstructionArg;

  @Autowired
  AgentInstallsListener agentInstallsListener;

  private PodamFactory podamFactory = new PodamFactoryImpl();

  @Test
  public void testGroupId() {
    final String groupId = agentInstallsListener.getGroupId();

    assertThat(groupId).isEqualTo("ambassador-installs-test-host");
  }

  @Test
  public void testTopic() {
    final String topic = agentInstallsListener.getTopic();

    assertThat(topic).isEqualTo("telemetry.installs.json");
  }

  @Test
  public void testHandleInstallEvent_exists() {

    when(envoyRegistry.getEnvoyIdByResource("r-1"))
        .thenReturn("e-1");

    when(envoyRegistry.containsEnvoyResource(any()))
        .thenReturn(true);

    when(envoyRegistry.sendInstruction(any(), any()))
        .thenReturn(true);

    when(envoyRegistry.trackAgentInstall(any(), any(), any()))
        .thenReturn(Map.of(AgentType.TELEGRAF, "VERSION"));

    AgentRelease release = podamFactory.manufacturePojo(AgentRelease.class)
        .setType(AgentType.TELEGRAF)
        .setVersion("VERSION")
        .setUrl("URL")
        .setExe("EXE");
    AgentInstall install = podamFactory.manufacturePojo(AgentInstall.class)
        .setTenantId("t-1")
        .setAgentRelease(release);
    BoundAgentInstall boundAgentInstall = podamFactory.manufacturePojo(BoundAgentInstall.class)
        .setResourceId("r-1")
        .setAgentInstall(install);
    when(boundAgentInstallRepository.findAllByTenantResourceAgentType(any(), any(), any()))
        .thenReturn(List.of(boundAgentInstall));

    // EXECUTE

    agentInstallsListener.handleAgentInstallEvent(
        new AgentInstallChangeEvent()
        .setOp(OperationType.UPSERT)
        .setAgentType(AgentType.TELEGRAF)
        .setTenantId("t-1")
        .setResourceId("r-1")
    );

    // VERIFY

    verify(boundAgentInstallRepository).findAllByTenantResourceAgentType("t-1", "r-1", AgentType.TELEGRAF);

    verify(envoyRegistry).getEnvoyIdByResource("r-1");
    verify(envoyRegistry).containsEnvoyResource("r-1");

    verify(envoyRegistry).trackAgentInstall("e-1", AgentType.TELEGRAF, "VERSION");

    verify(envoyRegistry).sendInstruction(eq("e-1"), envoyInstructionArg.capture());
    final EnvoyInstructionInstall installInstruction = envoyInstructionArg.getValue().getInstall();
    assertThat(installInstruction).isNotNull();
    assertThat(installInstruction.getExe()).isEqualTo("EXE");
    assertThat(installInstruction.getUrl()).isEqualTo("URL");

    assertThat(installInstruction.getAgent()).isNotNull();
    assertThat(installInstruction.getAgent().getVersion()).isEqualTo("VERSION");
    assertThat(installInstruction.getAgent().getType()).isEqualTo(TelemetryEdge.AgentType.TELEGRAF);

    verify(monitorBindingService).processEnvoy("e-1", Map.of(AgentType.TELEGRAF, "VERSION"));

    verifyNoMoreInteractions(envoyRegistry, boundAgentInstallRepository, monitorBindingService);
  }

  @Test
  public void testHandleInstallEvent_notOurs() {

    when(envoyRegistry.containsEnvoyResource(any()))
        .thenReturn(false);

    // EXECUTE

    agentInstallsListener.handleAgentInstallEvent(
        new AgentInstallChangeEvent()
        .setOp(OperationType.UPSERT)
        .setAgentType(AgentType.TELEGRAF)
        .setTenantId("t-1")
        .setResourceId("r-1")
    );

    // VERIFY

    verify(envoyRegistry).containsEnvoyResource("r-1");

    verifyNoMoreInteractions(envoyRegistry, boundAgentInstallRepository, monitorBindingService);
  }
}