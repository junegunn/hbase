# Cell-Level TTL and Version Pruning Inconsistency

## Summary

When a newer cell version has a cell-level TTL and an older version does not, the older
version may or may not survive after the newer version expires — depending on whether a
flush or compaction ran while the newer version was still alive. This is an inconsistency:
the result of a scan should not depend on the physical storage layout.

## Reproduction

### Case 1: Both cells in memstore

```ruby
create 't', 'd'
put 't', 'row', 'd:foo', 'v1'
put 't', 'row', 'd:foo', 'v2', TTL => 5 * 1000
scan 't' # => v2

sleep 5
scan 't' # => v1 remains (WRONG)
```

### Case 2: Both cells flushed to the same HFile

```ruby
create 't', 'd'
put 't', 'row', 'd:foo', 'v1'
put 't', 'row', 'd:foo', 'v2', TTL => 5 * 1000
scan 't' # => v2

flush 't'
sleep 5
scan 't' # => NO RESULT (correct)
```

### Case 3: Cells in separate HFiles

```ruby
create 't', 'd'
put 't', 'row', 'd:foo', 'v1'
flush 't'
put 't', 'row', 'd:foo', 'v2', TTL => 5 * 1000
flush 't'
scan 't' # => v2

sleep 5
scan 't' # => v1 remains (WRONG)
```

### Case 4: Separate HFiles + major compaction

```ruby
create 't', 'd'
put 't', 'row', 'd:foo', 'v1'
flush 't'
put 't', 'row', 'd:foo', 'v2', TTL => 5 * 1000
flush 't'
major_compact 't'
scan 't' # => v2

sleep 5
scan 't' # => NO RESULT (correct)
```

### Case 5: Flush after expiration

```ruby
create 't', 'd'
put 't', 'row', 'd:foo', 'v1'
put 't', 'row', 'd:foo', 'v2', TTL => 5 * 1000
scan 't' # => v2

sleep 5
flush 't'
major_compact 't'
scan 't' # => v1 remains (WRONG)
```

### Case 6: Separate HFiles, compaction after expiration

```ruby
create 't', 'd'
put 't', 'row', 'd:foo', 'v1'
flush 't'
put 't', 'row', 'd:foo', 'v2', TTL => 5 * 1000
flush 't'
sleep 5
major_compact 't'
scan 't' # => v1 remains (WRONG)
```

| Case | Storage layout                  | v1 survives after v2 expires? |
|------|---------------------------------|-------------------------------|
| 1    | Both in memstore                | Yes (bug)                     |
| 2    | Same HFile (flush before TTL)   | No (correct)                  |
| 3    | Separate HFiles                 | Yes (bug)                     |
| 4    | Compacted before TTL            | No (correct)                  |
| 5    | Flush after TTL                 | Yes (bug)                     |
| 6    | Compacted after TTL             | Yes (bug)                     |

## Root Cause

With `VERSIONS=1` (default), a Put is semantically an overwrite. When v2 overwrites v1,
v1 is logically gone. If v2 later expires via cell-level TTL, nothing should remain.

The inconsistency arises because the scan path (Cases 1 & 3) skips TTL-expired cells
**before** version counting, allowing overwritten versions to reappear:

```java
// NormalUserScanQueryMatcher.match() — BEFORE fix
MatchCode returnCode = preCheck(cell);  // TTL-expired cell -> SKIP
if (returnCode != null) return returnCode;  // returns early, no version counting
// ...
return matchColumn(cell, timestamp, typeByte);  // version counting happens here
```

After v2's TTL expires, `preCheck` returns SKIP without incrementing the version counter.
v1 then becomes version 1 of 1 and is incorrectly included.

The flush/compaction paths have the same bug when v2 has already expired at flush/compaction
time (Cases 5 & 6): `preCheck` skips v2 without counting it, so v1 becomes version 1 and
survives. Cases 2 & 4 only appear correct because flush/compaction happens to run while
v2 is still alive.

## Fix

The fix adds a total-version counter that tracks all versions per column **including
cell-level TTL expired ones**, and checks this against the CF's `maxVersions` (from
`ScanInfo`). This is applied to all matcher paths: user scan, flush, and compaction.

The key insight is that `preCheck()` returns `SKIP` **only** for cell-level TTL expiry, so
we can count those without calling `isCellTTLExpired` again:

```java
// NormalUserScanQueryMatcher.match() — AFTER fix
MatchCode returnCode = preCheck(cell);
if (returnCode != null) {
    if (returnCode == MatchCode.SKIP) {
        trackColumnVersion(cell);  // count expired cell toward CF limit
    }
    return returnCode;
}
// ... delete handling unchanged ...

// Check CF-level version limit before matchColumn
if (trackColumnVersion(cell) > cfMaxVersions) {
    return columns.getNextRowOrNextColumn(cell);
}
return matchColumn(cell, timestamp, typeByte);
```

This separates two concerns:
- **CF version limit** (`cfMaxVersions`): controls what survives compaction. Expired
  cells count. Checked via `trackColumnVersion()` before `matchColumn()`.
- **Scan version limit** (`min(scan.maxVersions, cf.maxVersions)`): controls how many
  visible versions to return. Expired cells do not count (they are already skipped by
  `preCheck`). Checked inside `matchColumn()` via the column tracker as before.

### All matchers after fix

All matchers now share the same pattern — count expired cells, then check the CF limit:

```java
MatchCode returnCode = preCheck(cell);         // TTL-expired → SKIP
if (returnCode != null) {
    if (returnCode == MatchCode.SKIP)
        trackColumnVersion(cell);              // count expired cell toward CF limit
    return returnCode;
}
// ... delete handling ...
if (trackColumnVersion(cell) > cfMaxVersions)  // check CF-level version limit
    return columns.getNextRowOrNextColumn(cell);
// ... normal version counting (checkVersions / matchColumn) ...
```

The shared `trackColumnVersion` and `checkCFVersionLimit` logic lives in
`CompactionScanQueryMatcher` (base class for flush/compaction matchers).
`NormalUserScanQueryMatcher` has its own copy since it does not extend that class.

### Files changed

- `CompactionScanQueryMatcher.java`: added `cfMaxVersions`, `columnVersions`,
  `columnCell` fields, `trackColumnVersion()` and `checkCFVersionLimit()` methods.
  Modified `reset()` and `beforeShipped()`.
- `MinorCompactionScanQueryMatcher.java`: updated `match()` to count expired cells
  and check CF version limit.
- `MajorCompactionScanQueryMatcher.java`: same.
- `StripeCompactionScanQueryMatcher.java`: same.
- `NormalUserScanQueryMatcher.java`: added `cfMaxVersions`, `columnVersions`,
  `columnCell` fields and `trackColumnVersion()` method. Modified `match()`, `reset()`,
  and `beforeShipped()`.

### Tests

- `testCellTTLExpiredCountsTowardVersionLimit`: with `VERSIONS=1`, expired cell blocks
  the older version
- `testCellTTLExpiredWithHigherCFVersionLimit`: with CF `VERSIONS=2` and scan
  `maxVersions=1`, the older version survives (within CF limit)
- `testCellTTLExpiredExceedsCFVersionLimit`: with CF `VERSIONS=2`, two expired cells
  fill the CF limit and block the older non-expired version
