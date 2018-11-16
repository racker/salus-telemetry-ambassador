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

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;

public class LabelRulesProcessorTest {

    @Test
    public void testProcess() throws IOException {

        final LabelRulesProcessor labelRulesProcessor =
                new LabelRulesProcessor(new ClassPathResource("test-label-rules.json"), new ObjectMapper());

        Map<String, String> given = new HashMap<>();
        given.put("os", "macos");
        given.put("arch", "amd64");

        Map<String, String> expected = new HashMap<>();
        expected.put("os", "DARWIN");
        expected.put("arch", "X86_64");

        final Map<String, String> result = labelRulesProcessor.process(given);

        assertEquals(expected, result);
    }

    @Test
    public void testProcessToUpper() throws IOException {

        final LabelRulesProcessor labelRulesProcessor =
                new LabelRulesProcessor(new ClassPathResource("test-label-rules.json"), new ObjectMapper());

        Map<String, String> given = new HashMap<>();
        given.put("os", "darwin");
        given.put("arch", "x86_64");

        Map<String, String> expected = new HashMap<>();
        expected.put("os", "DARWIN");
        expected.put("arch", "X86_64");

        final Map<String, String> result = labelRulesProcessor.process(given);

        assertEquals(expected, result);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDisallowed() throws IOException {
        final LabelRulesProcessor labelRulesProcessor =
                new LabelRulesProcessor(new ClassPathResource("test-label-rules.json"), new ObjectMapper());

        Map<String, String> given = new HashMap<>();
        given.put("os", "homegrown");
        given.put("arch", "onebit");

        final Map<String, String> result = labelRulesProcessor.process(given);
    }
}