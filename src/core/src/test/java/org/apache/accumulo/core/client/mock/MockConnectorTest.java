/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.core.client.mock;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.MultiTableBatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.aggregation.conf.AggregatorConfiguration;
import org.apache.hadoop.io.Text;
import org.junit.Test;

public class MockConnectorTest {
  Random random = new Random();
  
  static Text asText(int i) {
    return new Text(Integer.toHexString(i));
  }
  
  @Test
  public void testSunnyDay() throws Exception {
    Connector c = new MockConnector("root");
    c.tableOperations().create("test");
    BatchWriter bw = c.createBatchWriter("test", 10000L, 1000L, 4);
    for (int i = 0; i < 100; i++) {
      int r = random.nextInt();
      Mutation m = new Mutation(asText(r));
      m.put(asText(random.nextInt()), asText(random.nextInt()), new Value(Integer.toHexString(r).getBytes()));
      bw.addMutation(m);
    }
    bw.close();
    BatchScanner s = c.createBatchScanner("test", Constants.NO_AUTHS, 2);
    s.setRanges(Collections.singletonList(new Range()));
    Key key = null;
    int count = 0;
    for (Entry<Key,Value> entry : s) {
      if (key != null)
        assertTrue(key.compareTo(entry.getKey()) < 0);
      assertEquals(entry.getKey().getRow(), new Text(entry.getValue().get()));
      key = entry.getKey();
      count++;
    }
    assertEquals(100, count);
  }
  
  @Test
  public void testAggregation() throws Exception {
    MockInstance mockInstance = new MockInstance();
    Connector c = mockInstance.getConnector("root", new byte[] {});
    List<AggregatorConfiguration> aggregators = new ArrayList<AggregatorConfiguration>();
    aggregators.add(new AggregatorConfiguration(new Text("day"), "org.apache.accumulo.core.iterators.aggregation.StringSummation"));
    c.tableOperations().create("perDayCounts");
    c.tableOperations().addAggregators("perDayCounts", aggregators);
    String keys[][] = { {"foo", "day", "20080101"}, {"foo", "day", "20080101"}, {"foo", "day", "20080103"}, {"bar", "day", "20080101"},
        {"bar", "day", "20080101"},};
    BatchWriter bw = c.createBatchWriter("perDayCounts", 1000L, 1000L, 1);
    for (String elt[] : keys) {
      Mutation m = new Mutation(new Text(elt[0]));
      m.put(new Text(elt[1]), new Text(elt[2]), new Value("1".getBytes()));
      bw.addMutation(m);
    }
    bw.close();
    
    Scanner s = c.createScanner("perDayCounts", Constants.NO_AUTHS);
    Iterator<Entry<Key,Value>> iterator = s.iterator();
    assertTrue(iterator.hasNext());
    checkEntry(iterator.next(), "bar", "day", "20080101", "2");
    assertTrue(iterator.hasNext());
    checkEntry(iterator.next(), "foo", "day", "20080101", "2");
    assertTrue(iterator.hasNext());
    checkEntry(iterator.next(), "foo", "day", "20080103", "1");
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void testDelete() throws Exception {
    Connector c = new MockConnector("root");
    c.tableOperations().create("test");
    BatchWriter bw = c.createBatchWriter("test", 10000L, 1000L, 4);
    
    Mutation m1 = new Mutation("r1");
    
    m1.put("cf1", "cq1", 1, "v1");
    
    bw.addMutation(m1);
    bw.flush();
    
    Mutation m2 = new Mutation("r1");
    
    m2.putDelete("cf1", "cq1", 2);
    
    bw.addMutation(m2);
    bw.flush();
    
    Scanner scanner = c.createScanner("test", Constants.NO_AUTHS);
    
    int count = 0;
    for (@SuppressWarnings("unused")
    Entry<Key,Value> entry : scanner) {
      count++;
    }
    
    assertEquals(0, count);
    
    try {
      c.tableOperations().create("test_this_$tableName");
      assertTrue(false);
      
    } catch (IllegalArgumentException iae) {
      
    }
  }
  
  private void checkEntry(Entry<Key,Value> next, String row, String cf, String cq, String value) {
    assertEquals(row, next.getKey().getRow().toString());
    assertEquals(cf, next.getKey().getColumnFamily().toString());
    assertEquals(cq, next.getKey().getColumnQualifier().toString());
    assertEquals(value, next.getValue().toString());
  }
  
  public void testMockMultiTableBatchWriter() throws Exception {
    Connector c = new MockConnector("root");
    c.tableOperations().create("a");
    c.tableOperations().create("b");
    MultiTableBatchWriter bw = c.createMultiTableBatchWriter(10000L, 1000L, 4);
    Mutation m1 = new Mutation("r1");
    m1.put("cf1", "cq1", 1, "v1");
    BatchWriter b = bw.getBatchWriter("a");
    b.addMutation(m1);
    b.flush();
    b = bw.getBatchWriter("b");
    b.addMutation(m1);
    b.flush();
    
    Scanner scanner = c.createScanner("a", Constants.NO_AUTHS);
    int count = 0;
    for (@SuppressWarnings("unused")
    Entry<Key,Value> entry : scanner) {
      count++;
    }
    assertEquals(1, count);
    scanner = c.createScanner("b", Constants.NO_AUTHS);
    for (@SuppressWarnings("unused")
    Entry<Key,Value> entry : scanner) {
      count++;
    }
    assertEquals(1, count);

  }

}
