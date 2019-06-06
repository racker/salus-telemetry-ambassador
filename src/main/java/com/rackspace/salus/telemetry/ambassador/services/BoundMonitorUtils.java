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

import com.rackspace.salus.monitor_management.web.model.BoundMonitorDTO;

public class BoundMonitorUtils {

  public static final String LABEL_TARGET_TENANT = "target_tenant";
  public static final String LABEL_RESOURCE = "resource_id";
  public static final String LABEL_MONITOR_ID = "monitor_id";

  public static String buildConfiguredMonitorId(BoundMonitorDTO boundMonitor) {
    // don't need to qualify resource ID by resource tenant since monitor ID is already distinct
    // enough and already a per-tenant object
    return String.join("_", boundMonitor.getMonitorId().toString(), boundMonitor.getResourceId());
  }
}
