# Animal relevancy radius exceeds the client loaded range, causing a full-snapshot resend loop

> 用途：貼到 The Indie Stone 官方論壇 Bug Reports 板（B42）。英文本文可直接複製。
> 數據來源：正式服（38–39 連線、77 mods）2026-08-24 雙向 pcap 解碼 ＋ 42.20.3 `javap` 核實。
> 撰於 2026-08-24。發文前補上文末 placeholder。
>
> **規則**：不貼反編譯 Java 源碼；class／method 名稱、bytecode offset、行為描述可以。
> 不寫玩家名／IP／實際座標。
>
> 姊妹篇（同樣動物相關但機制完全獨立）：
> - `docs/tis-bug-report-animal-sort-livelock.md`（W11）＝ comparator 契約違反造成全服活鎖。
> - 本篇（W13）＝ 沒有 crash、沒有活鎖，純粹是頻寬浪費 ＋ 永不成功的重試迴圈。
>
> **審稿注意**：本文已刻意避免三種過度宣稱——不寫「exactly 1.25×」（奇數 grid width 下
> 是整數除法）、不寫「ring 內每份 snapshot 都必然被丟棄」（我們沒有逐請求的 client
> chunk 載入狀態）、不寫「修法零可見損失」（chunk 對齊與載具前移都會留下誤差）。

---

**Title:** [42.20.3] [MP] Animal relevancy radius is 10/8 of the client's loaded chunk half-width, producing a repeating full-snapshot request loop (~38% of server upload on a busy server)

## Summary

On a dedicated server, animals sitting between the client's loaded chunk boundary and the animal relevancy radius appear to generate a repeating request/response cycle:

1. the server sends a lightweight animal update for such an animal;
2. the client has no local instance for that `onlineID`, so it requests the full animal;
3. the server replies with a complete `IsoAnimal` snapshot (~1.1 KiB, genome included);
4. the client's consistency check requires the target grid square to be loaded — outside the loaded area it is not, so the body is skipped and no instance is created;
5. 800–1000 ms later the next lightweight update arrives and the cycle repeats.

On our server, packets carrying animal full-snapshot signatures accounted for **38.3–39.8%** of outgoing bytes in steady state, and **87.2%** of the observed full snapshots were repeats of a `(client, onlineID)` pair already sent within the same few seconds.

No mod is required for the geometry mismatch described below.

## Environment

- 42.20.3 dedicated Linux server, Java 25
- 38–39 concurrent connections during measurement, 77 workshop mods
- Wild animals (deer, mice, rats) in ordinary outdoor terrain — not a modded pen, not a hutch
- No crash, no exception, no livelock

## Measurements

Captured read-only with `tcpdump`, decoded with a purpose-built parser: Ethernet/IPv4/UDP → RakNet connected datagram (reliability/split headers) → reassembly by `(src, dst, ports, splitId, splitCount)` → PZ user message → `PacketType` → `AnimalUpdatePacket` requested section.

Window: 8.03 seconds, 25,000 bidirectional UDP datagrams (18.7 MB).

| Metric | Value |
|---|---:|
| client → server requested animal IDs | 109 |
| server → client full animal snapshots | 109 |
| distinct `(client endpoint, onlineID)` pairs | **14** |
| pairs that repeated within 5 s | **14 / 14** |
| full snapshots that were repeats | **95 / 109 (87.2%)** |
| full snapshots per pair in the window | **5 – 10** |
| requests that received a response | 109 / 109 |
| request → response latency | 43.9 – 94.2 ms (median 72.7) |
| average `dataSize` per full snapshot | 1,125.4 bytes |
| distance from the requesting player to the animal | 70.4 – 91.5 squares |
| RakNet split groups / incomplete | 1,167 / 4 |
| `AnimalUpdatePacket` parse errors | 0 |

Two benign explanations are ruled out by the data:

- **Not "many different animals entering view for the first time":** only 14 distinct pairs produced 109 snapshots.
- **Not simple packet loss:** every request received a response, and most responses travelled in *reliable* RakNet frames, yet the same ID was requested again on the next tick.

Repeat intervals cluster around 0.6 s / 1.0 s / 2.0 s, matching the animal update cadence.

**What the capture does not prove:** we do not have per-request `chunkGridWidth`, nor the client's per-chunk loaded state at the moment each snapshot arrived. The distances are consistent with the geometry below and the repetition is unambiguous, but "every one of those snapshots was necessarily discarded" remains an inference, not a direct observation.

