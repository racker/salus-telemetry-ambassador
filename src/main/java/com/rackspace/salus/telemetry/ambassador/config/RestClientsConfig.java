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

package com.rackspace.salus.telemetry.ambassador.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rackspace.salus.acm.web.client.AgentInstallApi;
import com.rackspace.salus.acm.web.client.AgentInstallApiClient;
import com.rackspace.salus.monitor_management.web.client.MonitorApi;
import com.rackspace.salus.monitor_management.web.client.MonitorApiClient;
import com.rackspace.salus.monitor_management.web.client.ZoneApi;
import com.rackspace.salus.monitor_management.web.client.ZoneApiClient;
import com.rackspace.salus.resource_management.web.client.ResourceApi;
import com.rackspace.salus.resource_management.web.client.ResourceApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RestClientsConfig {

  private final ServicesProperties servicesProperties;

  @Autowired
  public RestClientsConfig(ServicesProperties servicesProperties) {
    this.servicesProperties = servicesProperties;
  }

  @Bean
  public MonitorApi monitorApi(RestTemplateBuilder restTemplateBuilder) {
    return new MonitorApiClient(
        restTemplateBuilder
            .rootUri(servicesProperties.getMonitorManagementUrl())
            .build()
    );
  }

  @Bean
  public ZoneApi zoneApi(RestTemplateBuilder restTemplateBuilder) {
    return new ZoneApiClient(
            restTemplateBuilder
                    .rootUri(servicesProperties.getMonitorManagementUrl())
                    .build()
    );
  }

  @Bean
  public ResourceApi resourceApi(ObjectMapper objectMapper, RestTemplateBuilder restTemplateBuilder) {
    return new ResourceApiClient(
        objectMapper,
        restTemplateBuilder
            .rootUri(servicesProperties.getResourceManagementUrl())
            .build()
    );
  }

  @Bean
  public AgentInstallApi agentInstallApi(RestTemplateBuilder restTemplateBuilder) {
    return new AgentInstallApiClient(
        restTemplateBuilder
            .rootUri(servicesProperties.getAgentCatalogManagementUrl())
            .build()
    );
  }
}
