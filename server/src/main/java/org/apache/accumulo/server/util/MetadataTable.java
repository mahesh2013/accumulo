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
package org.apache.accumulo.server.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.IsolatedScanner;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.impl.BatchWriterImpl;
import org.apache.accumulo.core.client.impl.ScannerImpl;
import org.apache.accumulo.core.client.impl.Writer;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.file.FileUtil;
import org.apache.accumulo.core.security.CredentialHelper;
import org.apache.accumulo.core.security.thrift.TCredentials;
import org.apache.accumulo.core.tabletserver.thrift.ConstraintViolationException;
import org.apache.accumulo.core.util.CachedConfiguration;
import org.apache.accumulo.core.util.ColumnFQ;
import org.apache.accumulo.core.util.FastFormat;
import org.apache.accumulo.core.util.Pair;
import org.apache.accumulo.core.util.StringUtil;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.fate.zookeeper.IZooReaderWriter;
import org.apache.accumulo.fate.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.fate.zookeeper.ZooUtil.NodeMissingPolicy;
import org.apache.accumulo.server.ServerConstants;
import org.apache.accumulo.server.client.HdfsZooInstance;
import org.apache.accumulo.server.conf.ServerConfiguration;
import org.apache.accumulo.server.master.state.TServerInstance;
import org.apache.accumulo.server.security.SecurityConstants;
import org.apache.accumulo.server.trace.TraceFileSystem;
import org.apache.accumulo.server.zookeeper.ZooLock;
import org.apache.accumulo.server.zookeeper.ZooReaderWriter;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;

/**
 * provides a reference to the metadata table for updates by tablet servers
 */
public class MetadataTable extends org.apache.accumulo.core.util.MetadataTable {
  
  private static final Text EMPTY_TEXT = new Text();
  private static Map<TCredentials,Writer> metadata_tables = new HashMap<TCredentials,Writer>();
  private static final Logger log = Logger.getLogger(MetadataTable.class);
  
  private static final int SAVE_ROOT_TABLET_RETRIES = 3;
  
  private MetadataTable() {
    
  }
  
  public synchronized static Writer getMetadataTable(TCredentials credentials) {
    Writer metadataTable = metadata_tables.get(credentials);
    if (metadataTable == null) {
      metadataTable = new Writer(HdfsZooInstance.getInstance(), credentials, Constants.METADATA_TABLE_ID);
      metadata_tables.put(credentials, metadataTable);
    }
    return metadataTable;
  }
  
  public static void putLockID(ZooLock zooLock, Mutation m) {
    Constants.METADATA_LOCK_COLUMN.put(m, new Value(zooLock.getLockID().serialize(ZooUtil.getRoot(HdfsZooInstance.getInstance()) + "/").getBytes()));
  }
  
  public static void update(TCredentials credentials, Mutation m) {
    update(credentials, null, m);
  }
  
  public static void update(TCredentials credentials, ZooLock zooLock, Mutation m) {
    Writer t;
    t = getMetadataTable(credentials);
    if (zooLock != null)
      putLockID(zooLock, m);
    while (true) {
      try {
        t.update(m);
        return;
      } catch (AccumuloException e) {
        log.error(e, e);
      } catch (AccumuloSecurityException e) {
        log.error(e, e);
      } catch (ConstraintViolationException e) {
        log.error(e, e);
      } catch (TableNotFoundException e) {
        log.error(e, e);
      }
      UtilWaitThread.sleep(1000);
    }
    
  }
  
  /**
   * new data file update function adds one data file to a tablet's list
   * 
   * path should be relative to the table directory
   * 
   * @param time
   * @param filesInUseByScans
   * @param zooLock
   * @param flushId
   * 
   */
  public static void updateTabletDataFile(KeyExtent extent, String path, String mergeFile, DataFileValue dfv, String time, TCredentials credentials,
      Set<String> filesInUseByScans, String address, ZooLock zooLock, Set<String> unusedWalLogs, TServerInstance lastLocation, long flushId) {
    if (extent.equals(Constants.ROOT_TABLET_EXTENT)) {
      if (unusedWalLogs != null) {
        IZooReaderWriter zk = ZooReaderWriter.getInstance();
        // unusedWalLogs will contain the location/name of each log in a log set
        // the log set is stored under one of the log names, but not both
        // find the entry under one of the names and delete it.
        String root = getZookeeperLogLocation();
        boolean foundEntry = false;
        for (String entry : unusedWalLogs) {
          String[] parts = entry.split("/");
          String zpath = root + "/" + parts[1];
          while (true) {
            try {
              if (zk.exists(zpath)) {
                zk.recursiveDelete(zpath, NodeMissingPolicy.SKIP);
                foundEntry = true;
              }
              break;
            } catch (KeeperException e) {
              log.error(e, e);
            } catch (InterruptedException e) {
              log.error(e, e);
            }
            UtilWaitThread.sleep(1000);
          }
        }
        if (unusedWalLogs.size() > 0 && !foundEntry)
          log.warn("WALog entry for root tablet did not exist " + unusedWalLogs);
      }
      return;
    }
    
    Mutation m = new Mutation(extent.getMetadataEntry());
    
    if (dfv.getNumEntries() > 0) {
      m.put(Constants.METADATA_DATAFILE_COLUMN_FAMILY, new Text(path), new Value(dfv.encode()));
      Constants.METADATA_TIME_COLUMN.put(m, new Value(time.getBytes()));
      // stuff in this location
      TServerInstance self = getTServerInstance(address, zooLock);
      self.putLastLocation(m);
      // erase the old location
      if (lastLocation != null && !lastLocation.equals(self))
        lastLocation.clearLastLocation(m);
    }
    if (unusedWalLogs != null) {
      for (String entry : unusedWalLogs) {
        m.putDelete(Constants.METADATA_LOG_COLUMN_FAMILY, new Text(entry));
      }
    }
    
    for (String scanFile : filesInUseByScans)
      m.put(Constants.METADATA_SCANFILE_COLUMN_FAMILY, new Text(scanFile), new Value("".getBytes()));
    
    if (mergeFile != null)
      m.putDelete(Constants.METADATA_DATAFILE_COLUMN_FAMILY, new Text(mergeFile));
    
    Constants.METADATA_FLUSH_COLUMN.put(m, new Value((flushId + "").getBytes()));
    
    update(credentials, zooLock, m);
    
  }
  
  private static TServerInstance getTServerInstance(String address, ZooLock zooLock) {
    while (true) {
      try {
        return new TServerInstance(address, zooLock.getSessionId());
      } catch (KeeperException e) {
        log.error(e, e);
      } catch (InterruptedException e) {
        log.error(e, e);
      }
      UtilWaitThread.sleep(1000);
    }
  }
  
  public static void updateTabletFlushID(KeyExtent extent, long flushID, TCredentials credentials, ZooLock zooLock) {
    if (!extent.isRootTablet()) {
      Mutation m = new Mutation(extent.getMetadataEntry());
      Constants.METADATA_FLUSH_COLUMN.put(m, new Value((flushID + "").getBytes()));
      update(credentials, zooLock, m);
    }
  }
  
