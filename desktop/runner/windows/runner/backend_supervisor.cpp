#include "backend_supervisor.h"

#include <windows.h>
#include <shlobj.h>
#include <stdio.h>

#include <atomic>
#include <chrono>
#include <filesystem>
#include <mutex>
#include <string>
#include <thread>

namespace omnibot {

namespace {

std::mutex g_mutex;
PROCESS_INFORMATION g_process_info{};
HANDLE g_job_object = nullptr;
HANDLE g_stdout_read = nullptr;
std::atomic<bool> g_started{false};

std::wstring ExecutableDir() {
    wchar_t buffer[MAX_PATH];
    DWORD size = ::GetModuleFileNameW(nullptr, buffer, MAX_PATH);
    if (size == 0) return L"";
    std::wstring path(buffer, size);
    auto slash = path.find_last_of(L"\\/");
    if (slash != std::wstring::npos) path.resize(slash);
    return path;
}

std::wstring AppDataDir() {
    PWSTR raw = nullptr;
    std::wstring path;
    if (SUCCEEDED(::SHGetKnownFolderPath(FOLDERID_RoamingAppData, 0, nullptr, &raw))) {
        path = std::wstring(raw) + L"\\OmnibotApp";
        ::CoTaskMemFree(raw);
    }
    return path;
}

std::wstring LogDir() {
    PWSTR raw = nullptr;
    std::wstring path;
    if (SUCCEEDED(::SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &raw))) {
        path = std::wstring(raw) + L"\\OmnibotApp\\logs";
        ::CoTaskMemFree(raw);
    }
    std::error_code ec;
    std::filesystem::create_directories(path, ec);
    return path;
}

std::wstring Utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    int len = ::MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
    std::wstring out(len, L'\0');
    ::MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), out.data(), len);
    return out;
}

void RelayRemainingStdout(HANDLE read_handle, const std::wstring& log_path) {
    FILE* fp = nullptr;
    if (_wfopen_s(&fp, log_path.c_str(), L"ab") != 0 || !fp) {
        ::CloseHandle(read_handle);
        return;
    }
    char buffer[4096];
    DWORD read = 0;
    while (::ReadFile(read_handle, buffer, sizeof(buffer), &read, nullptr) && read > 0) {
        fwrite(buffer, 1, read, fp);
        fflush(fp);
    }
    fclose(fp);
    ::CloseHandle(read_handle);
}

}  // namespace

bool StartBackend() {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_started.load()) return true;

    std::wstring exe_dir = ExecutableDir();
    std::wstring backend_path = exe_dir + L"\\omnibot-backend.exe";
    if (!std::filesystem::exists(backend_path)) {
        ::OutputDebugStringW(L"[BackendSupervisor] backend binary missing");
        return false;
    }

    std::wstring data_dir = AppDataDir();
    std::filesystem::create_directories(data_dir);

    std::wstring command_line = L"\"" + backend_path + L"\" --data-dir \"" + data_dir + L"\" --bind 127.0.0.1:0";

    SECURITY_ATTRIBUTES sa{};
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;
    sa.lpSecurityDescriptor = nullptr;

    HANDLE stdout_read = nullptr;
    HANDLE stdout_write = nullptr;
    if (!::CreatePipe(&stdout_read, &stdout_write, &sa, 0)) return false;
    ::SetHandleInformation(stdout_read, HANDLE_FLAG_INHERIT, 0);

    STARTUPINFOW si{};
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdOutput = stdout_write;
    si.hStdError = stdout_write;
    si.hStdInput = ::GetStdHandle(STD_INPUT_HANDLE);

    PROCESS_INFORMATION pi{};
    BOOL ok = ::CreateProcessW(
        nullptr, command_line.data(), nullptr, nullptr, TRUE,
        CREATE_NEW_PROCESS_GROUP | CREATE_NO_WINDOW,
        nullptr, nullptr, &si, &pi);
    ::CloseHandle(stdout_write);
    if (!ok) { ::CloseHandle(stdout_read); return false; }

    // Bind to a Job so the backend exits with us.
    g_job_object = ::CreateJobObjectW(nullptr, nullptr);
    if (g_job_object) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION info{};
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        ::SetInformationJobObject(g_job_object, JobObjectExtendedLimitInformation, &info, sizeof(info));
        ::AssignProcessToJobObject(g_job_object, pi.hProcess);
    }

    // Read first stdout line for the port (timeout 8 s).
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(8);
    std::string line;
    line.reserve(64);
    bool got_port = false;
    while (std::chrono::steady_clock::now() < deadline) {
        DWORD avail = 0;
        if (!::PeekNamedPipe(stdout_read, nullptr, 0, nullptr, &avail, nullptr)) break;
        if (avail == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(25));
            continue;
        }
        char ch = 0;
        DWORD read = 0;
        if (!::ReadFile(stdout_read, &ch, 1, &read, nullptr) || read == 0) break;
        if (ch == '\n') {
            got_port = true;
            break;
        }
        if (ch != '\r') line.push_back(ch);
    }
    if (!got_port || line.find("OMNIBOT_BACKEND_PORT=") != 0) {
        ::TerminateProcess(pi.hProcess, 1);
        ::CloseHandle(pi.hProcess);
        ::CloseHandle(pi.hThread);
        ::CloseHandle(stdout_read);
        return false;
    }
    std::string port_str = line.substr(strlen("OMNIBOT_BACKEND_PORT="));
    std::wstring wport = Utf8ToWide(port_str);
    ::SetEnvironmentVariableW(L"OMNIBOT_BACKEND_PORT", wport.c_str());

    g_process_info = pi;
    g_stdout_read = stdout_read;
    g_started.store(true);

    // Spawn a thread to drain remaining stdout into the log file.
    std::wstring log_path = LogDir() + L"\\backend-stdout.log";
    std::thread([log_path, stdout_read]() {
        RelayRemainingStdout(stdout_read, log_path);
    }).detach();

    return true;
}

void StopBackend() {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_started.load()) return;
    if (g_process_info.hProcess) {
        ::GenerateConsoleCtrlEvent(CTRL_BREAK_EVENT, g_process_info.dwProcessId);
        if (::WaitForSingleObject(g_process_info.hProcess, 2000) == WAIT_TIMEOUT) {
            ::TerminateProcess(g_process_info.hProcess, 0);
        }
        ::CloseHandle(g_process_info.hProcess);
        ::CloseHandle(g_process_info.hThread);
        g_process_info = {};
    }
    if (g_job_object) {
        ::CloseHandle(g_job_object);
        g_job_object = nullptr;
    }
    g_started.store(false);
}

}  // namespace omnibot
