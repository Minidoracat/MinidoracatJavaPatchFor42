package zombie.mdc;

import java.io.BufferedWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jdk.jfr.Configuration;
import jdk.jfr.Event;
import jdk.jfr.Recording;
import org.json.JSONObject;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * 有界的 Java→Lua 入口採樣；每個 capture 獨立擁有統計，不共用舊時間桶。
 * 關閉時不查時鐘、不配樣本。開啟時 Frame 在各執行緒重用，統計寫入在短鎖內；
 * 這不是零成本 profiler，實際負擔須量測。只保留 Prototype，不保留 closure/env/args。
 */
public final class MdcProfiler {
    public static final int API_VERSION = 1;
    public static final int MAX_ROWS = 100;
    static final int MAX_SOURCES = 512;
    static final int MAX_DEPTH = 64;
    static final int MAX_THREADS = 32;
    private static final int MAX_SKIPPED = 2048;
    private static final int MAX_NAMED = 128;
    private static final long MAX_TOKEN = (1L << 53) - 1;
    private static final Object LIFECYCLE = new Object();
    private static final AtomicLong TOKENS = new AtomicLong();
    private static final ThreadLocal<ThreadState> LOCAL = ThreadLocal.withInitial(ThreadState::new);
    private static volatile Capture active;
    private static volatile Capture last;
    private static volatile boolean hookObserved;
    private static long sequence;
    private static ScheduledThreadPoolExecutor worker;

    private MdcProfiler() {}

    public static boolean isHookObserved() { return hookObserved; }
    public static boolean isRecording() { return active != null; }

    public static Map<String, Object> start(double seconds, String filter) {
        if (!Double.isFinite(seconds) || seconds < 1 || seconds > 300) {
            return failure("invalid-duration", "Recording duration must be between 1 and 300 seconds.");
        }
        String selected = filter == null ? "" : filter.trim();
        if (selected.length() > 64 || selected.indexOf('\n') >= 0 || selected.indexOf('\r') >= 0) {
            return failure("invalid-filter", "Source filter must be a single line of at most 64 characters.");
        }
        synchronized (LIFECYCLE) {
            if (active != null) return failure("already-recording", "A capture is already recording.");
            if (last != null && "saving".equals(last.state)) return failure("saving", "The previous capture is still being saved.");
            Recording recording = null;
            try {
                Path root = Path.of(ZomboidFileSystem.instance.getCacheDir(), "Lua", "MinidoracatDevProfiler", "captures");
                String side = GameServer.server ? "server" : GameClient.client ? "client" : "single";
                String pzVersion = String.valueOf(Core.getInstance().getVersion());
                String id = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
                Files.createDirectories(root);
                Path output = Files.createDirectory(root.resolve(id));
                recording = new Recording(Configuration.getConfiguration("default"));
                recording.setName("MinidoracatDevProfiler-" + id);
                recording.setMaxSize(64L * 1024 * 1024);
                recording.setMaxAge(java.time.Duration.ofSeconds(310));
                recording.enable(SourceEvent.class).withoutStackTrace();
                recording.start();
                // JFR 的首次初始化不算成 Lua 錄製時長。
                long now = System.nanoTime();
                Capture capture = new Capture(++sequence, id, selected, seconds, now, output, side, pzVersion, recording);
                ensureWorker();
                capture.expiry = worker.schedule(() -> finish(capture, "timeout", null),
                        (long) (seconds * 1_000_000_000d), TimeUnit.NANOSECONDS);
                // 期限任務排定後才發布：start 失敗時不留下永遠回報 recording 的假 capture。
                last = capture;
                active = capture;
                Map<String, Object> result = head(capture);
                result.put("ok", true);
                result.put("code", "started");
                result.put("requestedSeconds", seconds);
                result.put("filter", selected);
                result.put("jfr", true);
                return result;
            } catch (Exception e) {
                if (recording != null) recording.close();
                return failure("start-failed", brief(e));
            }
        }
    }

