#!/usr/bin/env python3
"""Create a source-preserving Parquet Bronze mirror for Purchased A-share 1m raw ZIPs.

This does not rerun quality rules. Every parsed CSV data row is kept as source strings, in source
order, with a stable composite identity: `_source_archive_sha256`, `_source_member`, `_source_row`.
The original ZIP remains the byte-for-byte source of truth; the existing deep-raw audit can join to
Bronze on that identity.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import tempfile
import zipfile
from collections.abc import Iterable
from datetime import UTC, datetime
from io import TextIOWrapper
from pathlib import Path
from typing import Any

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq

from alphavector.storage import cos_client

RAW_ROOT = "raw-market-data/purchased-a-share-1m-v1"
BRONZE_ROOT = "market-data/intraday/purchased-a-share/1m/bronze-v1"
AUDIT_ROOT = "market-data/intraday/purchased-a-share/1m/audit-v1/deep-raw"
DELISTED_KEY = f"{RAW_ROOT}/shsz/delisted/1分钟数据_已退市.zip"
GLOBAL_MANIFEST_KEY = f"{BRONZE_ROOT}/manifest.json"
PROVENANCE = ("_source_archive_sha256", "_source_member", "_source_row")
ANNUAL_RE = re.compile(rf"^{re.escape(RAW_ROOT)}/(?P<m>shsz|beijing)/(?P<y>\d{{4}})_1min\.zip$")
MARKET_TOKEN = {"beijing": "b", "shsz": "s"}
TOKEN_MARKET = {v: k for k, v in MARKET_TOKEN.items()}
YEAR_BASE, YEAR_MAX = 1900, 2100


def emit(text: str) -> None:
    print(text, flush=True)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def head(client: Any, bucket: str, key: str) -> dict[str, object] | None:
    try:
        r = client.head_object(Bucket=bucket, Key=key)
    except Exception as exc:
        getter = getattr(exc, "get_status_code", None)
        if callable(getter) and getter() == 404:
            return None
        raise
    return {"bytes": int(r.get("Content-Length", -1)), "etag": str(r.get("ETag", "")).strip('"')}


def get_json(client: Any, bucket: str, key: str) -> dict[str, object] | None:
    try:
        r = client.get_object(Bucket=bucket, Key=key)
    except Exception as exc:
        getter = getattr(exc, "get_status_code", None)
        if callable(getter) and getter() == 404:
            return None
        raise
    body = r["Body"]
    stream = body.get_raw_stream() if hasattr(body, "get_raw_stream") else body
    raw = stream.read()
    obj = json.loads(raw if isinstance(raw, str) else bytes(raw).decode("utf-8"))
    if not isinstance(obj, dict):
        raise TypeError(f"JSON object is not a mapping: {key}")
    return obj


def upload(client: Any, bucket: str, key: str, path: Path) -> None:
    client.upload_file(Bucket=bucket, Key=key, LocalFilePath=str(path), PartSize=16, MAXThread=5, EnableMD5=False)
    remote = head(client, bucket, key)
    if remote is None or int(remote["bytes"]) != path.stat().st_size:
        raise RuntimeError(f"COS upload size verification failed: {key}")


def download(client: Any, bucket: str, key: str, path: Path) -> dict[str, object]:
    meta = head(client, bucket, key)
    if meta is None or int(meta["bytes"]) <= 0:
        raise RuntimeError(f"raw object is missing or empty: {key}")
    client.download_file(Bucket=bucket, Key=key, DestFilePath=str(path))
    if path.stat().st_size != int(meta["bytes"]):
        raise RuntimeError(f"raw download size mismatch: {key}")
    return meta


def list_keys(client: Any, bucket: str, prefix: str) -> Iterable[str]:
    marker = ""
    while True:
        kw: dict[str, object] = {"Bucket": bucket, "Prefix": prefix, "MaxKeys": 1000}
        if marker:
            kw["Marker"] = marker
        r = client.list_objects(**kw)
        items = r.get("Contents", []) or []
        for item in items:
            key = str(item.get("Key") or "")
            if key:
                yield key
        if str(r.get("IsTruncated", "false")).lower() != "true":
            break
        marker = str(r.get("NextMarker") or (items[-1].get("Key") if items else "") or "")
        if not marker:
            break


def annual_partitions(client: Any, bucket: str) -> list[tuple[str, int, str]]:
    out = []
    for key in list_keys(client, bucket, f"{RAW_ROOT}/"):
        m = ANNUAL_RE.match(key)
        if m:
            out.append((m.group("m"), int(m.group("y")), key))
    out.sort(key=lambda x: (x[0], x[1]))
    if not out:
        raise RuntimeError("no annual raw ZIP partitions found")
    return out


def enc_year(year: int) -> str:
    if not YEAR_BASE <= year <= YEAR_MAX:
        raise ValueError(f"year outside {YEAR_BASE}..{YEAR_MAX}: {year}")
    n, chars = year - YEAR_BASE + 1, []
    while n:
        n, rem = divmod(n - 1, 26)
        chars.append(chr(ord("a") + rem))
    return "".join(reversed(chars))


def dec_year(value: str) -> int:
    if value.isdecimal():
        year = int(value)
    else:
        if not value or not value.isascii() or not value.isalpha() or value != value.lower():
            raise ValueError(f"invalid year token: {value!r}")
        n = 0
        for c in value:
            n = n * 26 + ord(c) - ord("a") + 1
        year = YEAR_BASE + n - 1
    if not YEAR_BASE <= year <= YEAR_MAX:
        raise ValueError(f"year outside {YEAR_BASE}..{YEAR_MAX}: {year}")
    return year


def dec_market(value: str) -> str:
    if value in MARKET_TOKEN:
        return value
    if value in TOKEN_MARKET:
        return TOKEN_MARKET[value]
    raise ValueError(f"invalid market: {value!r}")


def annual_manifest(market: str, year: int) -> str:
    return f"{BRONZE_ROOT}/market={market}/year={year}/manifest.json"


def annual_prefix(market: str, year: int, raw_hash: str) -> str:
    return f"{BRONZE_ROOT}/market={market}/year={year}/raw_sha256={raw_hash}"


def delisted_manifest() -> str:
    return f"{BRONZE_ROOT}/market=shsz/supplement=delisted/manifest.json"


def delisted_prefix(raw_hash: str) -> str:
    return f"{BRONZE_ROOT}/market=shsz/supplement=delisted/raw_sha256={raw_hash}"


def reusable(client: Any, bucket: str, manifest: dict[str, object] | None, raw_key: str, raw_meta: dict[str, object], verify_parts: bool) -> bool:
    if not manifest or manifest.get("bronze_status") != "complete" or manifest.get("raw_cos_key") != raw_key:
        return False
    if int(manifest.get("raw_bytes", -1)) != int(raw_meta["bytes"]) or str(manifest.get("raw_etag") or "") != str(raw_meta["etag"]):
        return False
    if int(manifest.get("source_rows", -1)) != int(manifest.get("bronze_rows", -2)):
        return False
    parts = manifest.get("parts")
    if not isinstance(parts, list) or not parts:
        return False
    if verify_parts:
        for p in parts:
            if not isinstance(p, dict):
                return False
            meta = head(client, bucket, str(p.get("key") or ""))
            if meta is None or int(meta["bytes"]) != int(p.get("bytes", -1)):
                return False
    return True


def csv_members(z: zipfile.ZipFile) -> list[str]:
    members = sorted(n for n in z.namelist() if n.lower().endswith(".csv"))
    if not members:
        raise ValueError("ZIP contains no CSV members")
    return members


def scan_columns(z: zipfile.ZipFile, members: list[str]) -> list[str]:
    union, seen = [], set()
    for i, member in enumerate(members, 1):
        with z.open(member) as raw:
            cols = list(pd.read_csv(TextIOWrapper(raw, encoding="utf-8-sig", newline=""), dtype="string", keep_default_na=False, na_filter=False, nrows=0).columns)
        collision = set(cols) & set(PROVENANCE)
        if collision:
            raise ValueError(f"{member}: source/provenance column collision: {sorted(collision)}")
        for col in map(str, cols):
            if col not in seen:
                seen.add(col)
                union.append(col)
        if i % 500 == 0 or i == len(members):
            emit(f"scanned headers {i}/{len(members)}")
    return union


def chunks(z: zipfile.ZipFile, member: str, cols: list[str], raw_hash: str, chunk_rows: int) -> Iterable[pd.DataFrame]:
    offset = 0
    with z.open(member) as raw:
        reader = pd.read_csv(TextIOWrapper(raw, encoding="utf-8-sig", newline=""), dtype="string", keep_default_na=False, na_filter=False, chunksize=chunk_rows)
        for source in reader:
            out = pd.DataFrame(index=source.index)
            out["_source_archive_sha256"] = raw_hash
            out["_source_member"] = member
            out["_source_row"] = range(offset + 2, offset + 2 + len(source))
            for col in cols:
                out[col] = source[col].astype("string") if col in source.columns else pd.Series(pd.NA, index=source.index, dtype="string")
            offset += len(source)
            yield out.reset_index(drop=True)


class Parts:
    def __init__(self, client: Any, bucket: str, staging: Path, prefix: str, schema: pa.Schema, limit: int):
        self.client, self.bucket, self.staging, self.prefix, self.schema, self.limit = client, bucket, staging, prefix, schema, limit
        self.items: list[dict[str, object]] = []
        self.total = self.index = self.rows = 0
        self.writer: pq.ParquetWriter | None = None
        self.path: Path | None = None

    def open(self) -> None:
        if self.writer is None:
            self.path = self.staging / f"part-{self.index:05d}.parquet"
            self.writer = pq.ParquetWriter(self.path, self.schema, compression="zstd", compression_level=3, use_dictionary=True, write_statistics=True)
            self.rows = 0

    def write(self, frame: pd.DataFrame) -> None:
        pos = 0
        while pos < len(frame):
            self.open()
            take = min(self.limit - self.rows, len(frame) - pos)
            table = pa.Table.from_pandas(frame.iloc[pos:pos + take], schema=self.schema, preserve_index=False, safe=False)
            assert self.writer is not None
            self.writer.write_table(table)
            self.rows += take
            self.total += take
            pos += take
            if self.rows == self.limit:
                self.finish()

    def finish(self) -> None:
        if self.writer is None or self.path is None:
            return
        self.writer.close()
        local, rows = self.path, self.rows
        self.writer = self.path = None
        key = f"{self.prefix}/part-{self.index:05d}.parquet"
        digest, size = sha256(local), local.stat().st_size
        upload(self.client, self.bucket, key, local)
        self.items.append({"key": key, "rows": rows, "bytes": size, "sha256": digest})
        emit(f"uploaded part {self.index:05d}: rows={rows:,} bytes={size:,}")
        local.unlink()
        self.index += 1
        self.rows = 0


def build(client: Any, bucket: str, raw_key: str, manifest_key: str, scope: str, market: str, year: int | None, run_id: str, chunk_rows: int, part_rows: int, force: bool) -> dict[str, object]:
    raw_meta = head(client, bucket, raw_key)
    if raw_meta is None:
        raise RuntimeError(f"raw source missing: {raw_key}")
    old = get_json(client, bucket, manifest_key)
    if not force and reusable(client, bucket, old, raw_key, raw_meta, True):
        result = dict(old or {})
        result["operation_status"] = "skipped_reusable"
        emit(f"reused complete Bronze partition: {market}/{year if year is not None else 'delisted'}")
        return result

    work = Path(tempfile.mkdtemp(prefix="purchased-1m-bronze-"))
    try:
        raw_zip = work / "raw.zip"
        raw_meta = download(client, bucket, raw_key, raw_zip)
        raw_hash = sha256(raw_zip)
        prefix = delisted_prefix(raw_hash) if scope == "delisted" else annual_prefix(market, int(year), raw_hash)
        with zipfile.ZipFile(raw_zip) as z:
            members = csv_members(z)
            cols = scan_columns(z, members)
            schema = pa.schema([pa.field(PROVENANCE[0], pa.string(), nullable=False), pa.field(PROVENANCE[1], pa.string(), nullable=False), pa.field(PROVENANCE[2], pa.int64(), nullable=False), *[pa.field(c, pa.string()) for c in cols]])
            sink = Parts(client, bucket, work, prefix, schema, part_rows)
            member_rows: list[dict[str, object]] = []
            source_rows = 0
            for i, member in enumerate(members, 1):
                count = 0
                for frame in chunks(z, member, cols, raw_hash, chunk_rows):
                    sink.write(frame)
                    count += len(frame)
                member_rows.append({"member": member, "rows": count})
                source_rows += count
                if i % 100 == 0 or i == len(members):
                    emit(f"bronze-mirrored {i}/{len(members)} members source_rows={source_rows:,} bronze_rows={sink.total:,}")
            sink.finish()
        if source_rows <= 0 or source_rows != sink.total or source_rows != sum(int(p["rows"]) for p in sink.items):
            raise RuntimeError(f"Bronze row-count mismatch source={source_rows} bronze={sink.total}")
        manifest: dict[str, object] = {
            "dataset": "purchased_a_share_1m_v1", "layer": "bronze_source_preserving_mirror", "bronze_version": 1, "bronze_status": "complete",
            "scope": scope, "market": market, "year": year, "run_id": run_id, "created_at": datetime.now(UTC).isoformat(),
            "raw_cos_key": raw_key, "raw_bytes": int(raw_meta["bytes"]), "raw_etag": str(raw_meta["etag"]), "raw_sha256": raw_hash,
            "source_member_count": len(member_rows), "source_rows": source_rows, "bronze_rows": sink.total,
            "source_columns": cols, "provenance_columns": list(PROVENANCE), "row_identity": list(PROVENANCE),
            "source_value_semantics": "CSV field values preserved as strings; no business correction, filtering, quarantine, sorting, deduplication, timestamp normalization, or bar synthesis",
            "audit_root": AUDIT_ROOT, "parts": sink.items, "part_count": len(sink.items), "bronze_bytes": sum(int(p["bytes"]) for p in sink.items), "members": member_rows,
        }
        path = work / "manifest.json"
        path.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        upload(client, bucket, manifest_key, path)
        manifest["manifest_key"], manifest["operation_status"] = manifest_key, "built"
        return manifest
    finally:
        shutil.rmtree(work, ignore_errors=True)


def plan(client: Any, bucket: str, encoded: bool) -> dict[str, object]:
    parts = annual_partitions(client, bucket)
    if encoded:
        include = [{"k": "a", "m": MARKET_TOKEN[m], "y": enc_year(y)} for m, y, _ in parts]
        include.append({"k": "d", "m": "s", "y": "a"})
        return {"include": include}
    include: list[dict[str, object]] = [{"scope": "annual", "market": m, "year": y, "raw_key": k} for m, y, k in parts]
    include.append({"scope": "delisted", "market": "shsz", "year": None, "raw_key": DELISTED_KEY})
    return {"include": include}


def states(client: Any, bucket: str, verify: bool) -> dict[str, object]:
    rows = []
    sources = [("annual", m, y, k, annual_manifest(m, y)) for m, y, k in annual_partitions(client, bucket)]
    sources.append(("delisted", "shsz", None, DELISTED_KEY, delisted_manifest()))
    for scope, market, year, raw_key, manifest_key in sources:
        raw_meta = head(client, bucket, raw_key)
        manifest = get_json(client, bucket, manifest_key)
        state = "raw_missing" if raw_meta is None else ("complete" if reusable(client, bucket, manifest, raw_key, raw_meta, verify) else ("missing" if manifest is None else "stale_or_incomplete"))
        rows.append({"scope": scope, "market": market, "year": year, "state": state, "source_rows": int((manifest or {}).get("source_rows", 0)), "bronze_rows": int((manifest or {}).get("bronze_rows", 0)), "part_count": int((manifest or {}).get("part_count", 0)), "bronze_bytes": int((manifest or {}).get("bronze_bytes", 0))})
    done = [r for r in rows if r["state"] == "complete"]
    return {"ok": len(done) == len(rows), "partition_count": len(rows), "complete_partition_count": len(done), "source_rows": sum(int(r["source_rows"]) for r in done), "bronze_rows": sum(int(r["bronze_rows"]) for r in done), "part_count": sum(int(r["part_count"]) for r in done), "bronze_bytes": sum(int(r["bronze_bytes"]) for r in done), "partitions": rows}


def finalize(client: Any, bucket: str, run_id: str) -> dict[str, object]:
    report = states(client, bucket, True)
    if not report["ok"]:
        bad = [f"{r['market']}/{r['year'] if r['year'] is not None else 'delisted'}={r['state']}" for r in report["partitions"] if r["state"] != "complete"]
        raise RuntimeError("Bronze finalize requires all partitions complete: " + ", ".join(bad))
    report.update({"dataset": "purchased_a_share_1m_v1", "layer": "bronze_source_preserving_mirror", "bronze_version": 1, "run_id": run_id, "created_at": datetime.now(UTC).isoformat(), "audit_root": AUDIT_ROOT})
    work = Path(tempfile.mkdtemp(prefix="purchased-1m-bronze-final-"))
    try:
        path = work / "manifest.json"
        path.write_text(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        upload(client, bucket, GLOBAL_MANIFEST_KEY, path)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    report["manifest_key"] = GLOBAL_MANIFEST_KEY
    return report


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--compact", action="store_true")
    p.add_argument("--chunk-rows", type=int, default=250_000)
    p.add_argument("--max-rows-per-part", type=int, default=5_000_000)
    p.add_argument("--force", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)
    pp = sub.add_parser("plan"); pp.add_argument("--encoded", action="store_true")
    aa = sub.add_parser("annual"); aa.add_argument("--market", required=True); aa.add_argument("--year", required=True); aa.add_argument("--run-id", required=True)
    dd = sub.add_parser("delisted"); dd.add_argument("--run-id", required=True)
    ss = sub.add_parser("status"); ss.add_argument("--verify-parts", action="store_true")
    ff = sub.add_parser("finalize"); ff.add_argument("--run-id", required=True)
    args = p.parse_args()
    if args.chunk_rows <= 0 or args.max_rows_per_part <= 0 or args.chunk_rows > args.max_rows_per_part:
        raise SystemExit("invalid chunk/part row limits")
    bucket, region = cos_client.DEFAULT_BUCKET, cos_client.DEFAULT_REGION
    client = cos_client.create_cos_client(region)
    if args.cmd == "plan": result = plan(client, bucket, args.encoded)
    elif args.cmd == "annual":
        market, year = dec_market(args.market), dec_year(args.year)
        result = build(client, bucket, f"{RAW_ROOT}/{market}/{year}_1min.zip", annual_manifest(market, year), "annual", market, year, args.run_id, args.chunk_rows, args.max_rows_per_part, args.force)
    elif args.cmd == "delisted": result = build(client, bucket, DELISTED_KEY, delisted_manifest(), "delisted", "shsz", None, args.run_id, args.chunk_rows, args.max_rows_per_part, args.force)
    elif args.cmd == "status": result = states(client, bucket, args.verify_parts)
    else: result = finalize(client, bucket, args.run_id)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":")) if args.compact else json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
