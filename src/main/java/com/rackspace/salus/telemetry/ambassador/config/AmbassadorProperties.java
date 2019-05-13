/*
 *    Copyright 2018 Rackspace US, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *
 */

package com.rackspace.salus.telemetry.ambassador.config;

import java.util.Collections;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties("ambassador")
@Component
@Data
public class AmbassadorProperties {
    String envoyRefreshInterval = "PT10S";
    long envoyRefreshParallelism = 5;

    /**
     * FOR DEVELOPMENT ONLY, disable TLS including mutual TLS authentication
     */
    boolean disableTls = false;
    // The following assume the working directory is $PROJECT_DIR$/dev
    String certChainPath = "certs/out/ambassador.pem";
    String trustCertPath = "certs/out/ca.pem";
    String keyPath = "certs/out/ambassador-pkcs8-key.pem";

    String vaultPkiRole = "telemetry-infra";
    String externalName = "localhost";
    List<String> altExternalNames = Collections.singletonList("127.0.0.1");

    long envoyLeaseSec = 30;

    /**
     * Only Envoys of these tenants are allowed to advertise a zone with prefixed with 'publicZonePrefix'
     */
    List<String> publicZoneTenants;

    /**
     * When calling APIs of other microservices, this will be the max attempts retried
     */
    int maxApiRetryAttempts = 10;
}
