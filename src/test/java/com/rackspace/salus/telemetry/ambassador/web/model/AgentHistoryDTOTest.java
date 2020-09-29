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

package com.rackspace.salus.telemetry.ambassador.web.model;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rackspace.salus.common.web.View;
import com.rackspace.salus.telemetry.entities.AgentHistory;
import java.time.format.DateTimeFormatter;
import org.junit.Test;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamFactoryImpl;

public class AgentHistoryDTOTest {

  final PodamFactory podamFactory = new PodamFactoryImpl();

  final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void testFieldsCovered() throws Exception {
    final AgentHistory agentHistory = podamFactory.manufacturePojo(AgentHistory.class);

    final AgentHistoryDTO dto = new AgentHistoryDTO(agentHistory);

    assertThat(dto.getId(), notNullValue());
    assertThat(dto.getConnectedAt(), notNullValue());
    assertThat(dto.getDisconnectedAt(), notNullValue());
    assertThat(dto.getEnvoyId(), notNullValue());
    assertThat(dto.getRemoteIp(), notNullValue());
    assertThat(dto.getResourceId(), notNullValue());
    assertThat(dto.getTenantId(), notNullValue());
    assertThat(dto.getZoneId(), notNullValue());


    assertThat(dto.getId(), equalTo(agentHistory.getId()));
    assertThat(dto.getZoneId(), equalTo(agentHistory.getZoneId()));
    assertThat(dto.getTenantId(), equalTo(agentHistory.getTenantId()));
    assertThat(dto.getResourceId(), equalTo(agentHistory.getResourceId()));
    assertThat(dto.getRemoteIp(), equalTo(agentHistory.getRemoteIp()));
    assertThat(dto.getEnvoyId(), equalTo(agentHistory.getEnvoyId()));
    assertThat(dto.getConnectedAt(), equalTo(DateTimeFormatter.ISO_INSTANT.format(agentHistory.getConnectedAt())));
    assertThat(dto.getDisconnectedAt(), equalTo(DateTimeFormatter.ISO_INSTANT.format(agentHistory.getDisconnectedAt())));


    String objectAsString;
    AgentHistoryDTO convertedDto;

    objectAsString = objectMapper.writerWithView(View.Admin.class).writeValueAsString(dto);
    convertedDto = objectMapper.readValue(objectAsString, AgentHistoryDTO.class);
    assertThat(convertedDto.getEnvoyId(), notNullValue());
  }
}