## Root cause — animal radius is 10/8 of the loaded half-width

During handshake (`GameServer.receivePlayerConnect`) the server stores clamped values derived from the client-provided chunk-grid width:

```text
range          = clamp(client chunk grid width, 12, 20)
relevantRange  = range / 2 + 2                  // integer division
chunkGridWidth = range
```

`ClientServerMap.loaded[]` is unrelated to client chunk streaming: it tracks 64-square server-cell `isLoaded` state, not whether each 8-square client chunk has finished loading.

For a normal, unclamped odd width, the client chunk window has a guaranteed lower-bound half-width of `(range / 2) * 8` squares once the relevant chunks have finished streaming. `AnimalSynchronizationManager.sendUpdateToClient` instead decides animal relevancy with (bytecode offsets 233–242):

```text
radius = (getRelevantRange() - 2) * 10 = (range / 2) * 10
```

The radius is `10/8` of that guaranteed lower bound, so the extra ring can include positions for which the client has no `GridSquare`. We do not have per-snapshot evidence of the client's loaded set, so we cannot claim that every square in the ring was unloaded; the repeated request cycle is the direct observation.

Both quantities use **integer division** (e.g. `range = 13` yields 48 and 60, not 52 and 65). `IsoChunkMap.CalcChunkWidth` produces odd widths during normal operation.

Two further details make the mismatch worse in practice:

- `UdpConnection.RelevantTo` is an **axis-aligned square** test (`|dx| <= r && |dy| <= r`), not a circle, so the effective over-reach is larger on the diagonals.
- The loaded rectangle is **chunk-aligned around the player's chunk**, while the radius is continuous around the player's exact position. With continuous player offset `p ∈ [0,8)`, the low-side distance is `(range/2)*8 + p` and the high-side exclusive boundary is `((range/2)+1)*8 - p`. Their common safe lower bound is `(range/2)*8`; the wider side can exceed it by less than 8 squares, so no single radius matches both sides exactly.

For comparison, `UdpConnection.RelevantToPlayerIndex` uses `relevantRange * 8` for general relevancy, and `isAnimalOnScreen` reuses the same `(relevantRange - 2) * 10` expression for an unrelated purpose (choosing the 800 ms vs 1000 ms cadence). The `* 10` factor reads like a generic "relevancy-ish distance" that happens to be compared against a hard client-side requirement.

## Why the loop does not converge

- `AnimalUpdatePacket.parse` on the client adds an unknown `onlineID` to its requested set and asks the server for it.
- The server serializes the animal with `IsoAnimal.save`, which writes mod data **and** the full genome. Each `AnimalGene` writes its name plus two `AnimalAllele` entries, and each allele writes its own name, so fixed schema strings (`maxWeight`, `ageToGrow`, `fertility`, `meatRatio`, `eggSize`, …) are re-sent verbatim for every animal, every time. That is the ~1.1 KiB.
- `AnimalPacket.isConsistent` requires `IsoWorld.instance.getCell().getGridSquare(...) != null`. When that square is not loaded the body is skipped and **no instance is created**.
- With no instance, the next lightweight update for the same `onlineID` is again "unknown", so the client asks again.

There is no cooldown, negative cache, or "don't ask for animals outside my loaded area" check anywhere on that path.

## Vehicles make any player-centred radius unfixable on the server alone

`IsoChunkMap.ProcessChunkPos` moves the client's chunk-map centre **ahead of the player** while they are in a vehicle: by `currentSpeedKmHour / 5` squares when driving (no cap) and `min(s * 2, 20)` as a passenger. The server's `releventPos` is the player's actual position and carries no information about this look-ahead.

Consequently, while a player is driving, a server-side radius centred on the player will simultaneously withhold animals in already-loaded chunks ahead of the vehicle and keep advertising animals in unloaded chunks behind it. Any fix that only adjusts the radius needs to account for this, which is one reason we think the correct fix belongs on the side that actually knows the loaded set.

## Cost

Animal full-snapshot traffic was the single largest component of outgoing bandwidth on our server (38.3–39.8% of packet bytes), and 87.2% of those snapshots were repeats. The cost scales with (animals near the ring) × (connected clients), so it is worst on busy servers.

## Suggested fixes

Items 1–4 break the loop; item 5 independently reduces the cost of each snapshot.