  public static void updateTabletCompactID(KeyExtent extent, long compactID, TCredentials credentials, ZooLock zooLock) {
    if (!extent.isRootTablet()) {
      Mutation m = new Mutation(extent.getMetadataEntry());
      Constants.METADATA_COMPACT_COLUMN.put(m, new Value((compactID + "").getBytes()));
      update(credentials, zooLock, m);
    }
  }
  
  public static void updateTabletDataFile(long tid, KeyExtent extent, Map<String,DataFileValue> estSizes, String time, TCredentials credentials, ZooLock zooLock) {
    Mutation m = new Mutation(extent.getMetadataEntry());
    byte[] tidBytes = Long.toString(tid).getBytes();
    
    for (Entry<String,DataFileValue> entry : estSizes.entrySet()) {
      Text file = new Text(entry.getKey());
      m.put(Constants.METADATA_DATAFILE_COLUMN_FAMILY, file, new Value(entry.getValue().encode()));
      m.put(Constants.METADATA_BULKFILE_COLUMN_FAMILY, file, new Value(tidBytes));
    }
    Constants.METADATA_TIME_COLUMN.put(m, new Value(time.getBytes()));
    update(credentials, zooLock, m);
  }
  
  public static void addTablet(KeyExtent extent, String path, TCredentials credentials, char timeType, ZooLock lock) {
    Mutation m = extent.getPrevRowUpdateMutation();
    
    Constants.METADATA_DIRECTORY_COLUMN.put(m, new Value(path.getBytes()));
    Constants.METADATA_TIME_COLUMN.put(m, new Value((timeType + "0").getBytes()));
    
    update(credentials, lock, m);
  }
  
  public static void updateTabletPrevEndRow(KeyExtent extent, TCredentials credentials) {
    Mutation m = extent.getPrevRowUpdateMutation(); //
    update(credentials, m);
  }
  
  /**
   * convenience method for reading entries from the metadata table
   */
  public static SortedMap<KeyExtent,Text> getMetadataDirectoryEntries(SortedMap<Key,Value> entries) {
    Key key;
    Value val;
    Text datafile = null;
    Value prevRow = null;
    KeyExtent ke;
    
    SortedMap<KeyExtent,Text> results = new TreeMap<KeyExtent,Text>();
    
    Text lastRowFromKey = new Text();
    
    // text obj below is meant to be reused in loop for efficiency
    Text colf = new Text();
    Text colq = new Text();
    
    for (Entry<Key,Value> entry : entries.entrySet()) {
      key = entry.getKey();
      val = entry.getValue();
      
      if (key.compareRow(lastRowFromKey) != 0) {
        prevRow = null;
        datafile = null;
        key.getRow(lastRowFromKey);
      }
      
      colf = key.getColumnFamily(colf);
      colq = key.getColumnQualifier(colq);
      
      // interpret the row id as a key extent
      if (Constants.METADATA_DIRECTORY_COLUMN.equals(colf, colq))
        datafile = new Text(val.toString());
      
      else if (Constants.METADATA_PREV_ROW_COLUMN.equals(colf, colq))
        prevRow = new Value(val);
      
      if (datafile != null && prevRow != null) {
        ke = new KeyExtent(key.getRow(), prevRow);
        results.put(ke, datafile);
        
        datafile = null;
        prevRow = null;
      }
    }
    return results;
  }
  
  public static boolean recordRootTabletLocation(String address) {
    IZooReaderWriter zoo = ZooReaderWriter.getInstance();
    for (int i = 0; i < SAVE_ROOT_TABLET_RETRIES; i++) {
      try {
        log.info("trying to write root tablet location to ZooKeeper as " + address);
        String zRootLocPath = ZooUtil.getRoot(HdfsZooInstance.getInstance()) + Constants.ZROOT_TABLET_LOCATION;
        zoo.putPersistentData(zRootLocPath, address.getBytes(), NodeExistsPolicy.OVERWRITE);
        return true;
      } catch (Exception e) {
        log.error("Master: unable to save root tablet location in zookeeper. exception: " + e, e);
      }
    }
    log.error("Giving up after " + SAVE_ROOT_TABLET_RETRIES + " retries");
    return false;
  }
  
  public static SortedMap<String,DataFileValue> getDataFileSizes(KeyExtent extent, TCredentials credentials) {
    TreeMap<String,DataFileValue> sizes = new TreeMap<String,DataFileValue>();
    
    Scanner mdScanner = new ScannerImpl(HdfsZooInstance.getInstance(), credentials, Constants.METADATA_TABLE_ID, Constants.NO_AUTHS);
    mdScanner.fetchColumnFamily(Constants.METADATA_DATAFILE_COLUMN_FAMILY);
    Text row = extent.getMetadataEntry();
    
    Key endKey = new Key(row, Constants.METADATA_DATAFILE_COLUMN_FAMILY, new Text(""));
    endKey = endKey.followingKey(PartialKey.ROW_COLFAM);
    
    mdScanner.setRange(new Range(new Key(row), endKey));
    for (Entry<Key,Value> entry : mdScanner) {
      
      if (!entry.getKey().getRow().equals(row))
        break;
      DataFileValue dfv = new DataFileValue(entry.getValue().get());
      sizes.put(entry.getKey().getColumnQualifier().toString(), dfv);
    }
    
    return sizes;
  }
  
  public static void addNewTablet(KeyExtent extent, String path, TServerInstance location, Map<String,DataFileValue> datafileSizes,
      Map<String,Long> bulkLoadedFiles, TCredentials credentials, String time, long lastFlushID, long lastCompactID, ZooLock zooLock) {
    Mutation m = extent.getPrevRowUpdateMutation();
    
    Constants.METADATA_DIRECTORY_COLUMN.put(m, new Value(path.getBytes()));
    Constants.METADATA_TIME_COLUMN.put(m, new Value(time.getBytes()));
    if (lastFlushID > 0)
      Constants.METADATA_FLUSH_COLUMN.put(m, new Value(("" + lastFlushID).getBytes()));
    if (lastCompactID > 0)
      Constants.METADATA_COMPACT_COLUMN.put(m, new Value(("" + lastCompactID).getBytes()));
    
    if (location != null) {
      m.put(Constants.METADATA_CURRENT_LOCATION_COLUMN_FAMILY, location.asColumnQualifier(), location.asMutationValue());
      m.putDelete(Constants.METADATA_FUTURE_LOCATION_COLUMN_FAMILY, location.asColumnQualifier());
    }
    
    for (Entry<String,DataFileValue> entry : datafileSizes.entrySet()) {
      m.put(Constants.METADATA_DATAFILE_COLUMN_FAMILY, new Text(entry.getKey()), new Value(entry.getValue().encode()));
    }
    
    for (Entry<String,Long> entry : bulkLoadedFiles.entrySet()) {
      byte[] tidBytes = Long.toString(entry.getValue()).getBytes();
      m.put(Constants.METADATA_BULKFILE_COLUMN_FAMILY, new Text(entry.getKey()), new Value(tidBytes));
    }
    
    update(credentials, zooLock, m);
  }
  
