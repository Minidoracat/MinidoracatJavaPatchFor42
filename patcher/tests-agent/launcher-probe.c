/*
 * Build-time test: start a JVM the way ProjectZomboid64.exe does (load jvm.dll, JNI_CreateJavaVM), not through
 * java.exe. Run it with PATH reduced to the Windows folders: then the JRE's bin folder is not on the DLL search
 * path, as in the real game. No main class is called, so the game does not start.
 *
 * usage: launcher-probe <jre64> <vm option>...      exit 0 = the JVM was created
 */
#include <windows.h>
#include <stdio.h>

typedef struct { char *optionString; void *extraInfo; } Option;
typedef struct { int version; int nOptions; Option *options; unsigned char ignoreUnrecognized; } InitArgs;
typedef int(__stdcall *create_vm)(void **vm, void **env, void *args);

int main(int argc, char **argv) {
    char path[1024];
    Option options[32];
    InitArgs args;
    void *vm, *env;
    HMODULE jvm;
    create_vm create;
    int i, rc;

    if (argc < 2 || argc - 2 > 32) {
        return 2;
    }
    snprintf(path, sizeof path, "%s\\bin\\server\\jvm.dll", argv[1]);
    jvm = LoadLibraryA(path);
    create = jvm == NULL ? NULL : (create_vm) GetProcAddress(jvm, "JNI_CreateJavaVM");
    if (create == NULL) {
        fprintf(stderr, "launcher-probe: cannot load %s\n", path);
        return 2;
    }
    for (i = 2; i < argc; i++) {
        options[i - 2].optionString = argv[i];
        options[i - 2].extraInfo = NULL;
    }
    args.version = 0x00010008;
    args.nOptions = argc - 2;
    args.options = options;
    args.ignoreUnrecognized = 0;
    rc = create(&vm, &env, &args);
    fprintf(stderr, "launcher-probe: JNI_CreateJavaVM rc=%d\n", rc);
    fflush(stderr);
    ExitProcess(rc == 0 ? 0 : 1);
}
