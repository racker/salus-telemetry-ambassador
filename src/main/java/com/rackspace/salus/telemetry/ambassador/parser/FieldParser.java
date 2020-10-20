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

package com.rackspace.salus.telemetry.ambassador.parser;

import com.rackspace.monplat.protocol.UniversalMetricFrame.AccountType;
import com.rackspace.salus.telemetry.ambassador.config.FieldParserProperties;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

@EnableConfigurationProperties(FieldParserProperties.class)
@Service
public class FieldParser {

  FieldParserProperties properties;

  public FieldParser(FieldParserProperties properties) {
    this.properties = properties;
  }

  /**
   * Returns the accountType and deviceId if found, otherwise null.
   *
   * @param resourceId The resourceId to parse.
   * @return The deviceId, or null.
   */
  public Pair<AccountType, String> getDeviceIdForResourceId(String resourceId) {
    if (StringUtils.isBlank(resourceId)) {
      return null;
    }
    for (Entry<String, String> entry: properties.getDeviceTypeRegex().entrySet()) {
      Pattern devicePattern = Pattern.compile(entry.getValue(), Pattern.CASE_INSENSITIVE);

      Matcher matcher = devicePattern.matcher(resourceId);
      if (matcher.matches()) {
        String deviceId = matcher.group(FieldParserProperties.DEVICE_GROUP);
        String deviceType = entry.getKey();
        AccountType accountType = properties.getDeviceToAccount().get(deviceType);

        if (accountType == null) {
          throw new IllegalStateException(
              String.format("No matching accountType for deviceType=%s", deviceType));
        }

        return Pair.of(accountType, deviceId);
      }
    }
    return null;
  }
}
