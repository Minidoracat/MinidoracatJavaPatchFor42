# PZ 目前使用中的 Java Patch（2026-06-13）

整理時間：2026-06-13 23:11:43 +0800

---

## 判定方式

本文件以**目前實際 classpath 會載入的 loose class 狀態**為準，不以舊文件是否曾經記錄過為準。

### Server launcher 依據

來源：`<PZ Dedicated Server>\StartServer64.bat`

```bat
SET PZ_CLASSPATH=java/;java/projectzomboid.jar
```

這代表：

- `java/` 目錄下的 loose `.class` 會優先於 `java/projectzomboid.jar`
- 只要 `java/.../*.class` 存在且與 JAR 內同路徑 class 不同，就會成為**目前實際生效**的 patch

---

## 一、Server 端目前使用中的 Java Patch（Active）

以下 10 個 loose class 已確認存在於：

```text
<PZ Dedicated Server>\java\
```

且都與 `projectzomboid.jar` 內對應 class **不同**，因此目前為 active。

### 1. ActionStateContainer

- 路徑：`zombie/characters/action/ActionStateContainer.class`
- 類型：log noise suppression
- 用途：壓掉部分 `ActionSystem` unsupported child-state 類警告
- loose SHA256：`2060f95fa857bd2941851a99b9ef79a6f50aa2ad6ffda7e2d7a3b61bf4ec2ab0`
- jar SHA256：`efc5403de2f88725a2e0627d2d630627b9c4db094a961caa5ab237543c7fc1d6`

### 2. IsoAnimal

- 路徑：`zombie/characters/animals/IsoAnimal.class`
- 類型：行為 patch
- 用途：動物壓力相關 patch
- loose SHA256：`8f011ff82ea37bc6cc5ca1ba975fafc88e86a583189baad08a7d480ece9fd73e`
- jar SHA256：`e61e73724e8d02f23e5569144b4855cfb473cdf0dd27d976b9193db3995fa606`

### 3. AnimationSet

- 路徑：`zombie/core/skinnedmodel/advancedanimation/AnimationSet.class`
- 類型：log noise suppression
- 用途：壓掉部分缺少 `AnimState` 類 warning
- loose SHA256：`3b888a8cec0964d1b5e2303b94c7da72d5b5a83d56f1f8dd1a24d3353a40d6ce`
- jar SHA256：`50b6f5142624555b7af8a3982040720dffc855ae8212b39f7314f3cb45c00f5d`

### 4. SkinningBoneHierarchy

- 路徑：`zombie/core/skinnedmodel/model/SkinningBoneHierarchy.class`
- 類型：log noise suppression
- 用途：ragdoll / missing-bone warning suppression
- loose SHA256：`648a3354105df11aead4d7a6ec3cbd718a3bb253fea0c2d9f90fc4c1bbc1b9e0`
- jar SHA256：`84999e800c6c538b716c95c3578c08fcc47ebddcf85f2ba7b31a1abf5314b52e`

### 5. SpriteConfig

- 路徑：`zombie/entity/components/spriteconfig/SpriteConfig.class`
- 類型：log noise suppression
- 用途：只壓指定 `Invalid SpriteConfig object!` 名稱的 log，不修底層根因
- 目前 suppress 名稱：
  - `MetalBigWireFence`
  - `WoodFloorLvl3`
  - `Wooden_Windows`
- 邊界：
  - 保留 `resetObjectInfo()`
  - 保留其他 invalid name（例如 `null`）的 warning
- loose SHA256：`3fe8b3ff19e1e0b23301111dd2189199fbf92422d07256ec8b99225e762e9c7b`
- jar SHA256：`11335fd98180810e314cfcb8fd9e88cc58550f3891c800fbd78ac0c558208706`

### 6. ItemPickInfo

- 路徑：`zombie/inventory/ItemPickInfo.class`
- 類型：log noise suppression
- 用途：壓掉 unknown container-ID 類 log
- loose SHA256：`597187158dc502fdc9d43c37919f139e814eb3332a3445c06902dd67e212f6b9`
- jar SHA256：`43d3e2efc1348d0172cb9fae9662c9c5cf120204edbf49da749a8c5759935f44`

### 7. PacketsCache

- 路徑：`zombie/network/PacketsCache.class`
- 類型：行為 / 網路相關 patch
- 用途：封包速率 / 黑邊問題相關 patch
- 備註：舊文件裡對它的描述有版本演進，現在只應以「目前 loose class 實際存在且蓋過 jar」這件事為準
- loose SHA256：`e8d1c3f0d1b5ee52c4512ac1b1d6d0fecea2043daafd858a376e93995137b420`
- jar SHA256：`7ef3fccf76e5e59405ca96f932d391f1705f3b131037183ee30b891a1961642e`

### 8. PacketTypes$PacketType

