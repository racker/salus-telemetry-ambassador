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

package com.rackspace.salus.telemetry.ambassador.config;

import com.rackspace.monplat.protocol.UniversalMetricFrame.AccountType;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("salus.parser")
@Data
public class FieldParserProperties {

  public static final String DEVICE_GROUP = "deviceId";
  public static final String INT_REGEX = "[0-9]+";
  public static final String UUID_REGEX =
    "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}";

  /**
   * A map of device types to a regex.
   *
   * The regex can be ran against a resourceId to determine the type of device it relates to,
   * providing the ability to retrieve the deviceId.
   */
  public static final Map<String, String> deviceTypeRegex = Map.of(
      "cloud_v1", String.format("cloud\\:(?<%s>%s)", DEVICE_GROUP, INT_REGEX),
      "cloud_v2", String.format("cloud\\:(?<%s>%s)", DEVICE_GROUP, UUID_REGEX),
      "dedicated", String.format("dedicated\\:(?<%s>%s+)", DEVICE_GROUP, INT_REGEX),
      "noAccountType", String.format("bad\\:(?<%s>%s)", DEVICE_GROUP, INT_REGEX),
      "test", String.format("(?<%s>r-[a-zA-Z0-9]+)", DEVICE_GROUP)
  );

  /**
   * A map of device types to Account Types.
   *
   * Once the device is found using deviceTypeRegex, the account type can then be
   * determined via this map.
   */
  public static final Map<String, AccountType> deviceToAccount = Map.of(
      "cloud_v1", AccountType.CLOUD,
      "cloud_v2", AccountType.CLOUD,
      "dedicated", AccountType.MANAGED_HOSTING,
      "test", AccountType.MANAGED_HOSTING
  );
}