    public static Map<String, Object> stop() {
        Capture capture = active;
        if (capture != null) finish(capture, "stop", null);
        Capture resultCapture = capture == null ? last : capture;
        Map<String, Object> result = head(resultCapture);
        result.put("ok", true);
        result.put("code", capture == null ? "already-stopped" : "stopped");
        result.put("outputDir", resultCapture == null ? "" : resultCapture.output.toString());
        result.put("error", resultCapture == null || resultCapture.error == null ? "" : resultCapture.error);
        return result;
    }

    public static Map<String, Object> snapshot(int limit) {
        Capture current = active;
        Capture capture = current == null ? last : current;
        Map<String, Object> result = head(capture);
        result.put("ok", true);
        if (capture == null) {
            result.put("rows", List.of());
            result.put("elapsedSeconds", 0d);
            result.put("remainingSeconds", 0d);
            result.put("dropped", 0L);
            result.put("internal", 0L);
            result.put("error", "");
            result.put("outputDir", "");
            return result;
        }
        List<Row> rows;
        synchronized (capture.lock) {
            rows = capture.finalRows == null ? rowsLocked(capture) : capture.finalRows;
            long end = capture.closed ? capture.endNs : System.nanoTime();
            result.put("elapsedSeconds", (end - capture.startNs) / 1_000_000_000d);
            result.put("remainingSeconds", capture.closed ? 0d : Math.max(0, capture.deadlineNs - end) / 1_000_000_000d);
            result.put("state", capture.state);
            result.put("filter", capture.filter);
            result.put("dropped", capture.dropped);
            result.put("unfinished", capture.unfinished);
            result.put("unattributed", capture.unattributed);
            result.put("internal", capture.internal);
            result.put("threads", capture.threadIds.size());
            result.put("error", capture.error == null ? "" : capture.error);
            result.put("outputDir", capture.output.toString());
        }
        int count = Math.min(Math.max(1, Math.min(MAX_ROWS, limit)), rows.size());
        List<Map<String, Object>> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(rows.get(i).values());
        result.put("rows", values);
        result.put("rowsTotal", rows.size());
        return result;
    }

    static Frame enter(Object function) {
        if (!hookObserved) hookObserved = true;
        Capture capture = active;
        if (capture == null) return null;
        try {
            if (System.nanoTime() >= capture.deadlineNs) {
                finish(capture, "timeout", null);
                return null;
            }
            if (!(function instanceof LuaClosure closure) || closure.prototype == null) {
                synchronized (capture.lock) { if (!capture.closed) capture.unattributed++; }
                return null;
            }
            Source source;
            synchronized (capture.lock) {
                if (capture.closed) return null;
                source = sourceLocked(capture, closure.prototype);
            }
            // 排除的子入口仍有輕量 Frame，才能從其父入口扣除已觀測子時間。
            return push(capture, source, false);
        } catch (RuntimeException e) {
            finish(capture, "sampler-error", brief(e));
            return null;
        }
    }

    static void exit(Frame frame, boolean thrown) {
        if (frame == null) return;
        Capture capture = frame.capture;
        if (capture == null) return;
        // 回傳點先取時鐘：統計鎖的等待時間不能算成 Lua 的執行時間。
        long end = System.nanoTime();
        try {
            close(frame, capture, end, thrown);
        } catch (RuntimeException e) {
            // 這裡執行在遊戲 callback 的 finally 內；採樣失敗只能記錄，不得取代原回傳值或原例外。
            if (capture.error == null) capture.error = "sampler-error: " + brief(e);
        }
    }

