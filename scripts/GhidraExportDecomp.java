// Ghidra headless postScript: export every decompiled function to one grep-able C file.
// Java GhidraScript (Ghidra 12.x dropped Jython; .py needs PyGhidra, .java is native).
// Invoked by scripts/native_snapshot.py `decompile`:
//   analyzeHeadless <proj> snap -import lib.so -postScript GhidraExportDecomp.java <out.c>
// @category MDC

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class GhidraExportDecomp extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: GhidraExportDecomp.java <output.c>");
        }
        DecompInterface di = new DecompInterface();
        di.setOptions(new DecompileOptions());
        di.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();
        int total = fm.getFunctionCount();
        int done = 0;
        int failed = 0;
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(args[0]), StandardCharsets.UTF_8), 1 << 20)) {
            out.write("// Decompiled by Ghidra (headless) from " + currentProgram.getName() + "\n");
            out.write("// image base " + currentProgram.getImageBase() + ", " + total + " functions\n\n");
            for (Function f : fm.getFunctions(true)) {
                if (monitor.isCancelled()) {
                    break;
                }
                done++;
                if (f.isThunk() || f.isExternal()) {
                    continue;
                }
                if (done % 500 == 0) {
                    println("decomp " + done + "/" + total + " ...");
                }
                DecompileResults res = di.decompileFunction(f, 60, monitor);
                out.write("// ============ " + f.getName(true) + " @ " + f.getEntryPoint() + " ============\n");
                if (res != null && res.decompileCompleted()) {
                    out.write(res.getDecompiledFunction().getC());
                    out.write("\n");
                } else {
                    failed++;
                    String msg = res != null ? res.getErrorMessage().strip() : "null result";
                    out.write("// DECOMPILE FAILED: " + msg + "\n\n");
                }
            }
        } finally {
            di.dispose();
        }
        println("export done: " + done + " functions, " + failed + " failed -> " + args[0]);
    }
}
