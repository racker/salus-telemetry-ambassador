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
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.Mod10Check;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("salus.ambassador")
@Component
@Data
@Validated
public class AmbassadorProperties {

    @NotBlank
    String envoyRefreshInterval = "PT10S";

    @Min(1)
    long envoyRefreshParallelism = 5;

    /**
     * FOR DEVELOPMENT ONLY, disable TLS including mutual TLS authentication
     */
    boolean disableTls = false;
    // The following assume the working directory is $PROJECT_DIR$/dev
    @NotBlank
    String certChainPath = "certs/out/ambassador.pem";
    @NotBlank
    String trustCertPath = "certs/out/ca.pem";
    @NotBlank
    String keyPath = "certs/out/ambassador-pkcs8-key.pem";
    @NotBlank
    String vaultPkiRole = "telemetry-infra";
    @NotBlank
    String externalName = "localhost";
    @NotEmpty
    List<String> altExternalNames = Collections.singletonList("127.0.0.1");

    @Min(1)
    long envoyLeaseSec = 30;

    /**
     * Only Envoys of these tenants are allowed to advertise a zone with prefixed with 'publicZonePrefix'
     */
    @NotEmpty
    List<String> publicZoneTenants;

    /**
     * When calling APIs of other microservices, this will be the max attempts retried
     */
    @Min(1)
    int maxApiRetryAttempts = 10;

    /**
     * Specifies the number of threads used to accept incoming gRPC connections.
     */
    @Min(1)
    int grpcBossThreads = 2;
    /**
     * Specifies the number of threads used to process gRPC sockets. Can be set to zero to
     * use the default Netty calculation based on 2 * available processors.
     */
    @Min(1)
    int grpcWorkerThreads = 8;

    /**
     * Specifies number of threads used to process server methods and etcd client calls.
     */
    @Min(1)
    int asyncThreads = 8;
}