    private static void close(Frame frame, Capture capture, long end, boolean thrown) {
        if (frame.owner.threadId != Thread.currentThread().threadId()) {
            finish(capture, "wrong-thread", "A profiler frame was closed from another thread.");
            return;
        }
        ThreadState state = frame.owner;
        boolean unbalanced = false;
        synchronized (capture.lock) {
            if (!frame.live) return;
            if (state.depth - 1 != frame.slot) {
                // 只回收尚未配對的具名 Frame；不碰 Kahlua 自己的 stack。
                while (state.depth - 1 > frame.slot && state.frames[state.depth - 1].named) {
                    Frame abandoned = state.frames[--state.depth];
                    if (abandoned.capture == capture && abandoned.source != null) {
                        if (!capture.closed) {
                            capture.openSelected--;
                            capture.dropped++;
                        }
                    }
                    abandoned.clear();
                    unbalanced = true;
                }
                if (state.depth - 1 != frame.slot) {
                    capture.error = "profiler-stack-mismatch";
                    return;
                }
            }
            long inclusive = Math.max(0, end - frame.startNs);
            long exclusive = Math.max(0, inclusive - frame.childNs);
            state.depth--;
            if (state.depth > 0) {
                Frame parent = state.frames[state.depth - 1];
                if (parent.capture == capture) parent.childNs += inclusive;
            }
            if (frame.source != null) {
                if (!capture.closed) {
                    capture.openSelected--;
                    Source source = frame.source;
                    source.calls++;
                    source.inclusiveNs += inclusive;
                    source.exclusiveNs += exclusive;
                    source.maxNs = Math.max(source.maxNs, inclusive);
                    if (thrown) source.thrown++;
                }
            }
            frame.clear();
        }
        if (unbalanced) finish(capture, "named-unbalanced", "A named region was not paired before its Lua callback returned.");
    }

    public static long beginNamed(String label) {
        Capture capture = active;
        if (capture == null) return 0;
        if (label == null || label.isBlank() || label.length() > 128) {
            finish(capture, "invalid-label", "Named regions require a nonempty label of at most 128 characters.");
            return 0;
        }
        try {
            Source source;
            synchronized (capture.lock) {
                if (capture.closed) return 0;
                source = capture.named.get(label);
                if (source == null) {
                    if (!capture.filter.isEmpty() && !contains(label, capture.filter)) return 0;
                    if (capture.named.size() >= MAX_NAMED || capture.sources.size() >= MAX_SOURCES) {
                        capture.dropped++;
                        return 0;
                    }
                    source = new Source("named", "<named>", 0, clean(label, 128));
                    capture.named.put(label, source);
                    capture.sources.add(source);
                }
            }
            Frame frame = push(capture, source, true);
            return frame == null ? 0 : frame.token;
        } catch (RuntimeException e) {
            finish(capture, "sampler-error", brief(e));
            return 0;
        }
    }

    public static void endNamed(long token) {
        if (token == 0) return;
        ThreadState state = LOCAL.get();
        Frame top = state.depth == 0 ? null : state.frames[state.depth - 1];
        if (top != null && top.named && top.token == token) {
            exit(top, false);
            return;
        }
        Capture capture = active;
        if (capture != null && token > capture.tokenFloor && token <= capture.lastToken) {
            finish(capture, "named-mismatch", "Named token does not belong to the current thread's top region.");
        }
        // 舊 capture 的 token 不可關掉下一輪的 Frame。
    }

    public static void onLuaReset() {
        Capture capture = active;
        if (capture != null) finish(capture, "lua-reset", "Lua environment was reset during capture.");
    }

    private static Frame push(Capture capture, Source source, boolean named) {
        ThreadState state = LOCAL.get();
        synchronized (capture.lock) {
            if (capture.closed || System.nanoTime() >= capture.deadlineNs) return null;
            while (state.depth > 0) {
                Frame top = state.frames[state.depth - 1];
                if (!top.named || !top.capture.closed) break;
                top.clear();
                state.depth--;
            }
            if (state.registeredCapture != capture.sequence) {
                if (capture.threadIds.size() >= MAX_THREADS && !capture.threadIds.contains(state.threadId)) {
                    capture.dropped++;
                    return null;
                }
                capture.threadIds.add(state.threadId);
                state.registeredCapture = capture.sequence;
            }
            if (state.depth >= MAX_DEPTH) {
                capture.dropped++;
                return null;
            }
            Frame frame = state.frames[state.depth];
            long token = named ? TOKENS.incrementAndGet() : 0;
            if (token > MAX_TOKEN) {
                capture.error = "named-token-space-exhausted";
                capture.dropped++;
                return null;
            }
            frame.capture = capture;
            frame.source = source;
            frame.named = named;
            frame.token = token;
            frame.childNs = 0;
            frame.startNs = System.nanoTime();
            frame.live = true;
            state.depth++;
            if (source != null) capture.openSelected++;
            if (named) capture.lastToken = token;
            return frame;
        }
    }

