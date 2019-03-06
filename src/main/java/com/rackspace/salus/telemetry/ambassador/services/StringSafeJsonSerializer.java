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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ExtendedSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * This is a serializer that delegates to {@link JsonSerializer} when the given payload isn't yet
 * a string.
 * @param <T> the incoming payload type
 */
public class StringSafeJsonSerializer<T> implements ExtendedSerializer<T> {

  private JsonSerializer<T> jsonSerializer;

  @Override
  public void configure(Map<String, ?> map, boolean isKey) {
    jsonSerializer = new JsonSerializer<>();
    jsonSerializer.configure(map, isKey);
  }

  @Override
  public byte[] serialize(String topic, T data) {
    if (data instanceof String) {
      return ((String)data).getBytes(StandardCharsets.UTF_8);
    }
    else {
      return jsonSerializer.serialize(topic, data);
    }
  }

  @Override
  public byte[] serialize(String topic, Headers headers, T data) {
    if (data instanceof String) {
      return ((String)data).getBytes(StandardCharsets.UTF_8);
    }
    else {
      return jsonSerializer.serialize(topic, headers, data);
    }
  }

  @Override
  public void close() {
    jsonSerializer.close();
  }
}
