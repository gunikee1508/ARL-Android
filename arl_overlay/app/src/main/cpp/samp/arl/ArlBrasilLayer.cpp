#include "ArlBrasilLayer.h"

#include <atomic>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <sys/stat.h>

#include "main.h"
#include "game/Streaming.h"
#include "game/StreamingInfo.h"
#include "game/Models/ModelInfo.h"
#include "game/Core/KeyGen.h"
#include "game/Textures/TextureDatabaseRuntime.h"
#include "vendor/armhook/patch.h"

namespace ArlBrasilLayer {
namespace {

constexpr char kDbName[] = "arlbrasil";
// Keep one backslash before the database/archive basename. GTA SA derives the
// texture-db key from the IMG path; "arlbrasil\\arlbrasil.img" maps the DFFs
// to TextureDatabaseRuntime("arlbrasil") without replacing GTA3.IMG itself.
constexpr char kImgRelative[] = "arlbrasil\\arlbrasil.img";

struct ImgDirEntry {
    std::uint32_t posn;
    std::uint16_t sizeSectors;
    std::uint16_t sizeArchive;
    char name[24];
};
static_assert(sizeof(ImgDirEntry) == 32, "Unexpected GTA IMG directory entry size");

using SortEntriesFn = void (*)(TextureDatabaseRuntime*, bool);
SortEntriesFn g_origSortEntries = nullptr;
std::atomic<bool> g_loadingArlDb{false};
std::atomic<bool> g_applied{false};
std::atomic<bool> g_failed{false};
bool g_hookInstalled = false;

void WriteStatus(const char* text) {
    if (!g_pszStorage || !text) return;
    char path[512]{};
    std::snprintf(path, sizeof(path), "%sarlbrasil/status.txt", g_pszStorage);
    if (FILE* f = std::fopen(path, "w")) {
        std::fputs(text, f);
        std::fclose(f);
    }
}

bool FileExists(const char* suffix) {
    if (!g_pszStorage || !suffix) return false;
    char path[512]{};
    std::snprintf(path, sizeof(path), "%s%s", g_pszStorage, suffix);
    struct stat st{};
    return stat(path, &st) == 0 && st.st_size > 0;
}

bool PayloadPresent() {
    return FileExists("arlbrasil/arlbrasil.img") &&
           FileExists("texdb/arlbrasil/arlbrasil.txt");
}

void SortEntriesHook(TextureDatabaseRuntime* db, bool skipThumbs) {
    if (g_loadingArlDb.load(std::memory_order_relaxed)) {
        // Our DF_UNC database intentionally has no .tmb files. The stock
        // SortEntries path expects thumbnail arrays; skip those passes only
        // while ARL's loose-PNG DB is being loaded.
        skipThumbs = true;
    }
    if (g_origSortEntries) g_origSortEntries(db, skipThumbs);
}

bool EngineReady() {
    if (!g_pszStorage || !g_pszStorage[0]) return false;
    if (!g_hookInstalled) return false;
    if (!CStreaming::ms_files[0].m_szName[0]) return false;
    return TextureDatabaseRuntime::GetDatabase("gta3") != nullptr;
}

int FindModelIdByName(const char* name) {
    if (!name || !name[0]) return -1;
    const std::uint32_t key = CKeyGen::GetUppercaseKey(name);
    for (int i = 0; i < CModelInfo::NUM_MODEL_INFOS; ++i) {
        CBaseModelInfo* info = CModelInfo::GetModelInfo(i);
        if (info && info->m_nKey == key) return i;
    }
    return -1;
}

bool HasDffExtension(const char* name) {
    if (!name) return false;
    const char* dot = std::strrchr(name, '.');
    if (!dot) return false;
    return std::tolower(static_cast<unsigned char>(dot[1])) == 'd' &&
           std::tolower(static_cast<unsigned char>(dot[2])) == 'f' &&
           std::tolower(static_cast<unsigned char>(dot[3])) == 'f' &&
           dot[4] == '\0';
}

void Apply() {
    char imgAbsolute[512]{};
    std::snprintf(imgAbsolute, sizeof(imgAbsolute),
                  "%sarlbrasil/arlbrasil.img", g_pszStorage);

    g_loadingArlDb.store(true, std::memory_order_relaxed);
    TextureDatabaseRuntime* db = TextureDatabaseRuntime::Load(kDbName, true, DF_UNC);
    g_loadingArlDb.store(false, std::memory_order_relaxed);
    if (!db) {
        FLog("[ARL Brasil] TEXDB load failed");
        WriteStatus("failed: texdb load\n");
        g_failed.store(true, std::memory_order_release);
        return;
    }

    const int imgIdx = CStreaming::AddImageToList(kImgRelative, true);
    if (imgIdx < 0 || imgIdx >= TOTAL_IMG_ARCHIVES ||
        CStreaming::ms_files[imgIdx].m_StreamHandle <= 0) {
        FLog("[ARL Brasil] IMG registration failed idx=%d handle=%d",
             imgIdx,
             (imgIdx >= 0 && imgIdx < TOTAL_IMG_ARCHIVES)
                 ? CStreaming::ms_files[imgIdx].m_StreamHandle : -999);
        WriteStatus("failed: img registration\n");
        g_failed.store(true, std::memory_order_release);
        return;
    }

    FILE* img = std::fopen(imgAbsolute, "rb");
    if (!img) {
        FLog("[ARL Brasil] cannot open %s", imgAbsolute);
        WriteStatus("failed: img unreadable\n");
        g_failed.store(true, std::memory_order_release);
        return;
    }

    char magic[4]{};
    std::uint32_t count = 0;
    if (std::fread(magic, 1, 4, img) != 4 ||
        std::memcmp(magic, "VER2", 4) != 0 ||
        std::fread(&count, sizeof(count), 1, img) != 1 ||
        count == 0 || count > 20000) {
        std::fclose(img);
        FLog("[ARL Brasil] invalid VER2 image");
        WriteStatus("failed: invalid VER2 image\n");
        g_failed.store(true, std::memory_order_release);
        return;
    }

    int repointed = 0;
    int unknown = 0;
    int oversized = 0;
    int nonDff = 0;
    int refreshed = 0;
    bool channelsFlushed = false;

    for (std::uint32_t i = 0; i < count; ++i) {
        ImgDirEntry entry{};
        if (std::fread(&entry, sizeof(entry), 1, img) != 1) break;
        entry.name[23] = '\0';

        if (!HasDffExtension(entry.name)) {
            ++nonDff;
            continue;
        }

        char base[24]{};
        std::snprintf(base, sizeof(base), "%s", entry.name);
        if (char* dot = std::strrchr(base, '.')) *dot = '\0';

        const int modelId = FindModelIdByName(base);
        if (modelId < 0 || modelId >= RESOURCE_ID_TXD) {
            ++unknown;
            continue;
        }

        // The game can stream one resource across both halves of its streaming
        // buffer. Do not repoint an entry that cannot fit that engine limit.
        if (CStreaming::ms_streamingBufferSize > 0 &&
            entry.sizeSectors > 2 * CStreaming::ms_streamingBufferSize) {
            ++oversized;
            continue;
        }

        CStreamingInfo& stream = CStreaming::GetInfo(modelId);
        if (stream.m_nLoadState != LOADSTATE_NOT_LOADED) {
            if (!channelsFlushed) {
                CStreaming::FlushChannels();
                channelsFlushed = true;
            }
            CStreaming::RemoveModel(modelId);
            ++refreshed;
        }

        stream.m_nImgId = static_cast<std::uint8_t>(imgIdx);
        stream.m_nCdPosn = entry.posn;
        stream.m_nCdSize = entry.sizeSectors;
        stream.m_nNextIndexOnCd = -1;
        ++repointed;
    }
    std::fclose(img);

    char status[384]{};
    std::snprintf(status, sizeof(status),
                  "applied img=%d entries=%u models=%d refreshed=%d unknown=%d "
                  "oversized=%d nonDff=%d\n",
                  imgIdx, count, repointed, refreshed, unknown, oversized, nonDff);
    WriteStatus(status);
    FLog("[ARL Brasil] %s", status);
    g_applied.store(true, std::memory_order_release);
}

} // namespace

void InstallHooks() {
    if (g_hookInstalled) return;
    CHook::InlineHook("_ZN22TextureDatabaseRuntime11SortEntriesEb",
                      &SortEntriesHook, &g_origSortEntries);
    g_hookInstalled = g_origSortEntries != nullptr;
    FLog("[ARL Brasil] loose TEXDB hook %s",
         g_hookInstalled ? "installed" : "failed");
}

void Tick() {
    if (g_applied.load(std::memory_order_acquire) ||
        g_failed.load(std::memory_order_acquire)) return;
    if (!EngineReady() || !PayloadPresent()) return;
    Apply();
}

bool Applied() {
    return g_applied.load(std::memory_order_acquire);
}

} // namespace ArlBrasilLayer
