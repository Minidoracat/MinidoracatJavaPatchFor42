# MinidoracatJavaPatchFor42

PZ B42 專用伺服器 loose-class 優化 patch——自製、可重生、帶同源防護。

## 原理

PZ 伺服器 classpath 為 `java/.` 優先於 `java/projectzomboid.jar`：同路徑的 loose `.class`
會覆蓋 jar 內的原版。本專案**不重編譯反編譯原始碼**，而是用 ASM 直接對 jar 內的原版
bytecode 做兩種「堆疊形狀不變」的手術（log 呼叫改道／方法內常數替換），
StackMapFrames 原樣保留；過濾邏輯寫在普通 Java 類 `zombie/mdc/LogFilter.java`
（javac 對遊戲 jar 編譯，隨 patch 出貨）。

## 內容（10 class、12 處手術、22 個命中點）

- **抑噪 8 項**：ActionStateContainer／AnimationSet／SkinningBoneHierarchy／SpriteConfig（選擇性）／
  ItemPickInfo／NetworkZombieManager／PacketsCache／PacketTypes$PacketType——只攔已知噪音樣式，
  未知警告與反作弊警告照常輸出。
- **行為 2 項**：ZombieCountOptimiser（超額殭屍 culling 取樣 1/3→1/2，五道安全條件不動）、
  IsoAnimal（動物壓力三調：閒置衰減×2、聲音壓力÷3、屠宰連鎖上限減半，clamp 與行為路徑不動）。

逐項 javap 證據與安全論證：[docs/patches.md](docs/patches.md)；分析原始規格：[docs/specs/](docs/specs/)。

## 建置

```powershell
# 需求：scoop temurin25-jdk（42.19 jar 已是 class file v69）、lib/asm-9.8.jar
scp <your-server>:/home/pzserver/serverfiles/java/projectzomboid.jar work\
.\build.ps1   # 編譯 → 手術 → 命中數守門 → 連結驗證 → dist/
```

**命中數守門**：每處手術帶 expectedCount，PZ 更新後 build 漂移＝建置直接失敗（重新分析而非默默出錯）。

## 部署

```bash
# dist/ 整包丟到伺服器後：
bash install.sh     # 內建同源閘——逐 class 驗 jar hash，遊戲更新過就拒裝
# 下次伺服器重啟生效；移除：bash uninstall.sh
```

## PZ 更新後 SOP

1. 重新拉 jar → `.\build.ps1`——命中數全過＝手術座標仍有效，直接重新部署。
2. 建置失敗＝該 class 已變——重跑對應分析（`work/specs/` 有原始規格與方法論）再更新 `PatchConfig.java`。