  public static void rollBackSplit(Text metadataEntry, Text oldPrevEndRow, TCredentials credentials, ZooLock zooLock) {
    KeyExtent ke = new KeyExtent(metadataEntry, oldPrevEndRow);
    Mutation m = ke.getPrevRowUpdateMutation();
    Constants.METADATA_SPLIT_RATIO_COLUMN.putDelete(m);
    Constants.METADATA_OLD_PREV_ROW_COLUMN.putDelete(m);
    update(credentials, zooLock, m);
  }

  public static void splitTablet(KeyExtent extent, Text oldPrevEndRow, double splitRatio, TCredentials credentials, ZooLock zooLock) {
    Mutation m = extent.getPrevRowUpdateMutation(); //
    
    Constants.METADATA_SPLIT_RATIO_COLUMN.put(m, new Value(Double.toString(splitRatio).getBytes()));
    
    Constants.METADATA_OLD_PREV_ROW_COLUMN.put(m, KeyExtent.encodePrevEndRow(oldPrevEndRow));
    Constants.METADATA_CHOPPED_COLUMN.putDelete(m);
    update(credentials, zooLock, m);
  }
  
  public static void finishSplit(Text metadataEntry, Map<String,DataFileValue> datafileSizes, List<String> highDatafilesToRemove, TCredentials credentials,
      ZooLock zooLock) {
    Mutation m = new Mutation(metadataEntry);
    Constants.METADATA_SPLIT_RATIO_COLUMN.putDelete(m);
    Constants.METADATA_OLD_PREV_ROW_COLUMN.putDelete(m);
    Constants.METADATA_CHOPPED_COLUMN.putDelete(m);
    
    for (Entry<String,DataFileValue> entry : datafileSizes.entrySet()) {
      m.put(Constants.METADATA_DATAFILE_COLUMN_FAMILY, new Text(entry.getKey()), new Value(entry.getValue().encode()));
    }
    
    for (String pathToRemove : highDatafilesToRemove) {
      m.putDelete(Constants.METADATA_DATAFILE_COLUMN_FAMILY, new Text(pathToRemove));
    }
    
    update(credentials, zooLock, m);
  }
  
  public static void finishSplit(KeyExtent extent, Map<String,DataFileValue> datafileSizes, List<String> highDatafilesToRemove, TCredentials credentials,
      ZooLock zooLock) {
    finishSplit(extent.getMetadataEntry(), datafileSizes, highDatafilesToRemove, credentials, zooLock);
  }
  
  public static void replaceDatafiles(KeyExtent extent, Set<String> datafilesToDelete, Set<String> scanFiles, String path, Long compactionId,
      DataFileValue size, TCredentials credentials, String address, TServerInstance lastLocation, ZooLock zooLock) {
    replaceDatafiles(extent, datafilesToDelete, scanFiles, path, compactionId, size, credentials, address, lastLocation, zooLock, true);
  }
  
  public static void replaceDatafiles(KeyExtent extent, Set<String> datafilesToDelete, Set<String> scanFiles, String path, Long compactionId,
      DataFileValue size, TCredentials credentials, String address, TServerInstance lastLocation, ZooLock zooLock, boolean insertDeleteFlags) {
    
    if (insertDeleteFlags) {
      // add delete flags for those paths before the data file reference is removed
      addDeleteEntries(extent, datafilesToDelete, credentials);
    }
    
    // replace data file references to old mapfiles with the new mapfiles
    Mutation m = new Mutation(extent.getMetadataEntry());
    
    for (String pathToRemove : datafilesToDelete)
      m.putDelete(Constants.METADATA_DATAFILE_COLUMN_FAMILY, new Text(pathToRemove));
    
    for (String scanFile : scanFiles)
      m.put(Constants.METADATA_SCANFILE_COLUMN_FAMILY, new Text(scanFile), new Value("".getBytes()));
    
    if (size.getNumEntries() > 0)
      m.put(Constants.METADATA_DATAFILE_COLUMN_FAMILY, new Text(path), new Value(size.encode()));
    
    if (compactionId != null)
      Constants.METADATA_COMPACT_COLUMN.put(m, new Value(("" + compactionId).getBytes()));
    
    TServerInstance self = getTServerInstance(address, zooLock);
    self.putLastLocation(m);
    
    // remove the old location
    if (lastLocation != null && !lastLocation.equals(self))
      lastLocation.clearLastLocation(m);
    
    update(credentials, zooLock, m);
  }
  
  public static void addDeleteEntries(KeyExtent extent, Set<String> datafilesToDelete, TCredentials credentials) {
    
    String tableId = extent.getTableId().toString();
    
    // TODO could use batch writer,would need to handle failure and retry like update does - ACCUMULO-1294
    for (String pathToRemove : datafilesToDelete)
      update(credentials, createDeleteMutation(tableId, pathToRemove));
  }
  
  public static void addDeleteEntry(String tableId, String path) {
    update(SecurityConstants.getSystemCredentials(), createDeleteMutation(tableId, path));
  }
  
  public static Mutation createDeleteMutation(String tableId, String pathToRemove) {
    Mutation delFlag;
    String prefix = Constants.METADATA_DELETE_FLAG_PREFIX;
    if (tableId.equals(Constants.METADATA_TABLE_ID))
      prefix = Constants.METADATA_DELETE_FLAG_FOR_METADATA_PREFIX;

    if (pathToRemove.startsWith("../"))
      delFlag = new Mutation(new Text(prefix + pathToRemove.substring(2)));
    else
      delFlag = new Mutation(new Text(prefix + "/" + tableId + pathToRemove));

    delFlag.put(EMPTY_TEXT, EMPTY_TEXT, new Value(new byte[] {}));
    return delFlag;
  }
  
  public static void removeScanFiles(KeyExtent extent, Set<String> scanFiles, TCredentials credentials, ZooLock zooLock) {
    Mutation m = new Mutation(extent.getMetadataEntry());
    
    for (String pathToRemove : scanFiles)
      m.putDelete(Constants.METADATA_SCANFILE_COLUMN_FAMILY, new Text(pathToRemove));
    
    update(credentials, zooLock, m);
  }
  
