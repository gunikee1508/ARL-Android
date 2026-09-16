#pragma once

namespace ArlRuntimeGuard {

// Records a safe, offset-free snapshot after libGTASA/libsamp are discovered.
void BootSnapshot();

// Records that the GTA/SA-MP gameplay layer reached the initialized state.
void MarkGameReady();

} // namespace ArlRuntimeGuard
