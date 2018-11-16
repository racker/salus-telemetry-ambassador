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

package com.rackspace.salus.telemetry.ambassador.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class LabelRulesProcessor {

    private final LabelRules labelRules;

    @Autowired
    public LabelRulesProcessor(@Value("classpath:label-rules.json") Resource labelRulesJson,
                               ObjectMapper objectMapper) throws IOException {

        try (InputStream inputStream = labelRulesJson.getInputStream()) {
            labelRules = objectMapper.readValue(inputStream, LabelRules.class);
        }
    }

    public Map<String, String> process(Map<String, String> labels) {
        final Map<String, String> normalized = new HashMap<>(labels.size());

        for (Map.Entry<String, String> entry : labels.entrySet()) {

            final String key = entry.getKey();

            final Map<String, String> labelMappings = labelRules.getMappings().get(key);
            if (labelMappings != null) {
                final String labelMapping = labelMappings.get(entry.getValue().toUpperCase());
                if (labelMapping != null) {
                    normalized.put(key, labelMapping);
                    continue;
                }
            }

            normalized.put(key, entry.getValue());
        }

        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            final String key = entry.getKey();
            final String value = entry.getValue();

            final List<String> allowedValues = labelRules.getConstrain().get(key);
            if (allowedValues != null) {
                if (!allowedValues.contains(value)) {
                    final String upperValue = value.toUpperCase();

                    if (allowedValues.contains(upperValue)) {
                        entry.setValue(upperValue);
                    }
                    else {
                        throw new IllegalArgumentException(
                                String.format("%s is not a valid value of the label %s",
                                value, key));
                    }
                }
            }
        }

        return normalized;
    }
}
