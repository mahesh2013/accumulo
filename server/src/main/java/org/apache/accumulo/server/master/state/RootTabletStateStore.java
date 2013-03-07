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
package org.apache.accumulo.server.master.state;

import java.util.Iterator;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.security.thrift.TCredentials;

public class RootTabletStateStore extends MetaDataStateStore {
  
  public RootTabletStateStore(Instance instance, TCredentials auths, CurrentState state) {
    super(instance, auths, state);
  }
  
  @Override
  public Iterator<TabletLocationState> iterator() {
    return new MetaDataTableScanner(instance, auths, Constants.METADATA_ROOT_TABLET_KEYSPACE, state);
  }
  
  @Override
  public String name() {
    return "Non-Root Metadata Tablets";
  }
}
