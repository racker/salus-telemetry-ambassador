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

import com.rackspace.salus.telemetry.ambassador.config.AmbassadorProperties;
import com.rackspace.salus.telemetry.ambassador.types.ZoneNotAuthorizedException;
import com.rackspace.salus.telemetry.etcd.types.ResolvedZone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Handles validation and authorization of public zones.
 */
@Component
public class ZoneAuthorizer {

  private final AmbassadorProperties properties;

  @Autowired
  public ZoneAuthorizer(AmbassadorProperties properties) {
    this.properties = properties;
  }

  /**
   * Authorizes the use of a potentially public zone for the given Envoy's tenant.
   * <em>NOTE:</em> the returned zoned must be the value used and persisted
   * @param tenantId the Envoy's tenant
   * @param zone the zone to verify
   * @return the zone, potentially transformed with further namespacing.
   * @throws ZoneNotAuthorizedException when the given zone is public and the tenant is not authorized
   */
  public ResolvedZone authorize(String tenantId, String zone) throws ZoneNotAuthorizedException {
    if (zone.startsWith(properties.getPublicZonePrefix())) {
      if (properties.getPublicZoneTenants() == null || !properties.getPublicZoneTenants()
          .contains(tenantId)) {
        throw new ZoneNotAuthorizedException(tenantId, zone);
      }

      return new ResolvedZone()
          .setPublicZone(true)
          .setId(zone);
    }
    else {
      return new ResolvedZone()
          .setPublicZone(false)
          .setId(zone)
          .setTenantId(tenantId);
    }
  }
}
