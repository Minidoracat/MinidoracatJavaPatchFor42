# P5 設計 v2 — `zombie.mdc.CellListMembership`（generation bundle 版）

> v1 → v2 依據：Claude 對抗審查 6 項 important ＋ codex REDESIGN 五雷（.omc/p5-design-for-codex-review.md
> 附 codex 全文於 rescue thread task-msbwjp4m-gd0q0r）。手術目標與 15 呼叫點不變；重做的是
> 生命週期、remove 語意、removeAll fast path、kill/rebuild 狀態機。

## 0. 不變量（v1 寫錯的那條）

```
State.set == list 中所有「不同 identity」的集合
```

**不是** `set.size == list.size`。清單可含重複元素；移除一份後若仍有另一份，membership 必須保留。

## 1. 生命週期：generation bundle（取代 weak registry —— codex 首選修法）

```java
private static final class State {
    final Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
    int expectedSize = -1;          // -1 = 未初始化，首次 rebuild 不計 divergence
    int opCount;                    // audit 抽樣計數（fast-miss 也遞增——codex 雷 2）
}
private static final class Gen {
    final IsoCell cell;             // identity 錨點
    final ArrayList<IsoObject> p, r, s;
    final State pState = new State(), rState = new State(), sState = new State();
    Gen(IsoCell c) { cell = c; p = c.getProcessIsoObjects(); r = c.getProcessIsoObjectRemove(); s = c.getStaticUpdaterObjectList(); }
}
private static volatile Gen gen;                    // 整組替換
private static volatile boolean killed;             // process-lifetime terminal
private static final AtomicInteger divergenceTotal = new AtomicInteger();   // Claude 修 6
private static final Object CONTROL = new Object(); // 線性化 gen 換代與 kill（codex 雷 4a）
```

- 解析：`cur = gen`；`cur == null || cur.cell != IsoCell.getInstance()` → `synchronized(CONTROL)` 內
  double-check ＋ `killed` 檢查後整組重建。**kill 與 stateFor 共用 CONTROL** —— kill 後 `gen = null`
  且重建路徑先看 `killed` → 直接回 null（殺掉「kill 後重插」競態）。
- 傳入 list 與當代 p/r/s **identity 不符** → 回 null → 呼叫端走 vanilla（舊 cell 殘留呼叫自然降級）。
- 換代即整組丟棄舊 State——`IsoCell.Dispose()` 不清 P/R/S 也無所謂，沒有 weak/GC 猜測，
  沒有 `STATES→State.set→IsoObject.table→舊 ArrayList` 反向釘住問題（codex 雷 3）。

## 2. 各 op 語意（15 呼叫點 → 6 個 helper 方法）

改道簽名一律 receiver 前插：`contains(Ljava/util/ArrayList;Ljava/lang/Object;)Z`、
`add(...)Z`、`remove(...)Z`、`clear(Ljava/util/ArrayList;)V`、
`removeAll(Ljava/util/ArrayList;Ljava/util/Collection;)Z`。killed 或 State==null → 原方法直呼。

**contains**：State lock 內：size 對帳（不符→rebuild＋divergence++）→ 每 4096 op 抽驗
`set.contains(o)==list.contains(o)`（不符→rebuild＋divergence++，以 rebuild 後 list 掃描為準）→ 回 `set.contains(o)`。

**add**：`boolean r = list.add(o); set.add(o); expectedSize = list.size(); return r;`
（ArrayList.add 恆 true；重複元素：list 收多份、set 一份，符合不變量。）

**remove（codex 雷 1＋2 的修正核心）**：
```java
if (state == null) return list.remove(o);
synchronized (state) {
    reconcileSize(state, list);                       // 不符 → rebuild + divergence
    if (!state.set.contains(o)) {                     // fast-miss 也走 audit（雷 2）
        if (++state.opCount % AUDIT_MASK == 0 && list.contains(o)) {
            rebuild(state, list); divergence();       // ghost 抓到 → 修復後繼續
        } else return false;
    }
    boolean r = list.remove(o);                       // hit 保留 vanilla 保序 O(N)
    if (r) {
        state.expectedSize = list.size();
        if (!list.contains(o)) state.set.remove(o);   // 重複元素感知（雷 1）
    }
    return r;
}
```

**clear**：`list.clear(); set.clear(); expectedSize = 0;`

**removeAll（codex 處方逐條照抄）**：
1. **嚴格 gate**：`killed || list.getClass() != ArrayList.class || c == null || c.getClass() != ArrayList.class`
   → 原生 `list.removeAll(c)`（null 的 NPE parity、subclass/custom Collection 的
   `c.contains` 副作用 parity 全由原生承擔）。`c.isEmpty()` → return false（原生等價）。