  private static KeyExtent fixSplit(Text table, Text metadataEntry, Text metadataPrevEndRow, Value oper, double splitRatio, TServerInstance tserver,
      TCredentials credentials, String time, long initFlushID, long initCompactID, ZooLock lock) throws AccumuloException {
    if (metadataPrevEndRow == null)
      // something is wrong, this should not happen... if a tablet is split, it will always have a
      // prev end row....
      throw new AccumuloException("Split tablet does not have prev end row, something is amiss, extent = " + metadataEntry);
    
    // check to see if prev tablet exist in metadata tablet
    Key prevRowKey = new Key(new Text(KeyExtent.getMetadataEntry(table, metadataPrevEndRow)));

    ScannerImpl scanner2 = new ScannerImpl(HdfsZooInstance.getInstance(), credentials, Constants.METADATA_TABLE_ID, Constants.NO_AUTHS);
    scanner2.setRange(new Range(prevRowKey, prevRowKey.followingKey(PartialKey.ROW)));
    
    if (!scanner2.iterator().hasNext()) {
      log.info("Rolling back incomplete split " + metadataEntry + " " + metadataPrevEndRow);
      rollBackSplit(metadataEntry, KeyExtent.decodePrevEndRow(oper), credentials, lock);
      return new KeyExtent(metadataEntry, KeyExtent.decodePrevEndRow(oper));
    } else {
      log.info("Finishing incomplete split " + metadataEntry + " " + metadataPrevEndRow);

      List<String> highDatafilesToRemove = new ArrayList<String>();

      Scanner scanner3 = new ScannerImpl(HdfsZooInstance.getInstance(), credentials, Constants.METADATA_TABLE_ID, Constants.NO_AUTHS);
      Key rowKey = new Key(metadataEntry);
      
      SortedMap<String,DataFileValue> origDatafileSizes = new TreeMap<String,DataFileValue>();
      SortedMap<String,DataFileValue> highDatafileSizes = new TreeMap<String,DataFileValue>();
      SortedMap<String,DataFileValue> lowDatafileSizes = new TreeMap<String,DataFileValue>();
      scanner3.fetchColumnFamily(Constants.METADATA_DATAFILE_COLUMN_FAMILY);
      scanner3.setRange(new Range(rowKey, rowKey.followingKey(PartialKey.ROW)));
      
      for (Entry<Key,Value> entry : scanner3) {
        if (entry.getKey().compareColumnFamily(Constants.METADATA_DATAFILE_COLUMN_FAMILY) == 0) {
          origDatafileSizes.put(entry.getKey().getColumnQualifier().toString(), new DataFileValue(entry.getValue().get()));
        }
      }
      
      splitDatafiles(table, metadataPrevEndRow, splitRatio, new HashMap<String,FileUtil.FileInfo>(), origDatafileSizes, lowDatafileSizes, highDatafileSizes,
          highDatafilesToRemove);
    
      MetadataTable.finishSplit(metadataEntry, highDatafileSizes, highDatafilesToRemove, credentials, lock);
      
      return new KeyExtent(metadataEntry, KeyExtent.encodePrevEndRow(metadataPrevEndRow));
    }


  }
  
  public static void splitDatafiles(Text table, Text midRow, double splitRatio, Map<String,FileUtil.FileInfo> firstAndLastRows,
      SortedMap<String,DataFileValue> datafiles, SortedMap<String,DataFileValue> lowDatafileSizes, SortedMap<String,DataFileValue> highDatafileSizes,
      List<String> highDatafilesToRemove) {
    
    for (Entry<String,DataFileValue> entry : datafiles.entrySet()) {
      
      Text firstRow = null;
      Text lastRow = null;
      
      boolean rowsKnown = false;
      
      FileUtil.FileInfo mfi = firstAndLastRows.get(entry.getKey());
      
      if (mfi != null) {
        firstRow = mfi.getFirstRow();
        lastRow = mfi.getLastRow();
        rowsKnown = true;
      }
      
      if (rowsKnown && firstRow.compareTo(midRow) > 0) {
        // only in high
        long highSize = entry.getValue().getSize();
        long highEntries = entry.getValue().getNumEntries();
        highDatafileSizes.put(entry.getKey(), new DataFileValue(highSize, highEntries, entry.getValue().getTime()));
      } else if (rowsKnown && lastRow.compareTo(midRow) <= 0) {
        // only in low
        long lowSize = entry.getValue().getSize();
        long lowEntries = entry.getValue().getNumEntries();
        lowDatafileSizes.put(entry.getKey(), new DataFileValue(lowSize, lowEntries, entry.getValue().getTime()));
        
        highDatafilesToRemove.add(entry.getKey());
      } else {
        long lowSize = (long) Math.floor((entry.getValue().getSize() * splitRatio));
        long lowEntries = (long) Math.floor((entry.getValue().getNumEntries() * splitRatio));
        lowDatafileSizes.put(entry.getKey(), new DataFileValue(lowSize, lowEntries, entry.getValue().getTime()));
        
        long highSize = (long) Math.ceil((entry.getValue().getSize() * (1.0 - splitRatio)));
        long highEntries = (long) Math.ceil((entry.getValue().getNumEntries() * (1.0 - splitRatio)));
        highDatafileSizes.put(entry.getKey(), new DataFileValue(highSize, highEntries, entry.getValue().getTime()));
      }
    }
  }
  
  public static KeyExtent fixSplit(Text metadataEntry, SortedMap<ColumnFQ,Value> columns, TServerInstance tserver, TCredentials credentials, ZooLock lock)
      throws AccumuloException {
    log.info("Incomplete split " + metadataEntry + " attempting to fix");
    
    Value oper = columns.get(Constants.METADATA_OLD_PREV_ROW_COLUMN);
    
    if (columns.get(Constants.METADATA_SPLIT_RATIO_COLUMN) == null) {
      throw new IllegalArgumentException("Metadata entry does not have split ratio (" + metadataEntry + ")");
    }
    
    double splitRatio = Double.parseDouble(new String(columns.get(Constants.METADATA_SPLIT_RATIO_COLUMN).get()));
    
    Value prevEndRowIBW = columns.get(Constants.METADATA_PREV_ROW_COLUMN);
    
    if (prevEndRowIBW == null) {
      throw new IllegalArgumentException("Metadata entry does not have prev row (" + metadataEntry + ")");
    }
    
    Value time = columns.get(Constants.METADATA_TIME_COLUMN);
    
    if (time == null) {
      throw new IllegalArgumentException("Metadata entry does not have time (" + metadataEntry + ")");
    }
    
    Value flushID = columns.get(Constants.METADATA_FLUSH_COLUMN);
    long initFlushID = -1;
    if (flushID != null)
      initFlushID = Long.parseLong(flushID.toString());
    
    Value compactID = columns.get(Constants.METADATA_COMPACT_COLUMN);
    long initCompactID = -1;
    if (compactID != null)
      initCompactID = Long.parseLong(compactID.toString());
    
    Text metadataPrevEndRow = KeyExtent.decodePrevEndRow(prevEndRowIBW);
    
    Text table = (new KeyExtent(metadataEntry, (Text) null)).getTableId();
    
    return fixSplit(table, metadataEntry, metadataPrevEndRow, oper, splitRatio, tserver, credentials, time.toString(), initFlushID, initCompactID, lock);
  }
  