    private static Source sourceLocked(Capture capture, Prototype prototype) {
        Source known = capture.byPrototype.get(prototype);
        if (known != null) return known;
        if (capture.skipped.containsKey(prototype)) return null;
        String file = prototype.filename != null ? prototype.filename : prototype.file != null ? prototype.file : "<unknown>";
        String name = prototype.name == null ? "" : prototype.name;
        boolean internal = contains(file, "MinidoracatDevProfiler");
        boolean skipInternal = internal && !contains(capture.filter, "DevProfiler");
        boolean matches = capture.filter.isEmpty() || contains(file, capture.filter) || contains(name, capture.filter);
        if (skipInternal || !matches) {
            if (capture.skipped.size() < MAX_SKIPPED) {
                capture.skipped.put(prototype, Boolean.TRUE);
                if (skipInternal) capture.internal++;
            }
            return null;
        }
        if (capture.sources.size() >= MAX_SOURCES) {
            capture.dropped++;
            return null;
        }
        String normalized = file.replace('\\', '/');
        String mod = "vanilla";
        int mods = normalized.indexOf("/mods/");
        if (mods >= 0) {
            int begin = mods + 6;
            int slash = normalized.indexOf('/', begin);
            if (slash > begin) {
                mod = normalized.substring(begin, slash);
                normalized = normalized.substring(slash + 1);
            }
        }
        int line = prototype.lines != null && prototype.lines.length > 0 ? Math.max(0, prototype.lines[0]) : 0;
        Source source = new Source(clean(mod, 120), clean(normalized, 512), line, clean(name, 120));
        capture.byPrototype.put(prototype, source);
        capture.sources.add(source);
        return source;
    }

