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
package org.apache.accumulo.core.client.security.tokens;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.security.auth.DestroyFailedException;
import javax.security.auth.Destroyable;

import org.apache.hadoop.io.Writable;

/**
 * @since 1.5.0
 */
public interface AuthenticationToken extends Writable, Destroyable, Cloneable {
  public class Properties extends HashMap<String, char[]> implements Destroyable {
    private static final long serialVersionUID = 507486847276806489L;
    boolean destroyed = false;
    
    public char[] put(String key, CharSequence value) {
      char[] toPut = new char[value.length()];
      for (int i = 0; i < value.length(); i++)
        toPut[i] = value.charAt(i);
      return this.put(key, toPut);
    }
    
    public void putAllStrings(Map<String, ? extends CharSequence> map) {
      for (Map.Entry<String,? extends CharSequence> entry : map.entrySet()) {
        put(entry.getKey(), entry.getValue());
      }
    }

    @Override
    public void destroy() throws DestroyFailedException {
      for (String key : this.keySet()) {
        char[] val = this.get(key);
        Arrays.fill(val, (char) 0);
      }
      this.clear();
      destroyed = true;
    }

    @Override
    public boolean isDestroyed() {
      return destroyed;
    }
  }
  
  public static class TokenProperty implements Comparable<TokenProperty> {
    private String key, description;
    private boolean masked;
    
    public TokenProperty(String name, String description, boolean mask) {
      this.key = name;
      this.description = description;
      this.masked = mask;
    }
    
    public String toString() {
      return this.key + " - " + description;
    }
    
    public String getKey() {
      return this.key;
    }
    
    public String getDescription() {
      return this.description;
    }
    
    public boolean getMask() {
      return this.masked;
    }
    
    public int hashCode() {
      return key.hashCode();
    }
    
    public boolean equals(Object o) {
      if (o instanceof TokenProperty)
        return ((TokenProperty) o).key.equals(key);
      return false;
    }
    
    @Override
    public int compareTo(TokenProperty o) {
      return key.compareTo(o.key);
    }
  }
  
  public void init(Properties properties);
  
  public Set<TokenProperty> getProperties();
  public AuthenticationToken clone();
}
