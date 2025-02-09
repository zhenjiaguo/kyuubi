/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.jdbc.hive;

import static org.apache.kyuubi.jdbc.hive.Utils.JdbcConnectionParams;
import static org.apache.kyuubi.jdbc.hive.Utils.extractURLComponents;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class UtilsTest {

  private String expectedHost;
  private String expectedPort;
  private String uri;

  @Parameterized.Parameters
  public static Collection<String[]> data() {
    return Arrays.asList(
        new String[][] {
          {"localhost", "10009", "jdbc:hive2:///db;k1=v1?k2=v2#k3=v3"},
          {"localhost", "10009", "jdbc:hive2:///"},
          {"localhost", "10009", "jdbc:kyuubi://"},
          {"localhost", "10009", "jdbc:hive2://"},
          {"hostname", "10018", "jdbc:hive2://hostname:10018/db;k1=v1?k2=v2#k3=v3"}
        });
  }

  public UtilsTest(String expectedHost, String expectedPort, String uri) {
    this.expectedHost = expectedHost;
    this.expectedPort = expectedPort;
    this.uri = uri;
  }

  @Test
  public void testExtractURLComponents() throws JdbcUriParseException {
    JdbcConnectionParams jdbcConnectionParams1 = extractURLComponents(uri, new Properties());
    assertEquals(expectedHost, jdbcConnectionParams1.getHost());
    assertEquals(Integer.parseInt(expectedPort), jdbcConnectionParams1.getPort());
  }
}
