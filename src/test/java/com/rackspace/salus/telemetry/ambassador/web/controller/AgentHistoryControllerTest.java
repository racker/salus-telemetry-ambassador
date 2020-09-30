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

package com.rackspace.salus.telemetry.ambassador.web.controller;

import static org.hamcrest.CoreMatchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rackspace.salus.telemetry.ambassador.services.AgentHistoryService;
import com.rackspace.salus.telemetry.ambassador.web.model.AgentHistoryDTO;
import com.rackspace.salus.telemetry.entities.AgentHistory;
import com.rackspace.salus.telemetry.model.PagedContent;
import com.rackspace.salus.telemetry.repositories.AgentHistoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamFactoryImpl;

@RunWith(SpringRunner.class)
@WebMvcTest(controllers = AgentHistoryController.class)
@ActiveProfiles("test")
public class AgentHistoryControllerTest {

  private PodamFactory podamFactory = new PodamFactoryImpl();

  @Autowired
  MockMvc mockMvc;

  @MockBean
  AgentHistoryService agentHistoryService;

  @MockBean
  AgentHistoryRepository agentHistoryRepository;

  @Autowired
  ObjectMapper objectMapper;

  @Test
  public void testGetAgentHistoryForTenant_by_envoyId() throws Exception {
    AgentHistory agentHistory = podamFactory.manufacturePojo(AgentHistory.class);
    when(agentHistoryService.getAgentHistoryForTenantAndEnvoyId(anyString(), anyString()))
        .thenReturn(Optional.of(agentHistory));

    String url = String.format("/api/tenant/%s/agent-history", agentHistory.getTenantId());

    mockMvc.perform(get(url).param("envoyId",agentHistory.getEnvoyId()).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andDo(print())
        .andExpect(content()
            .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.content[0].id", is(agentHistory.getId().toString())));
    verify(agentHistoryService).getAgentHistoryForTenantAndEnvoyId(
        agentHistory.getTenantId(), agentHistory.getEnvoyId());
  }

  @Test
  public void testGetAgentHistoryForTenant_by_envoyIdFailed() throws Exception {
    AgentHistory agentHistory = podamFactory.manufacturePojo(AgentHistory.class);
    when(agentHistoryService.getAgentHistoryForTenantAndEnvoyId(anyString(), anyString()))
        .thenReturn(Optional.empty());

    String url = String.format("/api/tenant/%s/agent-history", agentHistory.getTenantId());

    mockMvc.perform(get(url).param("envoyId",agentHistory.getEnvoyId()).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andDo(print())
        .andExpect(content()
            .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.content[0]").doesNotExist());
    verify(agentHistoryService).getAgentHistoryForTenantAndEnvoyId(
        agentHistory.getTenantId(), agentHistory.getEnvoyId());
  }

  @Test
  public void testGetAgentHistoryForTenant_by_resourceId() throws Exception {
    int numberOfAgentHistory = 1;
    // Use the APIs default Pageable settings
    int page = 0;
    int pageSize = 20;
    AgentHistory agentHistory = podamFactory.manufacturePojo(AgentHistory.class);
    List<AgentHistory> listOfAgentHistory = List.of(agentHistory);
    Pageable pageable = PageRequest.of(0, 20, Sort.unsorted());

    int start = page * pageSize;
    Page<AgentHistory> pageOfAgentHistory = new PageImpl<>(listOfAgentHistory.subList(start, numberOfAgentHistory),
        PageRequest.of(page, pageSize),
        numberOfAgentHistory);

    PagedContent<AgentHistory> result = PagedContent.fromPage(pageOfAgentHistory);

    when(agentHistoryService.getAgentHistoryForTenantAndResource(anyString(), anyString(), any()))
        .thenReturn(pageOfAgentHistory);

    String url = String.format("/api/tenant/%s/agent-history", agentHistory.getTenantId());

    mockMvc.perform(get(url).param("resourceId",agentHistory.getResourceId()).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andDo(print())
        .andExpect(content()
            .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().string(objectMapper.writeValueAsString(result.map(AgentHistoryDTO::new))))
        .andExpect(jsonPath("$.content[0].id", is(agentHistory.getId().toString())));
    verify(agentHistoryService).getAgentHistoryForTenantAndResource(
        agentHistory.getTenantId(), agentHistory.getResourceId(), pageable);
  }

  @Test
  public void testGetAgentHistoryForTenant() throws Exception {
    int numberOfAgentHistory = 1;
    // Use the APIs default Pageable settings
    int page = 0;
    int pageSize = 20;
    AgentHistory agentHistory = podamFactory.manufacturePojo(AgentHistory.class);
    List<AgentHistory> listOfAgentHistory = List.of(agentHistory);
    Pageable pageable = PageRequest.of(0, 20, Sort.unsorted());

    int start = page * pageSize;
    Page<AgentHistory> pageOfAgentHistory = new PageImpl<>(listOfAgentHistory.subList(start, numberOfAgentHistory),
        PageRequest.of(page, pageSize),
        numberOfAgentHistory);

    PagedContent<AgentHistory> result = PagedContent.fromPage(pageOfAgentHistory);

    when(agentHistoryService.getAgentHistoryForTenant(anyString(), any()))
        .thenReturn(pageOfAgentHistory);

    String url = String.format("/api/tenant/%s/agent-history", agentHistory.getTenantId());

    mockMvc.perform(get(url).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andDo(print())
        .andExpect(content()
            .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().string(objectMapper.writeValueAsString(result.map(AgentHistoryDTO::new))))
        .andExpect(jsonPath("$.content[0].id", is(agentHistory.getId().toString())));
    verify(agentHistoryService).getAgentHistoryForTenant(
        agentHistory.getTenantId(), pageable);
  }

  @Test
  public void testGetAgentHistoryForTenant_by_resourceId_and_envoyId() throws Exception {
    AgentHistory agentHistory = podamFactory.manufacturePojo(AgentHistory.class);
    String url = String.format("/api/tenant/%s/agent-history", agentHistory.getTenantId());

    mockMvc.perform(get(url)
        .param("envoyId", agentHistory.getEnvoyId())
        .param("resourceId", agentHistory.getResourceId())
        .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andDo(print());
    verifyNoMoreInteractions(agentHistoryService);
  }

  @Test
  public void testGetAgentHistoryById() throws Exception {
    AgentHistory agentHistory = podamFactory.manufacturePojo(AgentHistory.class);
    when(agentHistoryService.getAgentHistoryForIdAndTenantId(any(), anyString()))
        .thenReturn(Optional.of(agentHistory));

    String url = String.format("/api/tenant/%s/agent-history/%s", agentHistory.getTenantId(), agentHistory.getId());

    mockMvc.perform(get(url).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andDo(print())
        .andExpect(content()
            .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id", is(agentHistory.getId().toString())));

    verify(agentHistoryService).getAgentHistoryForIdAndTenantId(
        agentHistory.getId(), agentHistory.getTenantId());
  }

  @Test
  public void testGetAgentHistoryById_not_found() throws Exception {
    AgentHistory agentHistory = podamFactory.manufacturePojo(AgentHistory.class);
    when(agentHistoryService.getAgentHistoryForIdAndTenantId(any(), anyString()))
        .thenReturn(Optional.empty());

    String url = String.format("/api/tenant/%s/agent-history/%s", agentHistory.getTenantId(), agentHistory.getId());

    mockMvc.perform(get(url).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andDo(print())
        .andExpect(content()
            .contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    verify(agentHistoryService).getAgentHistoryForIdAndTenantId(
        agentHistory.getId(), agentHistory.getTenantId());
  }
}