  public static void deleteTable(String tableId, boolean insertDeletes, TCredentials credentials, ZooLock lock) throws AccumuloException {
    Scanner ms = new ScannerImpl(HdfsZooInstance.getInstance(), credentials, Constants.METADATA_TABLE_ID, Constants.NO_AUTHS);
    Text tableIdText = new Text(tableId);
    BatchWriter bw = new BatchWriterImpl(HdfsZooInstance.getInstance(), credentials, Constants.METADATA_TABLE_ID, new BatchWriterConfig().setMaxMemory(1000000)
        .setMaxLatency(120000l, TimeUnit.MILLISECONDS).setMaxWriteThreads(2));
    
    // scan metadata for our table and delete everything we find
    Mutation m = null;
    ms.setRange(new KeyExtent(tableIdText, null, null).toMetadataRange());
    
    // insert deletes before deleting data from !METADATA... this makes the code fault tolerant
    if (insertDeletes) {
      
      ms.fetchColumnFamily(Constants.METADATA_DATAFILE_COLUMN_FAMILY);
      Constants.METADATA_DIRECTORY_COLUMN.fetch(ms);
      
      for (Entry<Key,Value> cell : ms) {
        Key key = cell.getKey();
        
        if (key.getColumnFamily().equals(Constants.METADATA_DATAFILE_COLUMN_FAMILY)) {
          String relPath = key.getColumnQualifier().toString();
          // only insert deletes for files owned by this table
          if (!relPath.startsWith("../"))
            bw.addMutation(createDeleteMutation(tableId, relPath));
        }
        
        if (Constants.METADATA_DIRECTORY_COLUMN.hasColumns(key)) {
          bw.addMutation(createDeleteMutation(tableId, cell.getValue().toString()));
        }
      }
      
      bw.flush();
      
      ms.clearColumns();
    }
    
    for (Entry<Key,Value> cell : ms) {
      Key key = cell.getKey();
      
      if (m == null) {
        m = new Mutation(key.getRow());
        if (lock != null)
          putLockID(lock, m);
      }
      
      if (key.getRow().compareTo(m.getRow(), 0, m.getRow().length) != 0) {
        bw.addMutation(m);
        m = new Mutation(key.getRow());
        if (lock != null)
          putLockID(lock, m);
      }
      m.putDelete(key.getColumnFamily(), key.getColumnQualifier());
    }
    
    if (m != null)
      bw.addMutation(m);
    
    bw.close();
  }
  
  public static class LogEntry {
    public KeyExtent extent;
    public long timestamp;
    public String server;
    public String filename;
    public int tabletId;
    public Collection<String> logSet;
    
    @Override
    public String toString() {
      return extent.toString() + " " + filename + " (" + tabletId + ")";
    }
    
    public String getName() {
      return server + "/" + filename;
    }
    
    public byte[] toBytes() throws IOException {
      DataOutputBuffer out = new DataOutputBuffer();
      extent.write(out);
      out.writeLong(timestamp);
      out.writeUTF(server);
      out.writeUTF(filename);
      out.write(tabletId);
      out.write(logSet.size());
      for (String s : logSet) {
        out.writeUTF(s);
      }
      return Arrays.copyOf(out.getData(), out.getLength());
    }
    
    public void fromBytes(byte bytes[]) throws IOException {
      DataInputBuffer inp = new DataInputBuffer();
      inp.reset(bytes, bytes.length);
      extent = new KeyExtent();
      extent.readFields(inp);
      timestamp = inp.readLong();
      server = inp.readUTF();
      filename = inp.readUTF();
      tabletId = inp.read();
      int count = inp.read();
      ArrayList<String> logSet = new ArrayList<String>(count);
      for (int i = 0; i < count; i++)
        logSet.add(inp.readUTF());
      this.logSet = logSet;
    }
    
  }
  
  private static String getZookeeperLogLocation() {
    return ZooUtil.getRoot(HdfsZooInstance.getInstance()) + Constants.ZROOT_TABLET_WALOGS;
  }
  
  public static void addLogEntry(TCredentials credentials, LogEntry entry, ZooLock zooLock) {
    if (entry.extent.isRootTablet()) {
      String root = getZookeeperLogLocation();
      while (true) {
        try {
          IZooReaderWriter zoo = ZooReaderWriter.getInstance();
          if (zoo.isLockHeld(zooLock.getLockID()))
            zoo.putPersistentData(root + "/" + entry.filename, entry.toBytes(), NodeExistsPolicy.OVERWRITE);
          break;
        } catch (KeeperException e) {
          log.error(e, e);
        } catch (InterruptedException e) {
          log.error(e, e);
        } catch (IOException e) {
          log.error(e, e);
        }
        UtilWaitThread.sleep(1000);
      }
    } else {
      String value = StringUtil.join(entry.logSet, ";") + "|" + entry.tabletId;
      Mutation m = new Mutation(entry.extent.getMetadataEntry());
      m.put(Constants.METADATA_LOG_COLUMN_FAMILY, new Text(entry.server + "/" + entry.filename), new Value(value.getBytes()));
      update(credentials, zooLock, m);
    }
  }
  
  public static LogEntry entryFromKeyValue(Key key, Value value) {
    MetadataTable.LogEntry e = new MetadataTable.LogEntry();
    e.extent = new KeyExtent(key.getRow(), EMPTY_TEXT);
    String[] parts = key.getColumnQualifier().toString().split("/");
    e.server = parts[0];
    e.filename = parts[1];
    parts = value.toString().split("\\|");
    e.tabletId = Integer.parseInt(parts[1]);
    e.logSet = Arrays.asList(parts[0].split(";"));
    e.timestamp = key.getTimestamp();
    return e;
  }
  
  public static Pair<List<LogEntry>,SortedMap<String,DataFileValue>> getFileAndLogEntries(TCredentials credentials, KeyExtent extent) throws KeeperException,
      InterruptedException, IOException {
    ArrayList<LogEntry> result = new ArrayList<LogEntry>();
    TreeMap<String,DataFileValue> sizes = new TreeMap<String,DataFileValue>();
    
    if (extent.isRootTablet()) {
      getRootLogEntries(result);
      FileSystem fs = TraceFileSystem.wrap(FileUtil.getFileSystem(CachedConfiguration.getInstance(), ServerConfiguration.getSiteConfiguration()));
      FileStatus[] files = fs.listStatus(new Path(ServerConstants.getRootTabletDir()));
      
      for (FileStatus fileStatus : files) {
        if (fileStatus.getPath().toString().endsWith("_tmp")) {
          continue;
        }
        DataFileValue dfv = new DataFileValue(0, 0);
        sizes.put(Constants.ZROOT_TABLET + "/" + fileStatus.getPath().getName(), dfv);
      }
      
    } else {
      Scanner scanner = new ScannerImpl(HdfsZooInstance.getInstance(), credentials, Constants.METADATA_TABLE_ID, Constants.NO_AUTHS);
      scanner.fetchColumnFamily(Constants.METADATA_LOG_COLUMN_FAMILY);
      scanner.fetchColumnFamily(Constants.METADATA_DATAFILE_COLUMN_FAMILY);
      scanner.setRange(extent.toMetadataRange());
      
      for (Entry<Key,Value> entry : scanner) {
        if (!entry.getKey().getRow().equals(extent.getMetadataEntry())) {
          throw new RuntimeException("Unexpected row " + entry.getKey().getRow() + " expected " + extent.getMetadataEntry());
        }
        
        if (entry.getKey().getColumnFamily().equals(Constants.METADATA_LOG_COLUMN_FAMILY)) {
          result.add(entryFromKeyValue(entry.getKey(), entry.getValue()));
        } else if (entry.getKey().getColumnFamily().equals(Constants.METADATA_DATAFILE_COLUMN_FAMILY)) {
          DataFileValue dfv = new DataFileValue(entry.getValue().get());
          sizes.put(entry.getKey().getColumnQualifier().toString(), dfv);
        } else {
          throw new RuntimeException("Unexpected col fam " + entry.getKey().getColumnFamily());
        }
      }
    }
    
    return new Pair<List<LogEntry>,SortedMap<String,DataFileValue>>(result, sizes);
  }
  
