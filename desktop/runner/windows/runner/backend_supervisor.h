#pragma once

#include <string>

namespace omnibot {

// Spawns the bundled omnibot-backend.exe as a child process bound to a Job Object so
// it dies with the runner. Reads the first stdout line (`OMNIBOT_BACKEND_PORT=<port>`)
// and writes it to the OMNIBOT_BACKEND_PORT environment variable so Flutter (Dart) can
// pick it up.
//
// Returns true on success. On failure inspect ::GetLastError() and the log file at
// %LOCALAPPDATA%\OmnibotApp\logs\backend-stdout.log.
bool StartBackend();

void StopBackend();

}  // namespace omnibot
