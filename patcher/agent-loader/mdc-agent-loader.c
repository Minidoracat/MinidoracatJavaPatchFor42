/*
 * Windows client only. Loaded with -agentpath; it loads the JRE's own Java agent support (instrument.dll) and
 * passes the call through unchanged, so "-agentpath:<this dll>=<agent jar>=<options>" does what
 * "-javaagent:<agent jar>=<options>" would do. One difference: for -javaagent the JVM also adds
 * --add-modules java.instrument. A game started from the class path has that module as a default root module,
 * so nothing is missing unless someone limits the modules.
 *
 * Why: the game launcher (ProjectZomboid64.exe) loads jvm.dll directly. For -javaagent the JVM loads
 * jre64\bin\instrument.dll, which imports java.dll and jli.dll from the same folder. That folder is not on the
 * launcher's DLL search path (it is only when java.exe starts the JVM), so the JVM stops with "Could not find agent
 * library instrument on the library path, with error: Can't find dependent libraries" and the game does not start.
 * Loading the two DLLs by full path first makes the imports resolve to the same modules the JVM uses later.
 *
 * A failure before instrument.dll is called (also a launch option without an agent jar) only prints a line to
 * standard output, where the agent prints its own messages, and returns 0: the game starts unpatched.
 * A result from instrument.dll itself is returned as is.
 *
 * Built without the C runtime (kernel32 only), so this DLL has no dependent libraries of its own.
 */
#include <windows.h>

/* path buffers, in characters; two of them on the stack must stay under one page (no stack probe without the C runtime) */
#define CAP 512

typedef int(__stdcall *agent_on_load)(void *vm, char *options, void *reserved);

/* no C runtime: this is the DLL entry point */
BOOL WINAPI DllMainCRTStartup(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    (void) instance;
    (void) reason;
    (void) reserved;
    return TRUE;
}

static int disarmed(const char *why) {
    HANDLE err = GetStdHandle(STD_OUTPUT_HANDLE);
    const char *head = "[mdc-agent] DISARMED: agent loader: ";
    const char *tail = " -- running VANILLA Java\n";
    DWORD n;
    if (err != NULL && err != INVALID_HANDLE_VALUE) {
        WriteFile(err, head, lstrlenA(head), &n, NULL);
        WriteFile(err, why, lstrlenA(why), &n, NULL);
        WriteFile(err, tail, lstrlenA(tail), &n, NULL);
    }
    return 0;
}

/* dir ends with a backslash; returns NULL when the path does not fit or the DLL does not load */
static HMODULE load(const wchar_t *dir, const wchar_t *name) {
    wchar_t path[CAP];
    if (lstrlenW(dir) + lstrlenW(name) >= CAP) {
        return NULL;
    }
    lstrcpyW(path, dir);
    lstrcatW(path, name);
    return LoadLibraryW(path);
}

__declspec(dllexport) int Agent_OnLoad(void *vm, char *options, void *reserved) {
    wchar_t dir[CAP];
    HMODULE jvm = GetModuleHandleW(L"jvm.dll");
    DWORD len = jvm == NULL ? 0 : GetModuleFileNameW(jvm, dir, CAP);
    int cut = 0;
    HMODULE instrument;
    agent_on_load on_load;

    /* -agentpath:<dll> without "=<agent jar>" gives NULL; instrument.dll would read it without a check */
    if (options == NULL || options[0] == 0) {
        return disarmed("the launch option has no agent jar after '='");
    }
    if (len == 0 || len >= CAP) {
        return disarmed("cannot locate jvm.dll");
    }
    /* <jre>\bin\server\jvm.dll -> <jre>\bin\ */
    while (len > 0 && cut < 2) {
        len--;
        if (dir[len] == L'\\' || dir[len] == L'/') {
            cut++;
        }
    }
    if (cut < 2) {
        return disarmed("unexpected jvm.dll location");
    }
    dir[len + 1] = 0;

    if (load(dir, L"java.dll") == NULL || load(dir, L"jli.dll") == NULL) {
        return disarmed("cannot load java.dll and jli.dll from the game's JRE");
    }
    instrument = load(dir, L"instrument.dll");
    if (instrument == NULL) {
        return disarmed("cannot load instrument.dll from the game's JRE");
    }
    on_load = (agent_on_load) (void *) GetProcAddress(instrument, "Agent_OnLoad");
    if (on_load == NULL) {
        return disarmed("instrument.dll has no Agent_OnLoad");
    }
    return on_load(vm, options, reserved);
}
