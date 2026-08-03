# MinidoracatJavaPatchFor42

PZ B42 專用伺服器 loose-class 優化 patch——自製、可重生、帶同源防護。

## 原理

PZ 伺服器 classpath 為 `java/.` 優先於 `java/projectzomboid.jar`：同路徑的 loose `.class`
會覆蓋 jar 內的原版。本專案**不重編譯反編譯原始碼**，而是用 ASM 直接對 jar 內的原版
bytecode 做「堆疊形狀不變」的呼叫改道／方法內常數替換，另有兩個窄範圍 null 頭部守衛，
StackMapFrames 原樣保留；改道 helper 寫成普通 Java 類並由 javac 對遊戲 jar 編譯，隨 patch 出貨。

## 內容（26 個 patched class、38 個 runtime class、47 處手術、67 個命中點）

- **抑噪 6 項**：AnimationSet／SkinningBoneHierarchy／SpriteConfig（選擇性）／ItemPickInfo／
  PacketsCache／INetworkPacket.logInconsistentPacket，外加 NetworkZombieManager——只攔已知噪音樣式，
  未知警告與反作弊警告照常輸出。
- **防崩潰守衛 2 項**：hit/Zombie（guard-before-super）與 hit/Fall（縱深防禦）的 null 頭部守衛。
- **行為 1 項**：IsoAnimal（動物壓力三調：閒置衰減×2、聲音壓力÷3、屠宰連鎖上限減半，
  clamp 與行為路徑不動）。
- **安全屋修復 1 項**：SafehouseClaimPacket 遇到遺失的 square→room/building 綁定時，從
  authoritative roomList 補回 roomId，再完整執行原版權限、反作弊與安全屋驗證。
- **容器刷新修復 1 項**：LootRespawn 對自訂地圖缺少 vanilla TownZone 與黏性 construction flag
  加入窄範圍 fallback；只放行未搬動的原生固定容器，玩家製／搬動容器仍不刷新，安全屋仍由原版動態阻擋。
- **登入觀測 1 項**：LoginPacket 的三個同步 `ServerWorldDatabase` 寫入各自量測 `elapsedNs`；
  delegate、return/POP、例外與 auth/protocol 順序不變，log 不含玩家識別資料。這一版只建立歸因證據，
  不宣稱已優化登入或移除原生 busy 保護。
- **chunk unload entity removal 1 項**：只改道 `EngineEntityManager` 與 `EntityBucket` 的四個
  unordered identity add/remove callsite，以 weak-key＋primitive sidecar index 把批次卸載的重複
  O(N) 搜尋改成常態 O(1)；碰撞、外部 mutation、ordered/equality/null 路徑都保留原版 fallback。

- **受精蛋清除豁免 1 項**：`WorldItemRemovalList` 只比對 item type，無法區分受精蛋（受精是 `Food`
  的 per-instance 欄位，與一般蛋同為 `Base.Egg`），而 24 遊戲小時的清除門檻遠短於 1260 小時的
  孵化時間——改道 `IsoGridSquare.load` 內唯一的 `isIgnoreRemoveSandbox`，只在 vanilla 判定為
  不豁免時追加「可孵化且在孵化視窗內」的豁免；視窗天花板保證不會無界累積。

> 上表僅列到本節；2j~2n 的完整敘述見 docs/patches.md。

逐項 javap 證據與安全論證：[docs/patches.md](docs/patches.md)；分析原始規格：[docs/specs/](docs/specs/)。

## 建置

```powershell
# 需求：scoop temurin25-jdk（42.19 jar 已是 class file v69）、lib/asm-9.8.jar
scp <your-server>:/home/pzserver/serverfiles/java/projectzomboid.jar work\
.\build.ps1   # 編譯 → 手術 → 命中數 → 連結／bytecode／行為／尺度 benchmark → dist/
wsl bash patcher/tests/install-roundtrip.sh  # 隔離 temp serverfiles 上跑兩輪 install/uninstall
```

**命中數守門**：每處手術帶 expectedCount，PZ 更新後 build 漂移＝建置直接失敗（重新分析而非默默出錯）。

## 部署

```bash
# dist/ 整包丟到伺服器後：
bash install.sh     # 內建同源閘——逐 class 驗 jar hash，遊戲更新過就拒裝
# 下次伺服器重啟生效；移除：bash uninstall.sh
```

## PZ 更新 SOP

0. **更新前先在伺服器 `bash uninstall.sh`**。loose class 不在 Steam depot 內，`app_update`
   只換 jar 不會刪掉它們——殘留的舊 patched class 仍會覆蓋新 jar。
1. 重新拉 jar → `.\build.ps1`——命中數全過＝手術座標仍有效，直接重新部署。
2. 建置失敗＝該 class 已變——重跑對應分析（`work/specs/` 有原始規格與方法論）再更新 `PatchConfig.java`。
3. **命中數守門有盲點：數量對不代表改對地方。** 常數手術尤其要用 `javap` 確認該常數的**語境**
   （前後指令）。42.20 實例：`IsoAnimal.respondToSound` 的壓力算式從 `radius / 20.0F` 改寫成
   `radius * 0.05F`，同時新增了 `fleeDistance = radius * 3.0F + 20.0F`——方法內仍剛好有一個
   `20.0f`，舊座標會**通過守門卻改到逃跑距離**。每次更新都該重跑語境確認，不能只看命中數。
