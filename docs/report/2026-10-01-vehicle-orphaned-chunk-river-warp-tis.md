# TIS 回報草稿：駕駛中的車被留在已卸載的 chunk 上，駕駛被拉回舊位置、車卡進河裡全黑（未送出）

- 狀態：草稿，尚未回報。本 repo 沒有對應修補：整條路徑都在原版 client 端，伺服器修補碰不到。
- 事件：2026-10-01 約 19:33（伺服器時間），Player-L 在路易斯維爾往北開車，被拉回約 175 格外的舊位置，接著車落進俄亥俄河，畫面全黑、下不了車。之後重登幾次，車每次往岸邊移幾格，20:15 回到岸上恢復正常。
- 資料來源：Player-L 提供的 client DebugLog（19:14 啟動那份）、三段遊戲錄影（逐格讀畫面上的玩家座標、儀表板與時鐘）、伺服器 user／cmd log 與 vehicles.db（唯讀查詢）、42.21.0 反編譯快照（下文只寫類別與方法名稱，不貼原始碼）。
- 已確認：出事前約 35 秒，client log 連續三次記下同一台車 `vehicle wasn't removed from world`；錄影中駕駛先被拉到約 175 格外的舊位置 0.2 秒，之後落在河裡，座標剛好在 chunk 邊界；伺服器同時段穩定 10 FPS，沒有 AntiCheat、沒有 chunk 產生失敗的紀錄；我們的伺服器修補不在這條路徑上。
- 未確認：(1) 車還在上面時，那個 chunk 為什麼會被卸載；(2) 從被拉回到落進河裡的那一步發生在 native 物理，Java 看不到；(3) 出事前最後 3 秒畫面座標仍在變化，所以「車停止更新」不是從 log 那三行一路持續到出事，確切順序無法只靠 Java 定案；(4) 同一瞬間遊戲時鐘顯示倒退（22:20→22:10）的原因；(5) log 沒有直接印出 id 753 就是他的車，是從時間與位置推定。
- 玩家可用的避開方法：開車時引擎聲突然消失，或車子自己煞停，先停車並沿原路倒退，等引擎聲回來再開；已經卡在水裡就重登幾次，或請管理員移位。

