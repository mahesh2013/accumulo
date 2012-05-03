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
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;

class IteratorAdapter implements Iterator<Entry<Key,Value>> {
  
  SortedKeyValueIterator<Key,Value> inner;
  
  public IteratorAdapter(SortedKeyValueIterator<Key,Value> inner) throws IOException {
    this.inner = inner;
  }
  
  @Override
  public boolean hasNext() {
    return inner.hasTop();
  }
  
  @Override
  public Entry<Key,Value> next() {
    try {
      Entry<Key,Value> result = new MockEntry(new Key(inner.getTopKey()), new Value(inner.getTopValue()));
      inner.next();
      return result;
    } catch (IOException ex) {
      throw new NoSuchElementException();
    }
  }
  
  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }
}