    private static void ensureWorker() {
        if (worker != null) return;
        worker = new ScheduledThreadPoolExecutor(1, action -> {
            Thread thread = new Thread(action, "MdcProfiler-control");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        // 一次只允許一份 recording 或 saving；佇列至多有該份 deadline 與匯出。
    }

    private static void finish(Capture capture, String reason, String error) {
        synchronized (LIFECYCLE) {
            synchronized (capture.lock) {
                if (capture.closed) return;
                capture.closed = true;
                capture.endNs = System.nanoTime();
                capture.endUtc = Instant.now().toString();
                capture.stopReason = reason;
                if (error != null && capture.error == null) capture.error = error;
                capture.unfinished = capture.openSelected;
                capture.dropped += capture.unfinished;
                capture.state = "saving";
                if (active == capture) active = null;
                if (capture.expiry != null) capture.expiry.cancel(false);
            }
            worker.execute(() -> export(capture));
        }
    }

    private static void export(Capture capture) {
        try {
            List<Row> rows;
            synchronized (capture.lock) {
                rows = rowsLocked(capture);
                capture.finalRows = rows;
                capture.byPrototype.clear();
                capture.skipped.clear();
                capture.named.clear();
                capture.sources.clear();
            }
            try (BufferedWriter csv = Files.newBufferedWriter(capture.output.resolve("summary.csv"), StandardCharsets.UTF_8)) {
                csv.write("mod,file,line,name,calls,inclusive_ms,exclusive_ms,max_ms,thrown\n");
                for (Row row : rows) {
                    csv.write(quote(row.mod) + "," + quote(row.file) + "," + row.line + "," + quote(row.name)
                            + "," + row.calls + "," + milliseconds(row.inclusiveNs) + "," + milliseconds(row.exclusiveNs)
                            + "," + milliseconds(row.maxNs) + "," + row.thrown + "\n");
                    SourceEvent event = new SourceEvent();
                    event.captureId = capture.id;
                    event.mod = row.mod;
                    event.file = row.file;
                    event.line = row.line;
                    event.name = row.name;
                    event.calls = row.calls;
                    event.inclusiveNs = row.inclusiveNs;
                    event.exclusiveNs = row.exclusiveNs;
                    event.maxNs = row.maxNs;
                    event.thrown = row.thrown;
                    event.commit();
                }
            }
            capture.recording.stop();
            capture.recording.dump(capture.output.resolve("capture.jfr"));
            capture.recording.close();
            JSONObject metadata = new JSONObject();
            metadata.put("schemaVersion", 1).put("captureId", capture.id).put("side", capture.side)
                    .put("pzVersion", capture.pzVersion).put("jvmVersion", System.getProperty("java.runtime.version"))
                    .put("startUtc", capture.startUtc).put("endUtc", capture.endUtc)
                    .put("durationSeconds", (capture.endNs - capture.startNs) / 1_000_000_000d)
                    .put("requestedSeconds", capture.seconds).put("filter", capture.filter)
                    .put("dropped", capture.dropped).put("unfinished", capture.unfinished)
                    .put("error", capture.error == null ? JSONObject.NULL : capture.error)
                    .put("complete", capture.dropped == 0 && capture.error == null)
                    .put("rowsWritten", rows.size()).put("threads", capture.threadIds.size())
                    .put("unattributed", capture.unattributed).put("internalRowsSuppressed", capture.internal)
                    .put("patchVersion", MdcPatchBuildInfo.VERSION).put("patchBuilt", MdcPatchBuildInfo.BUILT)
                    .put("jarSha256", MdcPatchBuildInfo.JAR_SHA256).put("stopReason", capture.stopReason).put("jfr", true);
            Files.writeString(capture.output.resolve("metadata.json"), metadata.toString(2), StandardCharsets.UTF_8);
            capture.state = capture.error == null ? "complete" : "error";
            System.out.println("[MinidoracatDevProfiler] saved " + capture.id + " rows=" + rows.size() + " dropped=" + capture.dropped);
        } catch (Exception e) {
            capture.error = "export-failed: " + brief(e);
            capture.state = "error";
            System.err.println("[MinidoracatDevProfiler] " + capture.error);
        } finally {
            capture.recording.close();
        }
    }

    private static List<Row> rowsLocked(Capture capture) {
        List<Row> rows = new ArrayList<>();
        for (Source source : capture.sources) {
            if (source.calls > 0) rows.add(new Row(source));
        }
        rows.sort(Comparator.comparingLong((Row row) -> row.inclusiveNs).reversed());
        return List.copyOf(rows);
    }

    private static Map<String, Object> head(Capture capture) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiVersion", API_VERSION);
        result.put("observed", hookObserved);
        result.put("captureId", capture == null ? "" : capture.id);
        result.put("state", capture == null ? "idle" : capture.state);
        return result;
    }

    private static Map<String, Object> failure(String code, String error) {
        Map<String, Object> result = head(active == null ? last : active);
        result.put("ok", false);
        result.put("code", code);
        result.put("error", error);
        return result;
    }

    private static boolean contains(String value, String needle) {
        for (int i = 0; i <= value.length() - needle.length(); i++) {
            if (value.regionMatches(true, i, needle, 0, needle.length())) return true;
        }
        return false;
    }

    private static String clean(String value, int limit) {
        String result = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return result.length() <= limit ? result : result.substring(0, limit);
    }
    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    private static String milliseconds(long value) { return BigDecimal.valueOf(value, 6).toPlainString(); }
    private static String brief(Exception error) { return clean(error.getClass().getSimpleName() + ": " + error.getMessage(), 240); }

    static final class Frame {
        final ThreadState owner;
        final int slot;
        Capture capture;
        Source source;
        long startNs, childNs, token;
        boolean named, live;
        Frame(ThreadState owner, int slot) { this.owner = owner; this.slot = slot; }
        void clear() { capture = null; source = null; named = false; live = false; token = 0; }
    }

    private static final class ThreadState {
        final long threadId = Thread.currentThread().threadId();
        final Frame[] frames = new Frame[MAX_DEPTH];
        long registeredCapture;
        int depth;
        ThreadState() { for (int i = 0; i < frames.length; i++) frames[i] = new Frame(this, i); }
    }

    private static final class Source {
        final String mod, file, name;
        final int line;
        long calls, inclusiveNs, exclusiveNs, maxNs, thrown;
        Source(String mod, String file, int line, String name) { this.mod = mod; this.file = file; this.line = line; this.name = name; }
    }

    private static final class Row {
        final String mod, file, name;
        final int line;
        final long calls, inclusiveNs, exclusiveNs, maxNs, thrown;
        Row(Source source) {
            mod = source.mod; file = source.file; line = source.line; name = source.name;
            calls = source.calls; inclusiveNs = source.inclusiveNs; exclusiveNs = source.exclusiveNs;
            maxNs = source.maxNs; thrown = source.thrown;
        }
        Map<String, Object> values() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mod", mod); row.put("file", file); row.put("line", line); row.put("name", name);
            row.put("calls", calls); row.put("inclusiveMs", inclusiveNs / 1_000_000d);
            row.put("exclusiveMs", exclusiveNs / 1_000_000d); row.put("maxMs", maxNs / 1_000_000d); row.put("thrown", thrown);
            return row;
        }
    }