```text
[42.21.0][MP] A driven vehicle is left on an unloaded client chunk; it stops syncing, and a later server update teleports the driver to the stale position (stuck in water, black screen)

Version: 42.21.0. Dedicated server (Linux, about 30 players online, main loop steady at 10 FPS) and a Windows 11 client.

Summary
On the client, IsoChunk.removeFromWorld() cannot remove a vehicle that has a local player aboard (BaseVehicle.removeFromWorld() returns early in that case), and nothing moves the vehicle to a loaded chunk. The vehicle stays in the cell but still belongs to a chunk the chunk map no longer references. From then on BaseVehicle.update() returns early on the client, so the vehicle stops sending VehiclePhysics, stops updating its sounds and stops placing its passengers. The server keeps the last position it received. When that stale position is outside the client's loaded area, VehiclePacket.doRemove() moves the local driver there. In our case the player was pulled about 175 squares back, the chunk map reloaded, and the car ended up in the Ohio River. He could not get out, and the screen stayed black until repeated reconnects brought the car back to the bank.

What the player saw
- While driving north through Louisville, the engine sound stopped and the car slowed down by itself.
- The screen went completely black. The minimap showed him in the river. He could not exit the vehicle.
- Reconnecting did not help at first: still in the car, still black.
- Each reconnect moved the car a few squares toward the shore. About 40 minutes later it was on the bank and the world rendered normally.
Other players on our server know this pattern. Their advice is to reverse back along the road as soon as the engine sound stops.

Evidence
1. Client DebugLog. The client clock is about 4 s behind the server. These lines come about 35 s before the jump:
     19:32:44 IsoChunk.removeFromWorld: vehicle wasn't removed from world id=753
     19:32:46 IsoChunk.removeFromWorld: vehicle wasn't removed from world id=753
     19:32:50 IsoChunk.removeFromWorld: vehicle wasn't removed from world id=753
   Nothing else is logged until the player leaves the game at 19:33:47. In this player's other logs from the same evening (about 70 minutes of play) the message appears four more times, each a single line for a different id, with no incident. The log does not say which vehicle id 753 is. It is the only repeated id, and the time and place match his car.
2. The player's 60 fps recording. We read the on-screen player coordinates frame by frame:
   - The car brakes to a stop at 13194,1225 (dashboard: neutral, idle RPM, speed 0).
   - For the next 12 frames (0.2 s) the position is 13281,1378 and the whole screen is black, name tag included. That point is about 175 squares back along his route, close to where he was when the three log lines were written (interpolated from server-side positions).
   - Then 13193,1160, later 13152,1184 and 13150,1185, all in the river. 1160, 13152 and 1184 are multiples of 8 (chunk edges).
   - At the first jump the in-game clock display also stepped back from 22:20 to 22:10 (DayLength 3). We have no explanation for that.
3. Server. He disconnected at 13150,1185. Later sessions loaded him at 13149,1189, then 13149,1190, then 13149,1191, and finally 13147,1203 on the bank. There are no AntiCheat lines, no "chunk ... was not generated" lines and no main-loop stall in that window.

Code paths involved (class and method names, 42.21.0)
- IsoChunk.removeFromWorld(): for a vehicle still in the cell it logs the message above and calls BaseVehicle.removeFromWorld(). That method returns immediately when any passenger is a local player, so the vehicle stays in the cell, still assigned to the unloaded chunk.
- BaseVehicle.update(): on the client, if the vehicle's chunk has no refs, it calls removeFromWorld() (which returns early again) and returns. Everything after that is skipped: updatePhysicsNetwork() (no VehiclePhysics, so the server keeps the old position), the position and chunk sync from the physics transform, passenger placement, and updateSounds() (the engine sound that players notice going silent). Reversing can bring that chunk back into the chunk map, which ends the state. That fits the player workaround.
- CarController: when BaseVehicle.isInvalidChunkAhead() is true and the driver is not reversing, it forces the brake and cuts the throttle. So the car also stops on its own at the edge of chunks that are not loaded, and reversing is the only input that still works there.
- VehiclePacket.doRemove(), called from VehicleUpdatePacket.parse() and VehicleFullUpdatePacket.parse(): if the client has no square at the server's position for the vehicle and a local player is in it, the player is moved to that position and the vehicle is requested again. Because the server sends regular vehicle updates, a stale server position triggers this soon after it leaves the client's loaded area. A jump wider than the chunk map reloads the whole chunk map, which matches the fully black frames.
- BaseVehicle.update(): when there is no square under the physics position, the vehicle is clamped into its current chunk at height 0.2 and its physics body is moved there. The chunk-edge coordinates above look like this clamp.
- IsoCamera.FrameState.calculateCameraZ(): while the player is seated, the camera height is the vehicle's raw physics height, with no clamp. FBORenderCutaways does not render squares above the camera level when that level is below 0. As far as we can tell from IsoChunk.calcPhysics(), water squares are solid for vehicle physics. A car pushed below ground there blacks out the whole view, while the HUD and minimap keep working. When he appeared outside the car on the bank after a reconnect, the view was normal right away.

Not determined
- Why the chunk under a moving, locally driven car is unloaded in the first place. A pooled chunk object being reused and a race while the chunk map scrolls are the candidates we could not rule out.
- How the car got from the stale position into the river. That step happens in native physics. The on-screen position kept changing during the last 3 s before the jump, so the vehicle was not frozen for the whole time between the log lines and the jump.

Suggested fixes
1. In IsoChunk.removeFromWorld(), when a vehicle cannot be removed because a local player is aboard, move it to the loaded chunk under its current position (or keep that chunk referenced) instead of leaving it on the unloaded one.
2. In BaseVehicle.update(), for a vehicle with a local driver, reassign it to the loaded chunk under its position instead of returning early.
3. In VehiclePacket.doRemove(), do not teleport the local driver of a vehicle this client is authoritative for (Local or LocalCollide). Request a full update instead.
4. Clamp the seated camera height to the vehicle's floor level, so a vehicle below ground does not black out the view.

We have no patch for this, because the whole path is on the client. We can share the client log, the recording frames and the server-side positions.
```
