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

import com.rackspace.salus.services.TelemetryEdge;
import com.rackspace.salus.services.TelemetryEdge.ConfigurationOp.Type;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstruction;
import com.rackspace.salus.services.TelemetryEdge.EnvoyInstructionConfigure;
import com.rackspace.salus.telemetry.messaging.OperationType;
import com.rackspace.salus.telemetry.model.AgentType;
import java.util.List;
import org.junit.Test;

public class ConfigInstructionsBuilderTest {

  @Test
  public void testTypicalScenario() {

    final ConfigInstructionsBuilder builder = new ConfigInstructionsBuilder();

    builder.add(AgentType.TELEGRAF, "content1", OperationType.CREATE, "m-1");
    builder.add(AgentType.TELEGRAF, "content2", OperationType.UPDATE, "m-2");
    builder.add(AgentType.TELEGRAF, "", OperationType.DELETE, "m-3");
    builder.add(AgentType.FILEBEAT, "content4", OperationType.CREATE, "m-4");

    final List<EnvoyInstruction> instructions = builder.build();
    assertThat(instructions, hasSize(2));

    final EnvoyInstructionConfigure telegrafConfig = instructions.get(0).getConfigure();
    assertThat(telegrafConfig, notNullValue());
    assertThat(telegrafConfig.getAgentType(), equalTo(TelemetryEdge.AgentType.TELEGRAF));
    assertThat(telegrafConfig.getOperationsList(), hasSize(3));
    assertThat(telegrafConfig.getOperations(0).getId(), equalTo("m-1"));
    assertThat(telegrafConfig.getOperations(0).getType(), equalTo(Type.CREATE));
    assertThat(telegrafConfig.getOperations(0).getContent(), equalTo("content1"));
    assertThat(telegrafConfig.getOperations(1).getId(), equalTo("m-2"));
    assertThat(telegrafConfig.getOperations(1).getType(), equalTo(Type.MODIFY));
    assertThat(telegrafConfig.getOperations(1).getContent(), equalTo("content2"));
    assertThat(telegrafConfig.getOperations(2).getId(), equalTo("m-3"));
    assertThat(telegrafConfig.getOperations(2).getType(), equalTo(Type.REMOVE));
    // content of REMOVE is not used

    final EnvoyInstructionConfigure filebeatConfig = instructions.get(1).getConfigure();
    assertThat(filebeatConfig, notNullValue());
    assertThat(filebeatConfig.getAgentType(), equalTo(TelemetryEdge.AgentType.FILEBEAT));
    assertThat(filebeatConfig.getOperationsList(), hasSize(1));
    assertThat(filebeatConfig.getOperations(0).getId(), equalTo("m-4"));
    assertThat(filebeatConfig.getOperations(0).getType(), equalTo(Type.CREATE));
    assertThat(filebeatConfig.getOperations(0).getContent(), equalTo("content4"));
  }
}