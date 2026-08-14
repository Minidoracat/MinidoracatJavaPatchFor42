package zombie.mdc;

import zombie.iso.Vector2;

/**
 * W7 朝向暫存執行緒隔離（2026-08-13 Player-A 雞舍 chunk 抹除實案的根治，2026-08-14）。
 *
 * <p><b>根因</b>：{@code IsoGameCharacter.setForwardDirectionFromIsoDirection()} 用一個
 * <b>JVM 全域共用</b>的 {@code private static final Vector2 tempVector2_2} 當暫存傳遞朝向：
 * <pre>
 *   this.getVectorFromDirection(tempVector2_2);   // ① 寫入共用 static
 *   this.setForwardDirection(tempVector2_2);      // ② 讀回來 normalize()
 * </pre>
 * 而 {@code IsoMovingObject.getVectorFromDirection(Vector2, IsoDirections)} 的第一件事就是
 * <b>把 x、y 都歸零</b>再依方向填回真值。主執行緒（每 tick 為殭屍／動物／玩家呼叫）與
 * {@code ServerChunkLoader$LoaderThread}（chunk 反序列化）同時走這段、零同步：一方在
 * 歸零空窗期、另一方讀取，就拿到 (0,0)。{@code setForwardDirection(float,float)} 的
 * {@code normalize()} 得到長度 0，拋 {@code IllegalStateException}。
 *
 * <p><b>為什麼會毀存檔</b>：例外落在 {@code IsoAnimal.load} ← {@code IsoHutch.load}
 * ← {@code IsoGridSquare.load} ← {@code IsoChunk.LoadFromDisk} 時，
 * {@code IsoChunk.LoadChunk} 的失敗分支是 {@code Blam()}（8×8 格全部 setSquare(null)）
 * ＋{@code LoadBrandNew()}（用原版地圖重生），下一次世界存檔即永久覆寫玩家存檔。
 * 2026-08-13 19:55:03 chunk 1160,968 因此被抹除：46,142 bytes（雞舍＋32 隻家禽的完整
 * 基因組＋水桶）→ 8,549 bytes（只剩草地）。事故報告見 temp/report/。
 *
 * <p><b>存檔本身沒有壞</b>（可還原的依據）：{@code IsoDirections.fromIndex(int)} 是
 * {@code VALUES[index & 7]}，8 個方向全都有非零向量，存進檔案的方向值不可能產生零向量。
 * 失敗純屬競態擲骰——這是判定 blam 備份可直接還原的關鍵前提。
 *
 * <p><b>修法</b>：把該方法內兩處 {@code getstatic tempVector2_2} 各自接一個
 * INVOKESTATIC 到本 helper——吃掉共享實例、回傳<b>執行緒私有</b>的替身。同一執行緒內
 * ① 寫和 ② 讀拿到同一個實例，語意與原版逐字相同；跨執行緒則互不可見，競態消失。
 *
 * <p><b>不複製共享實例的內容</b>（刻意）：站點 ① 之後緊接的 {@code getVectorFromDirection}
 * 無條件覆寫 x、y，內容不具意義；站點 ② 要讀的正是站點 ① 寫進私有實例的值。複製共享
 * 內容反而會把別的執行緒的髒值帶進來。
 *
 * <p><b>不影響其他讀者</b>：原版 {@code tempVector2_2} 類別內共 12 個 getstatic
 * （另有 1 個 putstatic 在 {@code static {}} 初始化欄位），本方法佔 2 個，其餘 10 個
 * 分佈於 5 個方法，<b>逐一核對全部都是先寫後讀</b>：
 * <pre>
 *   processHitDamage        x2  .set(wielder 座標) → getVectorFromDirection(tempVector2_2)
 *   renderlast              x4  getNameCoordForPlayer／getNameCoords 填入後才讀 .x/.y
 *   isObjectBehind          x1  .set(this 座標)
 *   isBehind                x1  .set(chr 座標)
 *   updateMovementStatistics x2 .set(this 座標) → distanceTo(tempVector2_2)
 * </pre>
 * 沒有任何一處依賴本方法遺留在共享實例裡的值，故移除該副作用零耦合風險
 * （SmokeCheck 以類別內 getstatic 總數釘在 12，TIS 新增讀者即建置失敗）。
 *
 * <p><b>沒有計數觀測</b>（刻意）：本 helper 是唯一會被多執行緒同時呼叫的 helper，
 * 靜態計數器本身就是競態；且驗證訊號現成且更強——
 * {@code Forward Direction cannot be zero length vector} 在 server log 應歸零
 * （修前全 log 保留期 67 次，其中 66 次走 {@code IsoDirections.TEMP} 另一條獨立競態，
 * 不在本刀範圍，見 docs/patches.md 2s）。
 */
public final class ForwardVectorGuard {

    /**
     * 執行緒私有替身。每執行緒一個 Vector2（主執行緒＋各 chunk loader），
     * 皆為長生命週期執行緒且值為 8 bytes 的葉物件，無洩漏疑慮。
     */
    private static final ThreadLocal<Vector2> PRIVATE = ThreadLocal.withInitial(Vector2::new);

    /**
     * {@code setForwardDirectionFromIsoDirection} 內兩處 {@code getstatic tempVector2_2}
     * 的替身取得點。
     *
     * @param shared 共享實例（由 getstatic 壓入堆疊）。<b>刻意不使用</b>——本方法存在的
     *               目的就是把它換掉；收下它只為維持 FieldGetSwap 的 1→1 堆疊形狀。
     * @return 呼叫執行緒私有的 Vector2，永不為 null
     */
    public static Vector2 swap(Vector2 shared) {
        return PRIVATE.get();
    }

    private ForwardVectorGuard() {}
}