  public static List<LogEntry> getLogEntries(TCredentials credentials, KeyExtent extent) throws IOException, KeeperException, InterruptedException {
    log.info("Scanning logging entries for " + extent);
    ArrayList<LogEntry> result = new ArrayList<LogEntry>();
    if (extent.equals(Constants.ROOT_TABLET_EXTENT)) {
      log.info("Getting logs for root tablet from zookeeper");
      getRootLogEntries(result);
    } else {
      log.info("Scanning metadata for logs used for tablet " + extent);
      Scanner scanner = getTabletLogScanner(credentials, extent);
      Text pattern = extent.getMetadataEntry();
      for (Entry<Key,Value> entry : scanner) {
        Text row = entry.getKey().getRow();
        if (entry.getKey().getColumnFamily().equals(Constants.METADATA_LOG_COLUMN_FAMILY)) {
          if (row.equals(pattern)) {
            result.add(entryFromKeyValue(entry.getKey(), entry.getValue()));
          }
        }
      }
    }
    
    Collections.sort(result, new Comparator<LogEntry>() {
      @Override
      public int compare(LogEntry o1, LogEntry o2) {
        long diff = o1.timestamp - o2.timestamp;
        if (diff < 0)
          return -1;
        if (diff > 0)
          return 1;
        return 0;
      }
    });
    log.info("Returning logs " + result + " for extent " + extent);
    return result;
  }
  
  private static void getRootLogEntries(ArrayList<LogEntry> result) throws KeeperException, InterruptedException, IOException {
    IZooReaderWriter zoo = ZooReaderWriter.getInstance();
    String root = getZookeeperLogLocation();
    // there's a little race between getting the children and fetching 
    // the data.  The log can be removed in between.
    while (true) {
      result.clear();
      for (String child : zoo.getChildren(root)) {
        LogEntry e = new LogEntry();
        try {
          e.fromBytes(zoo.getData(root + "/" + child, null));
          result.add(e);
        } catch (KeeperException.NoNodeException ex) {
          continue;
        }
      }
      break;
    }
  }
  
  private static Scanner getTabletLogScanner(TCredentials credentials, KeyExtent extent) {
    Scanner scanner = new ScannerImpl(HdfsZooInstance.getInstance(), credentials, Constants.METADATA_TABLE_ID, Constants.NO_AUTHS);
    scanner.fetchColumnFamily(Constants.METADATA_LOG_COLUMN_FAMILY);
    Text start = extent.getMetadataEntry();
    Key endKey = new Key(start, Constants.METADATA_LOG_COLUMN_FAMILY);
    endKey = endKey.followingKey(PartialKey.ROW_COLFAM);
    scanner.setRange(new Range(new Key(start), endKey));
    return scanner;
  }
  
  static class LogEntryIterator implements Iterator<LogEntry> {
    
    Iterator<LogEntry> rootTabletEntries = null;
    Iterator<Entry<Key,Value>> metadataEntries = null;
    
    LogEntryIterator(TCredentials creds) throws IOException, KeeperException, InterruptedException {
      rootTabletEntries = getLogEntries(creds, Constants.ROOT_TABLET_EXTENT).iterator();
      try {
        Scanner scanner = HdfsZooInstance.getInstance().getConnector(creds.getPrincipal(), CredentialHelper.extractToken(creds))
            .createScanner(Constants.METADATA_TABLE_NAME, Constants.NO_AUTHS);
        scanner.fetchColumnFamily(Constants.METADATA_LOG_COLUMN_FAMILY);
        metadataEntries = scanner.iterator();
      } catch (Exception ex) {
        throw new IOException(ex);
      }
    }
    
    @Override
    public boolean hasNext() {
      return rootTabletEntries.hasNext() || metadataEntries.hasNext();
    }
    
    @Override
    public LogEntry next() {
      if (rootTabletEntries.hasNext()) {
        return rootTabletEntries.next();
      }
      Entry<Key,Value> entry = metadataEntries.next();
      return entryFromKeyValue(entry.getKey(), entry.getValue());
    }
    
    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }
  
  public static Iterator<LogEntry> getLogEntries(TCredentials creds) throws IOException, KeeperException, InterruptedException {
    return new LogEntryIterator(creds);
  }
  
  public static void removeUnusedWALEntries(KeyExtent extent, List<LogEntry> logEntries, ZooLock zooLock) {
    for (LogEntry entry : logEntries) {
      if (entry.extent.isRootTablet()) {
        String root = getZookeeperLogLocation();
        while (true) {
          try {
            IZooReaderWriter zoo = ZooReaderWriter.getInstance();
            if (zoo.isLockHeld(zooLock.getLockID()))
              zoo.recursiveDelete(root + "/" + entry.filename, NodeMissingPolicy.SKIP);
            break;
          } catch (Exception e) {
            log.error(e, e);
          }
          UtilWaitThread.sleep(1000);
        }
      } else {
        Mutation m = new Mutation(entry.extent.getMetadataEntry());
        m.putDelete(Constants.METADATA_LOG_COLUMN_FAMILY, new Text(entry.server + "/" + entry.filename));
        update(SecurityConstants.getSystemCredentials(), zooLock, m);
      }
    }
  }
  
  private static void getFiles(Set<String> files, Map<Key,Value> tablet, String srcTableId) {
    for (Entry<Key,Value> entry : tablet.entrySet()) {
      if (entry.getKey().getColumnFamily().equals(Constants.METADATA_DATAFILE_COLUMN_FAMILY)) {
        String cf = entry.getKey().getColumnQualifier().toString();
        if (srcTableId != null && !cf.startsWith("../"))
          cf = "../" + srcTableId + entry.getKey().getColumnQualifier();
        files.add(cf);
      }
    }
  }
  
