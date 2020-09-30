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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rackspace.salus.monitor_management.config.DatabaseConfig;
import com.rackspace.salus.services.TelemetryEdge.EnvoySummary;
import com.rackspace.salus.telemetry.entities.AgentHistory;
import com.rackspace.salus.telemetry.repositories.AgentHistoryRepository;
import com.rackspace.salus.test.EnableTestContainersDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.junit4.SpringRunner;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamFactoryImpl;

@Slf4j
@RunWith(SpringRunner.class)
@EnableTestContainersDatabase
@DataJpaTest(showSql = false)
@Import({AgentHistoryService.class, DatabaseConfig.class,
    ObjectMapper.class,
    SimpleMeterRegistry.class
})
public class AgentHistoryServiceTest {

  @Autowired
  AgentHistoryRepository agentHistoryRepository;

  @Autowired
  AgentHistoryService agentHistoryService;

  private AgentHistory currentAgentHistory;

  private PodamFactory podamFactory = new PodamFactoryImpl();

  @Mock
  MeterRegistry meterRegistry;

  @Before
  public void setUp() {
    AgentHistory agentHistory = new AgentHistory()
        .setTenantId("t-1")
        .setEnvoyId("e-1")
        .setResourceId("r-1")
        .setConnectedAt(Instant.now())
        .setDisconnectedAt(Instant.now())
        .setZoneId("z-1")
        .setRemoteIp("0.0.0.0");

    currentAgentHistory = agentHistoryRepository.save(agentHistory);
  }

  @After
  public void tearDown() throws Exception {
    // transactional rollback should take care of purging test data, but do a deleteAll to be sure
    agentHistoryRepository.deleteAll();
  }

  @Test
  public void testGetAgentHistoryForTenantAndId()  {
    Optional<AgentHistory> agentHistoryOptional = agentHistoryService.getAgentHistoryForTenantAndEnvoyId("t-1","e-1");
    assertExpectedAgentHistory(agentHistoryOptional);
  }

  @Test
  public void testGetAgentHistoryForTenantAndEnvoyId()  {
    Optional<AgentHistory> agentHistoryOptional = agentHistoryService.getAgentHistoryForTenantAndEnvoyId("t-1","e-1");
    assertExpectedAgentHistory(agentHistoryOptional);
  }

  @Test
  public void testGetAgentHistoryForTenantAndResource()  {
    int pageSize = 10;
    Pageable page = PageRequest.of(0, pageSize);
    Page<AgentHistory> result = agentHistoryService.getAgentHistoryForTenantAndResource("t-1","r-1", page);
    assertThat(result.getTotalElements(), equalTo(1L));
  }

  @Test
  public void testGetAgentHistoryForTenant()  {
    int pageSize = 10;
    Pageable page = PageRequest.of(0, pageSize);
    Page<AgentHistory> result = agentHistoryService.getAgentHistoryForTenant("t-1", page);
    assertThat(result.getTotalElements(), equalTo(1L));
  }

  private void assertExpectedAgentHistory(Optional<AgentHistory> agentHistoryOptional) {
    assertTrue(agentHistoryOptional.isPresent());
    assertThat(agentHistoryOptional.get().getId(), notNullValue());
    assertThat(agentHistoryOptional.get().getRemoteIp(), equalTo("0.0.0.0"));
    assertThat(agentHistoryOptional.get().getResourceId(), equalTo("r-1"));
    assertThat(agentHistoryOptional.get().getZoneId(), equalTo("z-1"));
  }

  @Test
  public void testAddAgentHistory() {
    final InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 33333);
    EnvoySummary envoySummary = podamFactory.manufacturePojo(EnvoySummary.class);
    Instant connectedAtInstant = Instant.now();
    AgentHistory agentHistory = agentHistoryService.addAgentHistory(envoySummary, remoteAddress, "e-1", "t-1", connectedAtInstant);

    assertThat(agentHistory.getEnvoyId(), equalTo("e-1"));
    assertThat(agentHistory.getResourceId(), equalTo(envoySummary.getResourceId()));
    assertThat(agentHistory.getTenantId(), equalTo("t-1"));
    assertThat(agentHistory.getZoneId(), equalTo(envoySummary.getZone()));
    assertThat(agentHistory.getRemoteIp(), equalTo(remoteAddress.toString()));
    assertThat(agentHistory.getConnectedAt(), equalTo(connectedAtInstant));

  }
}
