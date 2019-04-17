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

package com.rackspace.salus.telemetry.ambassador.types;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Indicates that an Envoy was attaching with a zone not authorized for that tenant.
 */
@Data @EqualsAndHashCode(callSuper = true)
public class ZoneNotAuthorizedException extends Exception {

  private final String tenantId;
  private final String zone;

  public ZoneNotAuthorizedException(String tenantId, String zone) {
    super(String.format("The tenant '%s' is not authorized to attach to zone '%s'", tenantId, zone));
    this.tenantId = tenantId;
    this.zone = zone;
  }
}
