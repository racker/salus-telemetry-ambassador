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
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import com.rackspace.salus.telemetry.ambassador.config.AmbassadorProperties;
import com.rackspace.salus.telemetry.ambassador.types.ZoneNotAuthorizedException;
import com.rackspace.salus.telemetry.etcd.types.ResolvedZone;
import java.util.Collections;
import org.junit.Test;

public class ZoneAuthorizerTest {

  @Test
  public void testNonPublic() throws ZoneNotAuthorizedException {
    AmbassadorProperties properties = new AmbassadorProperties();
    properties.setPublicZonePrefix("public/");

    final ZoneAuthorizer authorizer = new ZoneAuthorizer(properties);

    final ResolvedZone zone = authorizer.authorize("any tenant", "notPublic");

    assertThat(zone, notNullValue());
    assertThat(zone.getId(), equalTo("notPublic"));
    assertThat(zone.getTenantId(), equalTo("any tenant"));
    assertThat(zone.isPublicZone(), equalTo(false));
  }

  @Test(expected = ZoneNotAuthorizedException.class)
  public void testPublicNotAuthorized_noneConfigured() throws ZoneNotAuthorizedException {
    AmbassadorProperties properties = new AmbassadorProperties();
    properties.setPublicZonePrefix("public/");

    final ZoneAuthorizer authorizer = new ZoneAuthorizer(properties);

    // should fail
    authorizer.authorize("any tenant", "public/us-east");
  }

  @Test(expected = ZoneNotAuthorizedException.class)
  public void testPublicNotAuthorized_noMatch() throws ZoneNotAuthorizedException {
    AmbassadorProperties properties = new AmbassadorProperties();
    properties.setPublicZonePrefix("public/");
    properties.setPublicZoneTenants(Collections.singletonList("admin-tenant"));

    final ZoneAuthorizer authorizer = new ZoneAuthorizer(properties);

    // should fail
    authorizer.authorize("any tenant", "public/us-east");
  }

  @Test
  public void testPublicAuthorized() throws ZoneNotAuthorizedException {
    AmbassadorProperties properties = new AmbassadorProperties();
    properties.setPublicZonePrefix("public/");
    properties.setPublicZoneTenants(Collections.singletonList("admin-tenant"));

    final ZoneAuthorizer authorizer = new ZoneAuthorizer(properties);

    final ResolvedZone zone = authorizer.authorize("admin-tenant", "public/us-east");

    assertThat(zone, notNullValue());
    assertThat(zone.getId(), equalTo("public/us-east"));
    assertThat(zone.isPublicZone(), equalTo(true));
  }
}