- 路徑：`zombie/network/PacketTypes$PacketType.class`
- 類型：行為 / 網路相關 patch
- 用途：`packet ... is not consistent` 類 warn / 處理路徑相關 patch
- 備註：這類 patch 歷史上改過版本，不要直接把舊文檔的每一句邏輯當成今天的最終真相
- loose SHA256：`b554c7af2ace06edade3412ca76f131862110c9a9d3e9fb943c095a82ae0af16`
- jar SHA256：`7f635337f9b116f3910abc12eb1d3029b0641d81caeff95dd849185ad7aebdef`

### 9. NetworkZombieManager

- 路徑：`zombie/popman/NetworkZombieManager.class`
- 類型：log noise suppression
- 用途：壓掉 `moveZombie: There are no zombies in nz.zombies.` 類 log noise
- loose SHA256：`fc135329bdb67a12e556ad1981ce8daae7b302c33f75ad72519b932f3411d6d5`
- jar SHA256：`d50c53736d6b4bcf2b59ebbc445c525a22f8d01c943d0dc4e361ba65b34dfc5a`

### 10. ZombieCountOptimiser

- 路徑：`zombie/popman/ZombieCountOptimiser.class`
- 類型：行為 patch
- 用途：殭屍 culling / 自動刪除相關 patch
- loose SHA256：`adb7078289f21e723686fecb97bf51dfee81745df21bfe7ef19635320eec81c7`
- jar SHA256：`7a0147f9ca8d3e346a9cc4ed33d02f1d9842037ba3704601b0da3f688bce2077`

---

## 二、Server 端目前沒有在用的舊 Patch / 已停用項

### 1. PlayerHitZombiePacket Patch 13：已停用

目前現場找到的是：

```text
<PZ Dedicated Server>\java\zombie\network\packets\hit\PlayerHitZombiePacket.class.disabled_patch13_20260603_175935
```

結論：

- 這代表 **Patch 13 現在不是 active**
- 不應再把它列入目前使用中的 patch

### 2. 以下 class 目前沒有 active loose override

我已直接檢查 server `java/`，目前以下檔案都**不存在**：

- `zombie/WorldSoundManager.class`
- `zombie/network/packets/character/ZombieDeletePacket.class`
- `zombie/network/packets/character/PlayerHitZombiePacket.class`

結論：

- 這幾個即使曾出現在舊文件，也**不是目前實際生效 set**

---

## 三、Client 端目前狀態

### 結論：目前沒有 active 的自製 client loose Java patch

檢查路徑：

```text
<PZ Client>
```

結果：

- top-level `zombie/**/*.class` active loose override：**0 個**
- 目前沒有 active 的自製 client Java patch

### 已停用殘留檔

```text
<PZ Client>\zombie\characters\IsoGameCharacter.class.disabled_client_aimturn_20260608_104329
```

對應說明：

- 這是 client aim-turn prototype
- 安裝時間：2026-06-08 10:26 local time
- 停用時間：2026-06-08 10:43 local time
- 停用原因：Baker 實測回報**沒效果**

因此 client 端現況可簡寫為：

- **Active：0 個**
- **Disabled：IsoGameCharacter aim-turn prototype 1 個**

---

## 四、最短版摘要

### Server active（10 個）

```text
zombie/characters/action/ActionStateContainer.class
zombie/characters/animals/IsoAnimal.class
zombie/core/skinnedmodel/advancedanimation/AnimationSet.class
zombie/core/skinnedmodel/model/SkinningBoneHierarchy.class
zombie/entity/components/spriteconfig/SpriteConfig.class
zombie/inventory/ItemPickInfo.class
zombie/network/PacketsCache.class
zombie/network/PacketTypes$PacketType.class
zombie/popman/NetworkZombieManager.class
zombie/popman/ZombieCountOptimiser.class
```

### Server inactive / disabled

```text
zombie/network/packets/hit/PlayerHitZombiePacket.class.disabled_patch13_20260603_175935
```

### Client active

```text
0 個
```

### Client disabled

```text
<PZ Client>\zombie\characters\IsoGameCharacter.class.disabled_client_aimturn_20260608_104329
```

---

## 五、本次整理的主要依據

### 直接檢查的實際路徑

- `<PZ Dedicated Server>\StartServer64.bat`
- `<PZ Dedicated Server>\java\`
- `<PZ Dedicated Server>\java\projectzomboid.jar`
- `<PZ Client>`
- `<PZ Client>\projectzomboid.jar`

### 交叉參考文件

- `<local workspace>\CODEX_CONTEXT.md`
- `<local workspace>\CODEX_JAVA_PATCHES.md`
- `<local workspace>\markdown\server_java_patches.md`
- `<local workspace>\client_java_patches.md`
- `<local workspace>\review\backups\B42_SpriteConfigNoisePatch_20260612_021009\README.md`

### 方法

- 掃描 dedicated server `java/` 目錄中的 top-level loose `.class`
- 與 `projectzomboid.jar` 內同路徑 class 比對 SHA256
- `DIFF` 視為 active override
- `.disabled*` 視為停用但保留的歷史 patch
- client 端另行檢查 top-level `zombie/**/*.class` 是否存在
