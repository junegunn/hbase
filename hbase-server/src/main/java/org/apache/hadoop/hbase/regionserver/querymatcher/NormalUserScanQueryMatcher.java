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

import java.io.IOException;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.ExtendedCell;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.KeepDeletedCells;
import org.apache.hadoop.hbase.PrivateCellUtil;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.regionserver.ScanInfo;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Query matcher for normal user scan.
 */
@InterfaceAudience.Private
public abstract class NormalUserScanQueryMatcher extends UserScanQueryMatcher {

  /** Keeps track of deletes */
  private final DeleteTracker deletes;

  /** True if we are doing a 'Get' Scan. Every Get is actually a one-row Scan. */
  private final boolean get;

  /** whether time range queries can see rows "behind" a delete */
  protected final boolean seePastDeleteMarkers;

  /** The effective max versions for this store, used to match compaction pruning behavior. */
  private final int cfMaxVersions;

  /** Total versions seen for the current column, including cell-level TTL expired ones. */
  private int columnVersions;

  /** Current column cell for detecting column boundaries. */
  private ExtendedCell columnCell;

  protected NormalUserScanQueryMatcher(Scan scan, ScanInfo scanInfo, ColumnTracker columns,
    boolean hasNullColumn, DeleteTracker deletes, long oldestUnexpiredTS, long now) {
    super(scan, scanInfo, columns, hasNullColumn, oldestUnexpiredTS, now);
    this.deletes = deletes;
    this.get = scan.isGetScan();
    this.seePastDeleteMarkers = scanInfo.getKeepDeletedCells() != KeepDeletedCells.FALSE;
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
  private int trackColumnVersion(ExtendedCell cell) {
    if (columnCell == null || !CellUtil.matchingQualifier(cell, columnCell)) {
      columnVersions = 0;
      columnCell = cell;
    }
    return ++columnVersions;
  }

  @Override
  public MatchCode match(ExtendedCell cell) throws IOException {
    if (filter != null && filter.filterAllRemaining()) {
      return MatchCode.DONE_SCAN;
    }
    MatchCode returnCode = preCheck(cell);
    if (returnCode != null) {
      // preCheck returns SKIP only for cell-level TTL expiry. Count these toward the
      // CF version limit so that overwritten versions do not reappear after the newer
      // version expires.
      if (returnCode == MatchCode.SKIP) {
        trackColumnVersion(cell);
      }
      return returnCode;
    }
    long timestamp = cell.getTimestamp();
    byte typeByte = cell.getTypeByte();
    if (PrivateCellUtil.isDelete(typeByte)) {
      boolean includeDeleteMarker =
        seePastDeleteMarkers ? tr.withinTimeRange(timestamp) : tr.withinOrAfterTimeRange(timestamp);
      if (includeDeleteMarker) {
        this.deletes.add(cell);
      }
      return MatchCode.SKIP;
    }
    returnCode = checkDeleted(deletes, cell);
    if (returnCode != null) {
      return returnCode;
    }
    // Check CF-level version limit: if total versions for this column (including cell-level
    // TTL expired ones) exceeds cfMaxVersions, this cell would have been pruned by compaction.
    if (trackColumnVersion(cell) > cfMaxVersions) {
      return columns.getNextRowOrNextColumn(cell);
    }
    return matchColumn(cell, timestamp, typeByte);
  }

  @Override
  protected void reset() {
    deletes.reset();
    columnVersions = 0;
    columnCell = null;
  }

  @Override
  protected boolean isGet() {
    return get;
  }

  public static NormalUserScanQueryMatcher create(Scan scan, ScanInfo scanInfo,
    ColumnTracker columns, DeleteTracker deletes, boolean hasNullColumn, long oldestUnexpiredTS,
    long now) throws IOException {
    if (scan.isReversed()) {
      if (scan.includeStopRow()) {
        return new NormalUserScanQueryMatcher(scan, scanInfo, columns, hasNullColumn, deletes,
          oldestUnexpiredTS, now) {

          @Override
          protected boolean moreRowsMayExistsAfter(int cmpToStopRow) {
            return cmpToStopRow >= 0;
          }
        };
      } else {
        return new NormalUserScanQueryMatcher(scan, scanInfo, columns, hasNullColumn, deletes,
          oldestUnexpiredTS, now) {

          @Override
          protected boolean moreRowsMayExistsAfter(int cmpToStopRow) {
            return cmpToStopRow > 0;
          }
        };
      }
    } else {
      if (scan.includeStopRow()) {
        return new NormalUserScanQueryMatcher(scan, scanInfo, columns, hasNullColumn, deletes,
          oldestUnexpiredTS, now) {

          @Override
          protected boolean moreRowsMayExistsAfter(int cmpToStopRow) {
            return cmpToStopRow <= 0;
          }
        };
      } else {
        return new NormalUserScanQueryMatcher(scan, scanInfo, columns, hasNullColumn, deletes,
          oldestUnexpiredTS, now) {

          @Override
          protected boolean moreRowsMayExistsAfter(int cmpToStopRow) {
            return cmpToStopRow < 0;
          }
        };
      }
    }
  }
}
