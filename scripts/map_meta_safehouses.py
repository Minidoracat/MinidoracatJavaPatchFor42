#!/usr/bin/env python3
"""檢查、比較並安全合併 PZ B42 map_meta.bin 的安全屋資料。"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
from dataclasses import asdict, dataclass, replace
from pathlib import Path


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def take(self, fmt: str):
        size = struct.calcsize(fmt)
        if self.pos + size > len(self.data):
            raise ValueError(f"檔案在 offset {self.pos} 意外結束")
        values = struct.unpack_from(fmt, self.data, self.pos)
        self.pos += size
        return values[0] if len(values) == 1 else values

    def string(self) -> str:
        size = self.take(">H")
        if self.pos + size > len(self.data):
            raise ValueError(f"字串在 offset {self.pos} 超出檔案")
        raw = self.data[self.pos : self.pos + size]
        self.pos += size
        return raw.decode("utf-8", errors="replace")


@dataclass
class Safehouse:
    x: int
    y: int
    w: int
    h: int
    owner: str
    hit_points: int
    players: list[str]
    last_visited: int
    title: str
    datetime_created: int
    location: str
    respawn_players: list[str]

    @property
    def key(self) -> tuple[int, int, int, int]:
        return self.x, self.y, self.w, self.h

    @classmethod
    def read(cls, reader: Reader, version: int) -> Safehouse:
        x, y, w, h = (reader.take(">i") for _ in range(4))
        owner = reader.string()
        hit_points = reader.take(">i") if version >= 216 else 0
        players = [reader.string() for _ in range(_count(reader, "players"))]
        last_visited = reader.take(">q")
        title = reader.string()
        datetime_created = reader.take(">q") if version >= 223 else 0
        location = reader.string() if version >= 223 else ""
        respawn_players = [reader.string() for _ in range(_count(reader, "respawn players"))]
        return cls(x, y, w, h, owner, hit_points, players, last_visited, title,
                   datetime_created, location, respawn_players)

    def write(self, version: int) -> bytes:
        out = bytearray(struct.pack(">iiii", self.x, self.y, self.w, self.h))
        out += _string(self.owner)
        if version >= 216:
            out += struct.pack(">i", self.hit_points)
        out += struct.pack(">i", len(self.players))
        for player in self.players:
            out += _string(player)
        out += struct.pack(">q", self.last_visited)
        out += _string(self.title)
        if version >= 223:
            out += struct.pack(">q", self.datetime_created)
            out += _string(self.location)
        out += struct.pack(">i", len(self.respawn_players))
        for player in self.respawn_players:
            out += _string(player)
        return bytes(out)


@dataclass
class MetaFile:
    version: int
    bounds: tuple[int, int, int, int]
    prefix: bytes
    safehouses: list[Safehouse]
    suffix: bytes

    @classmethod
    def parse(cls, data: bytes) -> MetaFile:
        reader = Reader(data)
        if reader.take(">4s") != b"META":
            raise ValueError("不是 map_meta.bin：缺少 META header")
        version = reader.take(">i")
        x1, y1, x2, y2 = (reader.take(">i") for _ in range(4))
        if x2 < x1 or y2 < y1 or (x2 - x1 + 1) * (y2 - y1 + 1) > 2_000_000:
            raise ValueError(f"不合理的 metagrid bounds：{x1},{y1}..{x2},{y2}")

        building_size = 23 if version >= 201 else 19
        for _x in range(x1, x2 + 1):
            for _y in range(y1, y2 + 1):
                room_count = _count(reader, "rooms")
                reader.pos += room_count * 10
                building_count = _count(reader, "buildings")
                reader.pos += building_count * building_size
                if reader.pos > len(data):
                    raise ValueError("metagrid cell 資料超出檔案")

        prefix = data[: reader.pos]
        safehouses = [Safehouse.read(reader, version) for _ in range(_count(reader, "safehouses"))]
        return cls(version, (x1, y1, x2, y2), prefix, safehouses, data[reader.pos :])

    def to_bytes(self) -> bytes:
        body = b"".join(safehouse.write(self.version) for safehouse in self.safehouses)
        return self.prefix + struct.pack(">i", len(self.safehouses)) + body + self.suffix


def _count(reader: Reader, label: str) -> int:
    value = reader.take(">i")
    if value < 0 or value > 10_000_000:
        raise ValueError(f"不合理的 {label} 數量：{value}（offset {reader.pos - 4}）")
    return value


def _string(value: str) -> bytes:
    raw = value.encode("utf-8")
    if len(raw) > 32767:
        raise ValueError("字串超過 PZ signed-short 上限")
    return struct.pack(">H", len(raw)) + raw


def _load(path: Path) -> tuple[bytes, MetaFile]:
    data = path.read_bytes()
    meta = MetaFile.parse(data)
    if meta.to_bytes() != data:
        raise ValueError(f"{path} round-trip 驗證失敗，拒絕繼續")
    return data, meta


def _summary(path: Path) -> dict:
    data, meta = _load(path)
    return {
        "file": str(path.resolve()),
        "sha256": hashlib.sha256(data).hexdigest(),
        "world_version": meta.version,
        "bounds": meta.bounds,
        "safehouse_count": len(meta.safehouses),
        "round_trip": True,
        "safehouses": [asdict(safehouse) for safehouse in meta.safehouses],
    }


def _index(meta: MetaFile) -> dict[tuple[int, int, int, int], Safehouse]:
    result = {}
    for safehouse in meta.safehouses:
        if safehouse.key in result:
            raise ValueError(f"重複安全屋範圍：{safehouse.key}")
        result[safehouse.key] = safehouse
    return result


def _diff(source: MetaFile, target: MetaFile) -> dict:
    old, new = _index(source), _index(target)
    changed = []
    for key in sorted(old.keys() & new.keys()):
        before, after = old[key], new[key]
        if before != after:
            changed.append({
                "key": key,
                "owner_before": before.owner,
                "owner_after": after.owner,
                "players_removed": [p for p in before.players if p not in after.players],
                "players_added": [p for p in after.players if p not in before.players],
                "respawn_removed": [p for p in before.respawn_players if p not in after.respawn_players],
                "respawn_added": [p for p in after.respawn_players if p not in before.respawn_players],
                "other_fields_changed": any((
                    before.hit_points != after.hit_points,
                    before.last_visited != after.last_visited,
                    before.title != after.title,
                    before.datetime_created != after.datetime_created,
                    before.location != after.location,
                )),
            })
    return {
        "missing_in_target": [asdict(old[key]) for key in sorted(old.keys() - new.keys())],
        "new_in_target": [asdict(new[key]) for key in sorted(new.keys() - old.keys())],
        "changed": changed,
    }


def _union(current: list[str], old: list[str]) -> list[str]:
    return current + [value for value in old if value not in current]


def _overlaps(left: Safehouse, right: Safehouse) -> bool:
    return (left.x < right.x + right.w and left.x + left.w > right.x
            and left.y < right.y + right.h and left.y + left.h > right.y)


def _merge(source: MetaFile, target: MetaFile,
           selected: set[tuple[int, int, int, int]]) -> tuple[MetaFile, dict]:
    if source.version != target.version:
        raise ValueError(f"worldVersion 不同：source={source.version}, target={target.version}")
    current = _index(target)
    added, updated, conflicts, spatial_conflicts = [], [], [], []
    source_keys = {safehouse.key for safehouse in source.safehouses}
    missing_source = sorted(selected - source_keys)
    for old in source.safehouses:
        if old.key not in selected:
            continue
        now = current.get(old.key)
        if now is None:
            overlapping = [safehouse for safehouse in target.safehouses if _overlaps(old, safehouse)]
            if overlapping:
                spatial_conflicts.append({
                    "source": old.key,
                    "target": [safehouse.key for safehouse in overlapping],
                })
                continue
            target.safehouses.append(old)
            current[old.key] = old
            added.append(old.key)
        elif now.owner != old.owner:
            conflicts.append({"key": old.key, "source_owner": old.owner, "target_owner": now.owner})
        else:
            merged = replace(now,
                             players=_union(now.players, old.players),
                             respawn_players=_union(now.respawn_players, old.respawn_players))
            if merged != now:
                target.safehouses[target.safehouses.index(now)] = merged
                current[old.key] = merged
                updated.append(old.key)
    return target, {
        "added": added,
        "members_updated": updated,
        "owner_conflicts": conflicts,
        "spatial_conflicts": spatial_conflicts,
        "missing_in_source": missing_source,
    }


def _self_test() -> None:
    prefix = b"META" + struct.pack(">iiiii", 247, 0, 0, 0, 0) + struct.pack(">ii", 0, 0)
    old = Safehouse(1, 2, 3, 4, "owner", 100, ["member"], 5, "title", 6, "loc", ["member"])
    source = MetaFile(247, (0, 0, 0, 0), prefix, [old], b"tail")
    parsed = MetaFile.parse(source.to_bytes())
    assert parsed.to_bytes() == source.to_bytes()
    target = MetaFile(247, (0, 0, 0, 0), prefix, [], b"tail")
    merged, report = _merge(parsed, target, {old.key})
    assert len(merged.safehouses) == 1 and report["added"] == [(1, 2, 3, 4)]
    assert MetaFile.parse(merged.to_bytes()).safehouses[0].players == ["member"]
    print("self-test OK")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    inspect_cmd = sub.add_parser("inspect", help="解析並輸出安全屋 JSON")
    inspect_cmd.add_argument("files", nargs="+", type=Path)
    diff_cmd = sub.add_parser("diff", help="比較 source 與 target")
    diff_cmd.add_argument("source", type=Path)
    diff_cmd.add_argument("target", type=Path)
    merge_cmd = sub.add_parser("merge", help="補回缺少的安全屋與同 owner 成員，輸出新檔")
    merge_cmd.add_argument("source", type=Path)
    merge_cmd.add_argument("target", type=Path)
    merge_cmd.add_argument("output", type=Path)
    merge_cmd.add_argument("--key", action="append", required=True, metavar="X,Y,W,H",
                           help="只合併指定安全屋；可重複使用")
    sub.add_parser("self-test", help="執行內建最小測試")
    args = parser.parse_args()

    if args.command == "inspect":
        result = [_summary(path) for path in args.files]
    elif args.command == "diff":
        _, source = _load(args.source)
        _, target = _load(args.target)
        result = _diff(source, target)
    elif args.command == "merge":
        if args.output.resolve() == args.target.resolve():
            raise SystemExit("拒絕覆蓋 target；請指定新的 output")
        _, source = _load(args.source)
        _, target = _load(args.target)
        try:
            selected = {tuple(int(part) for part in value.split(",")) for value in args.key}
        except ValueError as error:
            raise SystemExit(f"無效 --key：{error}") from error
        if any(len(key) != 4 for key in selected):
            raise SystemExit("--key 必須是 X,Y,W,H")
        merged, result = _merge(source, target, selected)
        if result["owner_conflicts"] or result["spatial_conflicts"] or result["missing_in_source"]:
            print(json.dumps(result, ensure_ascii=False, indent=2))
            raise SystemExit("偵測到 owner／範圍衝突或 source 缺少指定 key，未寫入 output")
        output = merged.to_bytes()
        if MetaFile.parse(output).to_bytes() != output:
            raise SystemExit("合併後 round-trip 驗證失敗，未寫入 output")
        args.output.write_bytes(output)
        result["output"] = str(args.output.resolve())
        result["sha256"] = hashlib.sha256(output).hexdigest()
    else:
        _self_test()
        return
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
