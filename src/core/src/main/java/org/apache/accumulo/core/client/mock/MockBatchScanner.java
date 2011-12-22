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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.FilteringIterator;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.accumulo.core.iterators.SortedMapIterator;
import org.apache.accumulo.core.iterators.filter.Filter;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.commons.collections.iterators.IteratorChain;

public class MockBatchScanner extends MockScannerBase implements BatchScanner {
  
  final List<Range> ranges = new ArrayList<Range>();
  
  public MockBatchScanner(MockTable mockTable, Authorizations authorizations) {
    super(mockTable, authorizations);
  }
  
  @Override
  public void setRanges(Collection<Range> ranges) {
    this.ranges.clear();
    this.ranges.addAll(ranges);
  }
  
  static class RangesFilter implements Filter {
    List<Range> ranges;
    
    public RangesFilter(List<Range> ranges) {
      this.ranges = ranges;
    }
    
    @Override
    public void init(Map<String,String> options) {}
    
    @Override
    public boolean accept(Key k, Value v) {
      for (Range r : ranges) {
        if (r.contains(k))
          return true;
      }
      return false;
    }
  }
  
  @SuppressWarnings("unchecked")
  @Override
  public Iterator<Entry<Key,Value>> iterator() {
    IteratorChain chain = new IteratorChain();
    for (Range range : ranges) {
      SortedKeyValueIterator<Key,Value> i = new SortedMapIterator(table.table);
      try {
        i = createFilter(i);
        i.seek(range, createColumnBSS(columns), !columns.isEmpty());
        chain.addIterator(new IteratorAdapter(new FilteringIterator(i, Collections.singletonList(new RangesFilter(ranges)))));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return chain;
  }
  
  @Override
  public void close() {}
  
}