1. **Derive animal relevancy from the client's loaded set, not from `relevantRange`.** An exact loaded set is only available on the client (either through client-side judgment of what is loaded, or through explicit reporting to the server). Server-side approximations (chunk rectangle based on `chunkGridWidth` and player position) cannot account for vehicle look-ahead, pending chunks still streaming, or other asynchronous load conditions. Note that `ClientServerMap.loaded[]` is *not* a substitute: it tracks 64-square server-cell `isLoaded` state, not whether each 8-square client chunk has finished streaming. Clamping the radius alone cannot achieve exactness.
2. **Gate the requested path.** `setRequested` currently accepts any `onlineID` a client asks for, with no relevancy check and no rate limit (up to 150 per packet). Rejecting IDs outside the requesting connection's loaded area both fixes residual loops and closes an amplification surface: an authenticated client can currently request arbitrary animal IDs and receive full snapshots for animals it should not be able to observe.
3. **Add a per-connection/per-animal cooldown** for full snapshots, so any future mismatch degrades into one wasted packet instead of a sustained loop.
4. **Client side:** skip the request when the target square is not loaded, and/or keep a short negative cache for IDs whose snapshot was just discarded. This is the most robust place to break the cycle, since only the client knows its own loaded state.
5. **Independently, shrink the animal wire format.** Genome field names are a fixed schema but are transmitted as strings three times per gene (gene + both alleles). Gene indices or a fixed field order would cut every animal snapshot even after the loop is fixed. This is a wire-format change and needs both sides updated.

Fixes 2–3 are purely server-side and preserve the packet layout, so unmodified clients keep working. Fix 1 can be server-side (if client reports loaded state) or client-side (if client judges locally).

## Reproduction

No mods required.

1. Start a vanilla 42.20.3 dedicated server.
2. Connect one client on foot and note its chunk grid width (`range`); auto-computed values are odd and capped at 19.
3. Stand still in open terrain where wild animals spawn.
4. Place an animal in the `(range/2)*8 .. (range/2)*10` ring. Client-side `isConsistent` instrumentation should be used to identify cases where that animal's `GridSquare` is actually absent; distance alone is not proof of loaded state.
5. Observe either side:
   - **Server:** count `AnimalUpdateReliable`/`AnimalUpdateUnreliable` packets whose requested section contains that animal's `onlineID`. It repeats every ~800–1000 ms indefinitely.
   - **Client:** the same `onlineID` is repeatedly added to the requested set and the animal never becomes visible.
6. For a quantitative check, capture outgoing UDP for ~10 s and count how many full snapshots share the same `(destination, onlineID)` pair.

A cheap instrumentation point is `AnimalUpdatePacket.write`: logging `(connection, onlineID)` for every entry it serializes into the requested section makes the repetition obvious within seconds. Logging the client's `isConsistent` result for the same pair would confirm the discard side directly, which our capture cannot.

## Prepared mitigation (not yet deployed)

We have prepared a server-side bytecode mitigation that redirects the single `UdpConnection.RelevantTo` call inside `sendUpdateToClient` and, for selected connections, clamps the radius to `(getChunkGridWidth()/2) * 8`. It is stack-shape and instruction-length neutral and does not change the packet format; compatibility with unmodified clients still needs runtime verification after deployment.

The clamp applies only to `stored ∈ {13, 15, 17, 19}`. Values 14, 16, and 18 bypass because an even-width chunk rectangle is asymmetric; 12 and 20 bypass because clamping loses the original width; any connection with a player in a vehicle also bypasses.

We do not claim the mitigation is loss-free or exact. `over-send = 0` is only justified for the radius branch when the stored width is one of the trusted odd values, server and client use the same centre chunk, and the relevant client chunks have finished streaming. Under those conditions the continuous player offset `p ∈ [0,8)` means the wider side can still withhold animals by less than 8 squares inside the loaded rectangle. Centre mismatch, streaming gaps, `connectArea` load windows, every bypass above, and the ungated `requested` path can all leave residual repeats.

## Contact

- Server: <server name>
- Contact: <contact method>

## Attachments to prepare before posting

- Redacted packet-count summary (distinct pairs vs total snapshots; no IPs)
- Redacted server log excerpt showing repeated requested sections for one `onlineID`
- The `range` value in use, so the ring boundary can be recomputed
- Mod list, noting that the affected animals were wild and no mod touches the animal sync path
