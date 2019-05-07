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

package com.rackspace.salus.telemetry.ambassador.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

@Configuration
@EnableRetry
public class RetryConfig {

  private final AmbassadorProperties properties;

  @Autowired
  public RetryConfig(AmbassadorProperties properties) {
    this.properties = properties;
  }

  @Bean
  public RetryTemplate retryTemplate() {
    final RetryTemplate retryTemplate = new RetryTemplate();

    retryTemplate.setRetryPolicy(retryPolicy());
    retryTemplate.setBackOffPolicy(backoffPolicy());

    return retryTemplate;
  }

  @Bean
  BackOffPolicy backoffPolicy() {
    // use their defaults for now
    return new ExponentialBackOffPolicy();
  }

  @Bean
  RetryPolicy retryPolicy() {
    final SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
    retryPolicy.setMaxAttempts(properties.getMaxApiRetryAttempts());
    return retryPolicy;
  }
}
