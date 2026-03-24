/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver.querymatcher;

import static org.apache.hadoop.hbase.HConstants.EMPTY_START_ROW;

import java.io.IOException;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.ExtendedCell;
import org.apache.hadoop.hbase.KeepDeletedCells;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.regionserver.RegionCoprocessorHost;
import org.apache.hadoop.hbase.regionserver.ScanInfo;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Query matcher for compaction.
 */
@InterfaceAudience.Private
public abstract class CompactionScanQueryMatcher extends ScanQueryMatcher {

  /** readPoint over which the KVs are unconditionally included */
  protected final long maxReadPointToTrackVersions;

  /** Keeps track of deletes */
  protected final DeleteTracker deletes;

  /** whether to return deleted rows */
  protected final KeepDeletedCells keepDeletedCells;

  /** The effective max versions for this store, used to drop overwritten versions. */
  private final int cfMaxVersions;

  /** Total versions seen for the current column, including cell-level TTL expired ones. */
  private int columnVersions;

  /** Current column cell for detecting column boundaries. */
  private ExtendedCell columnCell;

  protected CompactionScanQueryMatcher(ScanInfo scanInfo, DeleteTracker deletes,
    ColumnTracker columnTracker, long readPointToUse, long oldestUnexpiredTS, long now) {
    super(createStartKeyFromRow(EMPTY_START_ROW, scanInfo), scanInfo, columnTracker,
      oldestUnexpiredTS, now);
    this.maxReadPointToTrackVersions = readPointToUse;
    this.deletes = deletes;
    this.keepDeletedCells = scanInfo.getKeepDeletedCells();
    this.cfMaxVersions = scanInfo.getMaxVersions();
  }

  @Override
  public void beforeShipped() throws IOException {
    super.beforeShipped();
    deletes.beforeShipped();
    if (columnCell != null) {
      this.columnCell = KeyValueUtil.toNewKeyCell(this.columnCell);
    }
  }

  /**
   * Track a version for the current column. Resets the counter when the column changes.
   * @return the new total version count for this column (including expired).
   */
  protected int trackColumnVersion(ExtendedCell cell) {
    if (columnCell == null || !CellUtil.matchingQualifier(cell, columnCell)) {
      columnVersions = 0;
      columnCell = cell;
    }
    return ++columnVersions;
  }

  /**
   * Check if this cell would have been pruned due to the CF version limit, considering all
   * versions including cell-level TTL expired ones. Should be called for non-delete Put cells
   * that passed preCheck and checkDeleted.
   * @return a non-null MatchCode if the cell should be skipped, null to continue normal matching.
   */
  protected MatchCode checkCFVersionLimit(ExtendedCell cell) {
    if (trackColumnVersion(cell) > cfMaxVersions) {
      return columns.getNextRowOrNextColumn(cell);
    }
    return null;
  }

  @Override
  public boolean hasNullColumnInQuery() {
    return true;
  }

  @Override
  public boolean isUserScan() {
    return false;
  }

  @Override
  public boolean moreRowsMayExistAfter(ExtendedCell cell) {
    return true;
  }

  @Override
  public Filter getFilter() {
    // no filter when compaction
    return null;
  }

  @Override
  public ExtendedCell getNextKeyHint(ExtendedCell cell) throws IOException {
    // no filter, so no key hint.
    return null;
  }

  @Override
  protected void reset() {
    deletes.reset();
    columnVersions = 0;
    columnCell = null;
  }

  protected final void trackDelete(ExtendedCell cell) {
    // If keepDeletedCells is true, then we only remove cells by versions or TTL during
    // compaction, so we do not need to track delete here.
    // If keepDeletedCells is TTL and the delete marker is expired, then we can make sure that the
    // minVerions is larger than 0(otherwise we will just return at preCheck). So here we still
    // need to track the delete marker to see if it masks some cells.
    if (
      keepDeletedCells == KeepDeletedCells.FALSE
        || (keepDeletedCells == KeepDeletedCells.TTL && cell.getTimestamp() < oldestUnexpiredTS)
    ) {
      deletes.add(cell);
    }
  }

  public static CompactionScanQueryMatcher create(ScanInfo scanInfo, ScanType scanType,
    long readPointToUse, long earliestPutTs, long oldestUnexpiredTS, long now,
    byte[] dropDeletesFromRow, byte[] dropDeletesToRow, RegionCoprocessorHost regionCoprocessorHost)
    throws IOException {
    Pair<DeleteTracker, ColumnTracker> trackers =
      getTrackers(regionCoprocessorHost, null, scanInfo, oldestUnexpiredTS, null);
    DeleteTracker deleteTracker = trackers.getFirst();
    ColumnTracker columnTracker = trackers.getSecond();
    if (dropDeletesFromRow == null) {
      if (scanType == ScanType.COMPACT_RETAIN_DELETES) {
        if (scanInfo.isNewVersionBehavior()) {
          return new IncludeAllCompactionQueryMatcher(scanInfo, deleteTracker, columnTracker,
            readPointToUse, oldestUnexpiredTS, now);
        } else {
          return new MinorCompactionScanQueryMatcher(scanInfo, deleteTracker, columnTracker,
            readPointToUse, oldestUnexpiredTS, now);
        }
      } else {
        return new MajorCompactionScanQueryMatcher(scanInfo, deleteTracker, columnTracker,
          readPointToUse, earliestPutTs, oldestUnexpiredTS, now);
      }
    } else {
      return new StripeCompactionScanQueryMatcher(scanInfo, deleteTracker, columnTracker,
        readPointToUse, earliestPutTs, oldestUnexpiredTS, now, dropDeletesFromRow,
        dropDeletesToRow);
    }
  }
}
