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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.rackspace.salus.monitor_management.web.client.ZoneApi;
import com.rackspace.salus.monitor_management.web.model.ZoneDTO;
import com.rackspace.salus.telemetry.ambassador.config.AmbassadorProperties;
import com.rackspace.salus.telemetry.ambassador.types.ZoneNotAuthorizedException;
import com.rackspace.salus.telemetry.etcd.types.ResolvedZone;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.junit4.SpringRunner;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamFactoryImpl;

@RunWith(SpringRunner.class)
public class ZoneAuthorizerTest {

  @MockBean
  ZoneApi zoneApi;

  private PodamFactory podamFactory = new PodamFactoryImpl();


  @Test
  public void testNonPublic() throws ZoneNotAuthorizedException {
    AmbassadorProperties properties = new AmbassadorProperties();

    final ZoneAuthorizer authorizer = new ZoneAuthorizer(properties, zoneApi);

    ZoneDTO zoneDTO = podamFactory.manufacturePojo(ZoneDTO.class);
    zoneDTO.setName("notPublic");

    when(zoneApi.getAvailableZones(anyString())).thenReturn(Collections.singletonList(zoneDTO));

    final ResolvedZone zone = authorizer.authorize("any tenant", zoneDTO.getName());

    assertThat(zone, notNullValue());
    assertThat(zone.getName(), equalTo("notpublic"));
    assertThat(zone.getTenantId(), equalTo("any tenant"));
    assertThat(zone.isPublicZone(), equalTo(false));
  }

  @Test(expected = ZoneNotAuthorizedException.class)
  public void testPublicNotAuthorized_noneConfigured() throws ZoneNotAuthorizedException {
    AmbassadorProperties properties = new AmbassadorProperties();

    final ZoneAuthorizer authorizer = new ZoneAuthorizer(properties, zoneApi);

    // should fail
    authorizer.authorize("any tenant", "public/us-east");
  }

  @Test(expected = ZoneNotAuthorizedException.class)
  public void testPublicNotAuthorized_noMatch() throws ZoneNotAuthorizedException {
    AmbassadorProperties properties = new AmbassadorProperties();
    properties.setPublicZoneTenants(Collections.singletonList("admin-tenant"));

    final ZoneAuthorizer authorizer = new ZoneAuthorizer(properties, zoneApi);

    // should fail
    authorizer.authorize("any tenant", "public/us-east");
  }

  @Test
  public void testPublicAuthorized() throws ZoneNotAuthorizedException {
    AmbassadorProperties properties = new AmbassadorProperties();
    properties.setPublicZoneTenants(Collections.singletonList("admin-tenant"));

    ZoneDTO zoneDTO = podamFactory.manufacturePojo(ZoneDTO.class);
    zoneDTO.setName("public/us-east");

    when(zoneApi.getAvailableZones(anyString())).thenReturn(Collections.singletonList(zoneDTO));

    final ZoneAuthorizer authorizer = new ZoneAuthorizer(properties, zoneApi);

    final ResolvedZone zone = authorizer.authorize("admin-tenant", zoneDTO.getName());

    assertThat(zone, notNullValue());
    assertThat(zone.getName(), equalTo("public/us-east"));
    assertThat(zone.isPublicZone(), equalTo(true));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testPublicZoneDoesntExist() throws ZoneNotAuthorizedException {
    AmbassadorProperties properties = new AmbassadorProperties();
    properties.setPublicZoneTenants(Collections.singletonList("admin-tenant"));

    when(zoneApi.getAvailableZones(anyString())).thenReturn(Collections.emptyList());

    final ZoneAuthorizer authorizer = new ZoneAuthorizer(properties, zoneApi);

    authorizer.authorize("admin-tenant", "public/us-east");
  }

  @Test
  public void testResolvingNullZone() throws ZoneNotAuthorizedException {
    AmbassadorProperties properties = new AmbassadorProperties();
    final ZoneAuthorizer authorizer = new ZoneAuthorizer(properties, zoneApi);

    final ResolvedZone zone = authorizer.authorize("any-tenant", null);

    assertThat(zone, nullValue());
  }

  @Test
  public void testResolvingEmptyZone() throws ZoneNotAuthorizedException {
    AmbassadorProperties properties = new AmbassadorProperties();
    final ZoneAuthorizer authorizer = new ZoneAuthorizer(properties, zoneApi);

    final ResolvedZone zone = authorizer.authorize("any-tenant", "");

    assertThat(zone, nullValue());
  }
}