  private static Mutation createCloneMutation(String srcTableId, String tableId, Map<Key,Value> tablet) {
    
    KeyExtent ke = new KeyExtent(tablet.keySet().iterator().next().getRow(), (Text) null);
    Mutation m = new Mutation(KeyExtent.getMetadataEntry(new Text(tableId), ke.getEndRow()));
    
    for (Entry<Key,Value> entry : tablet.entrySet()) {
      if (entry.getKey().getColumnFamily().equals(Constants.METADATA_DATAFILE_COLUMN_FAMILY)) {
        String cf = entry.getKey().getColumnQualifier().toString();
        if (!cf.startsWith("../"))
          cf = "../" + srcTableId + entry.getKey().getColumnQualifier();
        m.put(entry.getKey().getColumnFamily(), new Text(cf), entry.getValue());
      } else if (entry.getKey().getColumnFamily().equals(Constants.METADATA_CURRENT_LOCATION_COLUMN_FAMILY)) {
        m.put(Constants.METADATA_LAST_LOCATION_COLUMN_FAMILY, entry.getKey().getColumnQualifier(), entry.getValue());
      } else if (entry.getKey().getColumnFamily().equals(Constants.METADATA_LAST_LOCATION_COLUMN_FAMILY)) {
        // skip
      } else {
        m.put(entry.getKey().getColumnFamily(), entry.getKey().getColumnQualifier(), entry.getValue());
      }
    }
    return m;
  }
  
  private static Scanner createCloneScanner(String tableId, Connector conn) throws TableNotFoundException {
    Scanner mscanner = new IsolatedScanner(conn.createScanner(Constants.METADATA_TABLE_NAME, Constants.NO_AUTHS));
    mscanner.setRange(new KeyExtent(new Text(tableId), null, null).toMetadataRange());
    mscanner.fetchColumnFamily(Constants.METADATA_DATAFILE_COLUMN_FAMILY);
    mscanner.fetchColumnFamily(Constants.METADATA_CURRENT_LOCATION_COLUMN_FAMILY);
    mscanner.fetchColumnFamily(Constants.METADATA_LAST_LOCATION_COLUMN_FAMILY);
    mscanner.fetchColumnFamily(Constants.METADATA_CLONED_COLUMN_FAMILY);
    Constants.METADATA_PREV_ROW_COLUMN.fetch(mscanner);
    Constants.METADATA_TIME_COLUMN.fetch(mscanner);
    return mscanner;
  }
  
  static void initializeClone(String srcTableId, String tableId, Connector conn, BatchWriter bw) throws TableNotFoundException, MutationsRejectedException {
    TabletIterator ti = new TabletIterator(createCloneScanner(srcTableId, conn), new KeyExtent(new Text(srcTableId), null, null).toMetadataRange(), true, true);
    
    if (!ti.hasNext())
      throw new RuntimeException(" table deleted during clone?  srcTableId = " + srcTableId);
    
    while (ti.hasNext())
      bw.addMutation(createCloneMutation(srcTableId, tableId, ti.next()));
    
    bw.flush();
  }
  
  static int compareEndRows(Text endRow1, Text endRow2) {
    return new KeyExtent(new Text("0"), endRow1, null).compareTo(new KeyExtent(new Text("0"), endRow2, null));
  }
  
  static int checkClone(String srcTableId, String tableId, Connector conn, BatchWriter bw) throws TableNotFoundException, MutationsRejectedException {
    TabletIterator srcIter = new TabletIterator(createCloneScanner(srcTableId, conn), new KeyExtent(new Text(srcTableId), null, null).toMetadataRange(), true,
        true);
    TabletIterator cloneIter = new TabletIterator(createCloneScanner(tableId, conn), new KeyExtent(new Text(tableId), null, null).toMetadataRange(), true, true);
    
    if (!cloneIter.hasNext() || !srcIter.hasNext())
      throw new RuntimeException(" table deleted during clone?  srcTableId = " + srcTableId + " tableId=" + tableId);
    
    int rewrites = 0;
    
    while (cloneIter.hasNext()) {
      Map<Key,Value> cloneTablet = cloneIter.next();
      Text cloneEndRow = new KeyExtent(cloneTablet.keySet().iterator().next().getRow(), (Text) null).getEndRow();
      HashSet<String> cloneFiles = new HashSet<String>();
      
      boolean cloneSuccessful = false;
      for (Entry<Key,Value> entry : cloneTablet.entrySet()) {
        if (entry.getKey().getColumnFamily().equals(Constants.METADATA_CLONED_COLUMN_FAMILY)) {
          cloneSuccessful = true;
          break;
        }
      }
      
      if (!cloneSuccessful)
        getFiles(cloneFiles, cloneTablet, null);
      
      List<Map<Key,Value>> srcTablets = new ArrayList<Map<Key,Value>>();
      Map<Key,Value> srcTablet = srcIter.next();
      srcTablets.add(srcTablet);
      
      Text srcEndRow = new KeyExtent(srcTablet.keySet().iterator().next().getRow(), (Text) null).getEndRow();
      
      int cmp = compareEndRows(cloneEndRow, srcEndRow);
      if (cmp < 0)
        throw new TabletIterator.TabletDeletedException("Tablets deleted from src during clone : " + cloneEndRow + " " + srcEndRow);
      
      HashSet<String> srcFiles = new HashSet<String>();
      if (!cloneSuccessful)
        getFiles(srcFiles, srcTablet, srcTableId);
      
      while (cmp > 0) {
        srcTablet = srcIter.next();
        srcTablets.add(srcTablet);
        srcEndRow = new KeyExtent(srcTablet.keySet().iterator().next().getRow(), (Text) null).getEndRow();
        cmp = compareEndRows(cloneEndRow, srcEndRow);
        if (cmp < 0)
          throw new TabletIterator.TabletDeletedException("Tablets deleted from src during clone : " + cloneEndRow + " " + srcEndRow);
        
        if (!cloneSuccessful)
          getFiles(srcFiles, srcTablet, srcTableId);
      }
      
      if (cloneSuccessful)
        continue;
      
      if (!srcFiles.containsAll(cloneFiles)) {
        // delete existing cloned tablet entry
        Mutation m = new Mutation(cloneTablet.keySet().iterator().next().getRow());
        
        for (Entry<Key,Value> entry : cloneTablet.entrySet()) {
          Key k = entry.getKey();
          m.putDelete(k.getColumnFamily(), k.getColumnQualifier(), k.getTimestamp());
        }
        
        bw.addMutation(m);
        
        for (Map<Key,Value> st : srcTablets)
          bw.addMutation(createCloneMutation(srcTableId, tableId, st));
        
        rewrites++;
      } else {
        // write out marker that this tablet was successfully cloned
        Mutation m = new Mutation(cloneTablet.keySet().iterator().next().getRow());
        m.put(Constants.METADATA_CLONED_COLUMN_FAMILY, new Text(""), new Value("OK".getBytes()));
        bw.addMutation(m);
      }
    }
    
    bw.flush();
    return rewrites;
  }
  