    private static final class Capture {
        final Object lock = new Object();
        final long sequence, startNs, deadlineNs, tokenFloor;
        final String id, filter, startUtc, side, pzVersion;
        final double seconds;
        final Path output;
        final Recording recording;
        final IdentityHashMap<Prototype, Source> byPrototype = new IdentityHashMap<>();
        final IdentityHashMap<Prototype, Boolean> skipped = new IdentityHashMap<>();
        final Map<String, Source> named = new HashMap<>();
        final List<Source> sources = new ArrayList<>();
        final Set<Long> threadIds = new HashSet<>();
        volatile String state = "recording", error;
        volatile boolean closed;
        volatile long lastToken;
        long endNs, dropped, unfinished, unattributed, internal, openSelected;
        String endUtc, stopReason;
        List<Row> finalRows;
        ScheduledFuture<?> expiry;
        Capture(long sequence, String id, String filter, double seconds, long start, Path output,
                String side, String pzVersion, Recording recording) {
            this.sequence = sequence; this.id = id; this.filter = filter; this.seconds = seconds;
            this.startNs = start; this.deadlineNs = start + (long) (seconds * 1_000_000_000d);
            this.output = output; this.side = side; this.pzVersion = pzVersion; this.recording = recording;
            this.startUtc = Instant.now().toString(); this.tokenFloor = TOKENS.get();
        }
    }

    /** 只在匯出時每個來源一顆事件；原始 ns 不經 UI 的 ms 反轉換。 */
    @jdk.jfr.Name("Minidoracat.LuaSourceAggregate")
    @jdk.jfr.Label("Minidoracat Lua Source Aggregate")
    @jdk.jfr.Category({"Minidoracat", "Dev Profiler"})
    @jdk.jfr.StackTrace(false)
    public static final class SourceEvent extends Event {
        public String captureId, mod, file, name;
        public int line;
        public long calls, thrown;
        @jdk.jfr.Timespan(jdk.jfr.Timespan.NANOSECONDS) public long inclusiveNs;
        @jdk.jfr.Timespan(jdk.jfr.Timespan.NANOSECONDS) public long exclusiveNs;
        @jdk.jfr.Timespan(jdk.jfr.Timespan.NANOSECONDS) public long maxNs;
    }
}