2. R monitor 下做**固定大小 identity snapshot**：`int n = c.size(); Object[] snap = ...get(i)`
   —— 不用 iterator（不引入 vanilla 沒有的 CME 面）、不用動態 `i < c.size()`。
3. P monitor 下單趟壓實 survivors（`list.set(w++, e)`），然後**從尾端逐一 `list.remove(i)`**
   （`for (int i = n-1; i >= w; i--) list.remove(i)`）—— 每次 O(1)，
   **每刪一個 `modCount++`，精確還原 JDK `batchRemove` 的 `modCount += removedCount`**。
4. 結構變更全部成功後才 rebuild/commit P 的 sidecar；任何例外 → `expectedSize = -1`（毒化，
   下次強制 rebuild）再重拋 —— 不會半提交（codex removeAll 表的「例外中途提交」修正）。
5. 鎖序固定 **P→R**，全 helper 無任何 R→P 路徑（codex 雷 4e）。

## 3. rebuild / kill 狀態機（codex 雷 4 全套）

```
ACTIVE --size/audit 漂移--> REBUILD --成功--> ACTIVE
ACTIVE/REBUILD --divergenceTotal ≥ 8 或 domain violation--> KILLED（terminal）
KILLED：gen=null、helper 全數直通 vanilla；換代不復活
```

- rebuild 用**暫存 set**，成功才 commit＋`expectedSize = list.size()`（雷「rebuild 半失敗」：
  live shrink 拋 IOOBE → catch → 保留舊 set、divergence++、本次 op 走 vanilla，不無限 retry）。
- `expectedSize == -1` 的首次 rebuild 不計 divergence；其後所有 mismatch 都計。
- rebuild 本身**也計入** divergenceTotal（雷「rebuild storm 永不 kill」：持續旁路變異
  會累積到 kill，而不是每次默默 O(N) 重建）。
- **先 kill 再 log**：kill 動作在 CONTROL 內完成（`killed=true; gen=null;`），log 移出所有
  monitor 之後才發（雷「logging 排在 safety transition 前」）。
- 觀測：沿用水位模式，`[MinidoracatJavaPatch][CellList]` 每 2^20 op 印
  maxP/maxR/maxS/rebuilds/divergence/killed。

## 4. SmokeCheck（Claude 修 3＋4、codex 翻車位 1/2/5）

**結構（全序語境鎖，比照 popman 模式）**：三個 patched class 六個方法，每站鎖
`GETFIELD 目標欄位（或 getter invokevirtual）→ aload → INVOKESTATIC helper` 的指令鏈與
descriptor 精確匹配（`remove(Ljava/lang/Object;)Z` 不得誤中 `remove(I)`）；殘留
`invokevirtual java/util/ArrayList.{contains,add,remove,removeAll,clear}` 於六方法內全部歸零；
helper 六方法簽名逐一斷言。

**differential（隨機序列對照 vanilla）**：helper 鏡射操作 vs 純 ArrayList，序列含：重複元素、
null、null 重複、R 空、R⊄P、removeAll 全重複、interleaved add/remove —— 斷言清單內容、順序、
回傳值全等；ArrayList 匿名子類傳入 removeAll → 斷言走 vanilla fallback（gate 生效）。

**自癒與 kill**：旁路直改 list → 下一 op rebuild；等大小換血 → audit 在 ≤4096 op 內抓到；
連續漂移 → divergenceTotal 達 8 → killed → 斷言 helper 直通且 gen 為 null、
換代後仍 killed；kill 前後 log 順序（結構斷言 log 呼叫在 monitorexit 之後）。

**全 jar 斷言**：IsoObject 全部後代（實測 57 類）zero equals/hashCode 覆寫——hierarchy walk
斷言，任何未來 build 有子類新增覆寫即建置失敗（Claude 修 3）。

**部署原子性**：IsoCell＋IsoObject＋IsoDeadBody＋helper 同 manifest（既有 pipeline 自動保證，
manifest 完整性守門已在 build.ps1）。

## 5. 明確接受的殘餘風險（兩審聯集）

- Lua 旁路的等大小換血：audit 抽樣＋kill 門檻兜底，機率極低（codex acceptable）。
- 旁路注入重複元素造成的 parity 邊角：vanilla 守衛流程下不可能產生，javadoc 記載。
- killed 後每 op 仍付一次 volatile 讀＋（有 State 時）monitor：數十 ns，可忽略。
- 晚 kill：divergenceTotal 為 AtomicInteger 後已無丟失更新，此項關閉。