  public static void cloneTable(Instance instance, String srcTableId, String tableId) throws Exception {
    
    Connector conn = instance.getConnector(SecurityConstants.SYSTEM_PRINCIPAL, SecurityConstants.getSystemToken());
    BatchWriter bw = conn.createBatchWriter(Constants.METADATA_TABLE_NAME, new BatchWriterConfig());
    
    while (true) {
      
      try {
        initializeClone(srcTableId, tableId, conn, bw);
        
        // the following loop looks changes in the file that occurred during the copy.. if files were dereferenced then they could have been GCed
        
        while (true) {
          int rewrites = checkClone(srcTableId, tableId, conn, bw);
          
          if (rewrites == 0)
            break;
        }
        
        bw.flush();
        break;
        
      } catch (TabletIterator.TabletDeletedException tde) {
        // tablets were merged in the src table
        bw.flush();
        
        // delete what we have cloned and try again
        deleteTable(tableId, false, SecurityConstants.getSystemCredentials(), null);
        
        log.debug("Tablets merged in table " + srcTableId + " while attempting to clone, trying again");
        
        UtilWaitThread.sleep(100);
      }
    }
    
    // delete the clone markers and create directory entries
    Scanner mscanner = conn.createScanner(Constants.METADATA_TABLE_NAME, Constants.NO_AUTHS);
    mscanner.setRange(new KeyExtent(new Text(tableId), null, null).toMetadataRange());
    mscanner.fetchColumnFamily(Constants.METADATA_CLONED_COLUMN_FAMILY);
    
    int dirCount = 0;
    
    for (Entry<Key,Value> entry : mscanner) {
      Key k = entry.getKey();
      Mutation m = new Mutation(k.getRow());
      m.putDelete(k.getColumnFamily(), k.getColumnQualifier());
      Constants.METADATA_DIRECTORY_COLUMN.put(m, new Value(FastFormat.toZeroPaddedString(dirCount++, 8, 16, "/c-".getBytes())));
      bw.addMutation(m);
    }
    
    bw.close();
    
  }
  
  public static void chopped(KeyExtent extent, ZooLock zooLock) {
    Mutation m = new Mutation(extent.getMetadataEntry());
    Constants.METADATA_CHOPPED_COLUMN.put(m, new Value("chopped".getBytes()));
    update(SecurityConstants.getSystemCredentials(), zooLock, m);
  }
  
  public static void removeBulkLoadEntries(Connector conn, String tableId, long tid) throws Exception {
    Scanner mscanner = new IsolatedScanner(conn.createScanner(Constants.METADATA_TABLE_NAME, Constants.NO_AUTHS));
    mscanner.setRange(new KeyExtent(new Text(tableId), null, null).toMetadataRange());
    mscanner.fetchColumnFamily(Constants.METADATA_BULKFILE_COLUMN_FAMILY);
    BatchWriter bw = conn.createBatchWriter(Constants.METADATA_TABLE_NAME, new BatchWriterConfig());
    for (Entry<Key,Value> entry : mscanner) {
      log.debug("Looking at entry " + entry + " with tid " + tid);
      if (Long.parseLong(entry.getValue().toString()) == tid) {
        log.debug("deleting entry " + entry);
        Mutation m = new Mutation(entry.getKey().getRow());
        m.putDelete(entry.getKey().getColumnFamily(), entry.getKey().getColumnQualifier());
        bw.addMutation(m);
      }
    }
    bw.close();
  }
  
  public static List<String> getBulkFilesLoaded(Connector conn, KeyExtent extent, long tid) {
    List<String> result = new ArrayList<String>();
    try {
      Scanner mscanner = new IsolatedScanner(conn.createScanner(Constants.METADATA_TABLE_NAME, Constants.NO_AUTHS));
      mscanner.setRange(extent.toMetadataRange());
      mscanner.fetchColumnFamily(Constants.METADATA_BULKFILE_COLUMN_FAMILY);
      for (Entry<Key,Value> entry : mscanner) {
        if (Long.parseLong(entry.getValue().toString()) == tid) {
          result.add(entry.getKey().getColumnQualifier().toString());
        }
      }
      return result;
    } catch (TableNotFoundException ex) {
      // unlikely
      throw new RuntimeException("Onos! teh metadata table has vanished!!");
    }
  }
  
  public static Map<String,Long> getBulkFilesLoaded(TCredentials credentials, KeyExtent extent) {
    return getBulkFilesLoaded(credentials, extent.getMetadataEntry());
  }
  
  public static Map<String,Long> getBulkFilesLoaded(TCredentials credentials, Text metadataRow) {
    
    Map<String,Long> ret = new HashMap<String,Long>();
    
    Scanner scanner = new ScannerImpl(HdfsZooInstance.getInstance(), credentials, Constants.METADATA_TABLE_ID, Constants.NO_AUTHS);
    scanner.setRange(new Range(metadataRow));
    scanner.fetchColumnFamily(Constants.METADATA_BULKFILE_COLUMN_FAMILY);
    for (Entry<Key,Value> entry : scanner) {
      String file = entry.getKey().getColumnQualifier().toString();
      Long tid = Long.parseLong(entry.getValue().toString());
      
      ret.put(file, tid);
    }
    return ret;
  }
  
  public static void addBulkLoadInProgressFlag(String path) {
    
    Mutation m = new Mutation(Constants.METADATA_BLIP_FLAG_PREFIX + path);
    m.put(EMPTY_TEXT, EMPTY_TEXT, new Value(new byte[] {}));
    
    update(SecurityConstants.getSystemCredentials(), m);
  }
  
  public static void removeBulkLoadInProgressFlag(String path) {
    
    Mutation m = new Mutation(Constants.METADATA_BLIP_FLAG_PREFIX + path);
    m.putDelete(EMPTY_TEXT, EMPTY_TEXT);
    
    update(SecurityConstants.getSystemCredentials(), m);
  }

  public static void moveMetaDeleteMarkers(Instance instance, TCredentials creds) {
    // move delete markers from the normal delete keyspace to the root tablet delete keyspace if the files are for the !METADATA table
    Scanner scanner = new ScannerImpl(instance, creds, Constants.METADATA_TABLE_ID, Constants.NO_AUTHS);
    scanner.setRange(new Range(Constants.METADATA_DELETES_KEYSPACE));
    for (Entry<Key,Value> entry : scanner) {
      String row = entry.getKey().getRow().toString();
      if (row.startsWith(Constants.METADATA_DELETE_FLAG_PREFIX + "/" + Constants.METADATA_TABLE_ID)) {
        String filename = row.substring(Constants.METADATA_DELETE_FLAG_PREFIX.length());
        // add the new entry first
        log.info("Moving " + filename + " marker to the root tablet");
        Mutation m = new Mutation(Constants.METADATA_DELETE_FLAG_FOR_METADATA_PREFIX + filename);
        m.put(new byte[]{}, new byte[]{}, new byte[]{});
        update(creds, m);
        // remove the old entry
        m = new Mutation(entry.getKey().getRow());
        m.putDelete(new byte[]{}, new byte[]{});
        update(creds, m);
      } else {
        break;
      }
    }
    
  }
}
