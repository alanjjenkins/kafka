/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.security.oauthbearer.internals.unsecured;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class OAuthBearerScopeUtilsTest {
    @Test
    public void validScope() {
        for (String validScope : new String[] {"", "   ", "scope1", " scope1 ", "scope1 Scope2", "scope1   Scope2"}) {
            List<String> parsedScope = OAuthBearerScopeUtils.parseScope(validScope);
            if (validScope.trim().isEmpty()) {
                assertTrue(parsedScope.isEmpty());
            } else if (validScope.contains("Scope2")) {
                assertTrue(parsedScope.size() == 2 && parsedScope.get(0).equals("scope1")
                        && parsedScope.get(1).equals("Scope2"));
            } else {
                assertTrue(parsedScope.size() == 1 && parsedScope.get(0).equals("scope1"));
            }
        }
    }

    @Test
    public void invalidScope() {
        for (String invalidScope : new String[] {"\"foo", "\\foo"}) {
            try {
                OAuthBearerScopeUtils.parseScope(invalidScope);
                fail("did not detect invalid scope: " + invalidScope);
            } catch (OAuthBearerConfigException expected) {
                // empty
            }
        }
    }
}
