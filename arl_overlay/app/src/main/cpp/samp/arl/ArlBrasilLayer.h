#pragma once

namespace ArlBrasilLayer {

// Installs the one native hook needed for loose-PNG TEXDB loading.
// Safe to call once after CHook::InitHookStuff()/InstallSpecialHooks().
void InstallHooks();

// Called from the game main loop. It waits until the stock streaming and
// texture systems are ready, then activates the optional ARL Brasil layer
// exactly once if its payload exists in the GTA external-files directory.
void Tick();

bool Applied();

} // namespace ArlBrasilLayer
