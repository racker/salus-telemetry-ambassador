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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rackspace.monplat.protocol.UniversalMetricFrame.AccountType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.data.util.Pair;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
public class FieldParserTest {

  @Test
  public void getDeviceIdForResourceIdTest_cloudv1Resource() {
    String resourceId = "cloud:123456";

    Pair<AccountType, String> accountAndDevice = FieldParser.getDeviceIdForResourceId(resourceId);
    assertThat(accountAndDevice).isNotNull();
    assertThat(accountAndDevice.getFirst()).isEqualTo(AccountType.CLOUD);
    assertThat(accountAndDevice.getSecond()).isEqualTo("123456");
  }

  @Test
  public void getDeviceIdForResourceIdTest_cloudv2Resource() {
    String resourceId = "cloud:a24d106b-53e0-4fd5-9be9-93555dadf66d";

    Pair<AccountType, String> accountAndDevice = FieldParser.getDeviceIdForResourceId(resourceId);
    assertThat(accountAndDevice).isNotNull();
    assertThat(accountAndDevice.getFirst()).isEqualTo(AccountType.CLOUD);
    assertThat(accountAndDevice.getSecond()).isEqualTo("a24d106b-53e0-4fd5-9be9-93555dadf66d");
  }

  @Test
  public void getDeviceIdForResourceIdTest_dedicatedResource() {
    String resourceId = "dedicated:123456";

    Pair<AccountType, String> accountAndDevice = FieldParser.getDeviceIdForResourceId(resourceId);
    assertThat(accountAndDevice).isNotNull();
    assertThat(accountAndDevice.getFirst()).isEqualTo(AccountType.MANAGED_HOSTING);
    assertThat(accountAndDevice.getSecond()).isEqualTo("123456");
  }

  @Test
  public void getDeviceIdForResourceIdTest_unknownResource() {
    String resourceId = "xen-id:123456";

    Pair<AccountType, String> accountAndDevice = FieldParser.getDeviceIdForResourceId(resourceId);
    assertThat(accountAndDevice).isNull();
  }

  @Test
  public void getDeviceIdForResourceIdTest_noAccountType() {
    String resourceId = "bad:123456";

    assertThatThrownBy(() -> FieldParser.getDeviceIdForResourceId(resourceId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No matching accountType for deviceType=noAccountType");
  }

  @Test
  public void getDeviceIdForResourceIdTest_nullResource() {
    Pair<AccountType, String> accountAndDevice = FieldParser.getDeviceIdForResourceId(null);
    assertThat(accountAndDevice).isNull();
  }
}
