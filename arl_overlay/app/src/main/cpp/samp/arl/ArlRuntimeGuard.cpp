#include "ArlRuntimeGuard.h"

#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <sys/stat.h>

extern char* g_pszStorage;
extern uintptr_t g_libGTASA;
extern uintptr_t g_libSAMP;
void FLog(const char* fmt, ...);

namespace ArlRuntimeGuard {
namespace {

const char* AbiName() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#else
    return "unknown";
#endif
}

bool FileExists(const char* suffix) {
    if (!g_pszStorage || !suffix) return false;
    char path[512]{};
    std::snprintf(path, sizeof(path), "%s%s", g_pszStorage, suffix);
    struct stat st{};
    return stat(path, &st) == 0 && st.st_size > 0;
}

void WriteSnapshot(const char* stage) {
    if (!g_pszStorage || !g_pszStorage[0]) return;

    char path[512]{};
    std::snprintf(path, sizeof(path), "%sarl_runtime_status.txt", g_pszStorage);
    FILE* f = std::fopen(path, "w");
    if (!f) return;

    const bool img = FileExists("arlbrasil/arlbrasil.img");
    const bool texdb = FileExists("texdb/arlbrasil/arlbrasil.txt");

    std::fprintf(f, "phase=9\n");
    std::fprintf(f, "stage=%s\n", stage ? stage : "unknown");
    std::fprintf(f, "abi=%s\n", AbiName());
    std::fprintf(f, "libGTASA=0x%" PRIxPTR "\n", g_libGTASA);
    std::fprintf(f, "libsamp=0x%" PRIxPTR "\n", g_libSAMP);
    std::fprintf(f, "gta_brasil_img=%s\n", img ? "present" : "missing");
    std::fprintf(f, "gta_brasil_texdb=%s\n", texdb ? "present" : "missing");
    std::fprintf(f, "build=%s %s\n", __DATE__, __TIME__);
    std::fclose(f);
}

} // namespace

void BootSnapshot() {
    FLog("[ARL Runtime] Phase 9 boot | ABI=%s | libGTASA=0x%" PRIxPTR " | libsamp=0x%" PRIxPTR,
         AbiName(), g_libGTASA, g_libSAMP);
    WriteSnapshot("native_boot");
}

void MarkGameReady() {
    FLog("[ARL Runtime] GTA/SA-MP gameplay layer initialized");
    WriteSnapshot("game_ready");
}

} // namespace ArlRuntimeGuard
