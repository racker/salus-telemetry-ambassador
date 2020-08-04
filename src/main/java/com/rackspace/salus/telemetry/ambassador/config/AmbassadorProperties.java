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

import java.util.Collections;
import java.util.List;
import javax.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("salus.ambassador")
@Component
@Data
@Validated
public class AmbassadorProperties {

    @NotEmpty
    String envoyRefreshInterval = "PT10S";
    @NotEmpty
    long envoyRefreshParallelism = 5;

    /**
     * FOR DEVELOPMENT ONLY, disable TLS including mutual TLS authentication
     */
    @NotEmpty
    boolean disableTls = false;
    // The following assume the working directory is $PROJECT_DIR$/dev
    @NotEmpty
    String certChainPath = "certs/out/ambassador.pem";
    @NotEmpty
    String trustCertPath = "certs/out/ca.pem";
    @NotEmpty
    String keyPath = "certs/out/ambassador-pkcs8-key.pem";
    @NotEmpty
    String vaultPkiRole = "telemetry-infra";
    @NotEmpty
    String externalName = "localhost";
    @NotEmpty
    List<String> altExternalNames = Collections.singletonList("127.0.0.1");

    @NotEmpty
    long envoyLeaseSec = 30;

    /**
     * Only Envoys of these tenants are allowed to advertise a zone with prefixed with 'publicZonePrefix'
     */
    @NotEmpty
    List<String> publicZoneTenants;

    /**
     * When calling APIs of other microservices, this will be the max attempts retried
     */
    @NotEmpty
    int maxApiRetryAttempts = 10;

    /**
     * Specifies the number of threads used to accept incoming gRPC connections.
     */
    @NotEmpty
    int grpcBossThreads = 2;
    /**
     * Specifies the number of threads used to process gRPC sockets. Can be set to zero to
     * use the default Netty calculation based on 2 * available processors.
     */
    @NotEmpty
    int grpcWorkerThreads = 8;

    /**
     * Specifies number of threads used to process server methods and etcd client calls.
     */
    @NotEmpty
    int asyncThreads = 8;
}
