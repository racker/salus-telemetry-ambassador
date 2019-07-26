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

import com.rackspace.salus.monitor_management.web.client.ZoneApi;
import com.rackspace.salus.monitor_management.web.model.ZoneDTO;
import com.rackspace.salus.telemetry.ambassador.config.AmbassadorProperties;
import com.rackspace.salus.telemetry.ambassador.types.ZoneNotAuthorizedException;
import com.rackspace.salus.telemetry.etcd.types.ResolvedZone;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;

/**
 * Handles validation and authorization of public zones.
 */
@Component
@Slf4j
public class ZoneAuthorizer {

  private final AmbassadorProperties properties;
  private final ZoneApi zoneApi;

  @Autowired
  public ZoneAuthorizer(AmbassadorProperties properties, ZoneApi zoneApi) {
    this.properties = properties;
    this.zoneApi = zoneApi;
  }

  /**
   * Authorizes the use of a potentially public zone for the given Envoy's tenant.
   * <em>NOTE:</em> the returned zoned must be the value used and persisted
   * @param tenantId the Envoy's tenant
   * @param zone the zone to verify
   * @return the zone, potentially transformed with further namespacing.
   * @throws ZoneNotAuthorizedException when the given zone is public and the tenant is not authorized
   */
  public ResolvedZone authorize(String tenantId, String zone) throws ZoneNotAuthorizedException, IllegalArgumentException {
    if (!StringUtils.hasText(zone)) {
      return null;
    }

    log.debug(
        "Evaluating zone={} attached by tenant={} against publicZonePrefix={}",
        zone, tenantId, ResolvedZone.PUBLIC_PREFIX
    );

    if (zone.startsWith(ResolvedZone.PUBLIC_PREFIX)) {
      if (properties.getPublicZoneTenants() == null || !properties.getPublicZoneTenants()
          .contains(tenantId)) {
        throw new ZoneNotAuthorizedException(tenantId, zone);
      }

      validateZoneAvailable(tenantId, zone);
      return ResolvedZone.createPublicZone(zone);
    }
    else {
      validateZoneAvailable(tenantId, zone);
      return ResolvedZone.createPrivateZone(tenantId, zone);
    }
  }

  /**
   * Checks if the provided zone is an option for the client to connect to.
   * Any zone provided must have previously been created by ZoneManagement.
   *
   * @param tenantId The tenantId of the connecting envoy.
   * @param zone The zone provided in the envoySummary.
   * @throws IllegalArgumentException if the zone is not found.
   */
  private void validateZoneAvailable(String tenantId, String zone) throws IllegalArgumentException {
    List<ZoneDTO> zones;
    try {
      zones = zoneApi.getAvailableZones(tenantId);
    } catch (ResourceAccessException e) {
      // need to do something here to handle things more gracefully.
      throw new RuntimeException("Unable to validate zones.", e);
    }
    boolean found = zones.stream().anyMatch(z -> z.getName().equals(zone));

    log.debug("Found {} zones for tenant", zoneApi.getAvailableZones(tenantId).size());
    if (!found) throw new IllegalArgumentException("Provided zone does not exist: " + zone);
  }
}
