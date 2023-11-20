/*
 * Copyright (C) 2021 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "privacy/net/krypton/desktop/windows/krypton_service/krypton_service.h"

#include <windows.h>

#include <filesystem>
#include <memory>
#include <optional>
#include <string>

#include "privacy/net/krypton/desktop/proto/ppn_telemetry.proto.h"
#include "privacy/net/krypton/desktop/windows/ipc/named_pipe_interface.h"
#include "privacy/net/krypton/desktop/windows/krypton_service/constants.h"
#include "privacy/net/krypton/desktop/windows/krypton_service/ipc_krypton_service.h"
#include "privacy/net/krypton/desktop/windows/krypton_service/ipc_oauth.h"
#include "privacy/net/krypton/desktop/windows/krypton_service/ppn_notification_receiver.h"
#include "privacy/net/krypton/desktop/windows/logging/file_logger.h"
#include "privacy/net/krypton/desktop/windows/logging/ppn_log_sink.h"
#include "privacy/net/krypton/desktop/windows/timer.h"
#include "privacy/net/krypton/desktop/windows/utils/error.h"
#include "privacy/net/krypton/desktop/windows/utils/event.h"
#include "privacy/net/krypton/desktop/windows/utils/file_utils.h"
#include "privacy/net/krypton/desktop/windows/utils/strings.h"
#include "privacy/net/krypton/proto/krypton_config.proto.h"
#include "privacy/net/krypton/utils/status.h"
#include "third_party/absl/status/status.h"
#include "third_party/absl/synchronization/mutex.h"

namespace privacy {
namespace krypton {
namespace windows {

constexpr char kDebugLogFolderName[] = "debug\\krypton_service";
constexpr char kDebugFilePrefix[] = "ppn_debug_krypton_service_";

KryptonService* KryptonService::krypton_service_ = nullptr;

KryptonService::~KryptonService() {
  if (app_to_service_pipe_ipc_handler_ != nullptr) {
    app_to_service_pipe_ipc_handler_->Stop();
  }
  if (service_to_app_pipe_ipc_handler_ != nullptr) {
    service_to_app_pipe_ipc_handler_->Stop();
  }
  ipc_looper_.Stop();
  ipc_looper_.Join();
  if (xenon_ != nullptr) {
    xenon_->Stop();
  }
  StopKrypton();
}

absl::Status KryptonService::RegisterServiceMain(
    KryptonService* krypton_service_object) {
  krypton_service_ = krypton_service_object;
  auto service_name = utils::CharToWstring(kKryptonSvcName);
  SERVICE_TABLE_ENTRY dispatchTable[] = {
      {const_cast<TCHAR*>(service_name.c_str()), &KryptonService::ServiceMain},
      {nullptr, nullptr}};
  if (StartServiceCtrlDispatcher(dispatchTable) == 0) {
    return privacy::krypton::windows::utils::GetStatusForError(
        "Krypton Service failed to connect with SCM: ", GetLastError());
  }
  return absl::OkStatus();
}

void KryptonService::ServiceMain(DWORD dwArgc, LPTSTR* lpszArgv) {
  // Register the handler function for the service
  auto service_name = utils::CharToWstring(kKryptonSvcName);
  krypton_service_->service_status_handle_ =
      RegisterServiceCtrlHandlerW(service_name.c_str(), ServiceControlHandler);
  if (krypton_service_->service_status_handle_ == nullptr) {
    LOG(ERROR) << utils::GetStatusForError(
        "Service control handler registration failed with error:",
        GetLastError());
    return;
  }
  krypton_service_->ReportServiceStatus(SERVICE_START_PENDING, NO_ERROR, 1000);

  // Initiate logger
  // Takes base file path from parameter passed to service.
  if (dwArgc > 1) {
    auto local_app_data_dir = std::filesystem::path(lpszArgv[1]);
    auto debug_log_dir = local_app_data_dir / kDebugLogFolderName;
    (void)utils::CreateDirectoryRecursively(debug_log_dir);
    krypton_service_->logger_ =
        std::make_unique<FileLogger>(debug_log_dir, kDebugFilePrefix);
    krypton_service_->log_sink_ =
        std::make_unique<PpnLogSink>(krypton_service_->logger_.get());
  }

  // Create an event. The control handler function, ServiceControlHandler,
  // signals this event when it receives the stop control code.
  absl::StatusOr<HANDLE> krypton_service_stop_event_status =
      utils::CreateManualResetEvent();
  if (!krypton_service_stop_event_status.ok()) {
    LOG(ERROR) << krypton_service_stop_event_status.status();
    krypton_service_->ReportServiceStatus(
        SERVICE_STOPPED, krypton_service_stop_event_status.status().raw_code(),
        1000);
    return;
  }
  krypton_service_->service_stop_event_ = *krypton_service_stop_event_status;
  if (krypton_service_->service_stop_event_ == nullptr) {
    LOG(ERROR) << utils::GetStatusForError(
        "Creation of Service Stop Event Failed", GetLastError());
    krypton_service_->ReportServiceStatus(SERVICE_STOPPED, GetLastError(), 0);
    return;
  }

  absl::Status init_ipc_pipe_status =
      krypton_service_->InitializeIpcPipesAndHandlers();
  if (!init_ipc_pipe_status.ok()) {
    LOG(ERROR) << init_ipc_pipe_status;
    krypton_service_->ReportServiceStatus(
        SERVICE_STOPPED, init_ipc_pipe_status.raw_code(), 1000);
    return;
  }
  LOG(INFO) << "IPC Pipes fetched successfully";

  krypton_service_->InitializeKrypton();
  LOG(INFO) << "Krypton initialised successfully";

  krypton_service_->ipc_looper_.Post([] {
    (void)krypton_service_->app_to_service_pipe_ipc_handler_->PollOnPipe();
  });

  krypton_service_->ReportServiceStatus(SERVICE_RUNNING, NO_ERROR, 1000);

  // The ServiceControlHandler signals an event "service_stop_event_" whenever a
  // stop call for this service is triggered. We wait for that signal here and
  // execute cleanup before exit.
  LOG(INFO) << "Waiting On Stop Event...";
  WaitForSingleObject(krypton_service_->service_stop_event_, INFINITE);
  krypton_service_->ReportServiceStatus(SERVICE_STOPPED, NO_ERROR, 0);
}

void KryptonService::ReportServiceStatus(DWORD current_state, DWORD exit_code,
                                         DWORD wait_hint) {
  // Fill in the SERVICE_STATUS structure.
  service_status_.dwCurrentState = current_state;
  service_status_.dwWin32ExitCode = exit_code;
  service_status_.dwWaitHint = wait_hint;

  if (current_state == SERVICE_START_PENDING)
    service_status_.dwControlsAccepted = 0;
  else
    service_status_.dwControlsAccepted = SERVICE_ACCEPT_STOP;

  if ((current_state == SERVICE_RUNNING) || (current_state == SERVICE_STOPPED))
    service_status_.dwCheckPoint = 0;
  else
    service_status_.dwCheckPoint++;

  // Report the status of the service to the SCM.
  SetServiceStatus(service_status_handle_, &service_status_);
}

void KryptonService::ServiceControlHandler(DWORD control) {
  switch (control) {
    case SERVICE_CONTROL_STOP:
      SetEvent(krypton_service_->service_stop_event_);
      krypton_service_->ReportServiceStatus(
          krypton_service_->service_status_.dwCurrentState, NO_ERROR, 0);
      return;
    case SERVICE_CONTROL_INTERROGATE:
      break;
    default:
      break;
  }
}

void KryptonService::InitializeKrypton() {
  clock_ = std::make_unique<RealClock>();
  ppn_telemetry_manager_ = std::make_unique<PpnTelemetryManager>(clock_.get());
  timer_manager_ = std::make_unique<TimerManager>(Timer::Get());
  PPN_LOG_IF_ERROR(vpn_service_.InitializeWintun());
  ppn_notification_ = std::make_unique<PpnNotificationReceiver>(
      service_to_app_pipe_ipc_handler_.get());
  oauth_ = std::make_unique<IpcOauth>(service_to_app_pipe_ipc_handler_.get());
  notification_ = std::make_unique<Notification>(ppn_notification_.get(),
                                                 &ppn_notification_looper_,
                                                 ppn_telemetry_manager_.get());
  krypton_ = std::make_unique<Krypton>(&http_fetcher_, notification_.get(),
                                       &vpn_service_, oauth_.get(),
                                       timer_manager_.get());
  xenon_ = std::make_unique<NetworkMonitor>();
  xenon_->RegisterNotificationHandler(this, &xenon_looper_);
}

void KryptonService::StopKrypton() {
  if (krypton_ == nullptr) {
    LOG(INFO) << "Cannot stop Krypton because it is null.";
    return;
  }

  absl::MutexLock lock(&mutex_);
  if (!krypton_stopped_) {
    krypton_->Stop();
    krypton_stopped_ = true;
  }
}

absl::Status KryptonService::InitializeIpcPipesAndHandlers() {
  LOG(INFO) << "Connect to pipes";
  PPN_ASSIGN_OR_RETURN(
      app_to_service_pipe_,
      named_pipe_factory_->ConnectToPipeOnServer(kIpcAppToServicePipeName));
  PPN_ASSIGN_OR_RETURN(
      service_to_app_pipe_,
      named_pipe_factory_->ConnectToPipeOnServer(kIpcServiceToAppPipeName));
  SetAppToServiceIpcHandler(app_to_service_pipe_.get());
  SetServiceToAppIpcHandler(service_to_app_pipe_.get());
  return absl::OkStatus();
}

void KryptonService::SetAppToServiceIpcHandler(NamedPipeInterface* pipe) {
  app_to_service_pipe_ipc_handler_ =
      std::make_unique<IpcKryptonService>(this, pipe, &windows_api_);
}

void KryptonService::SetServiceToAppIpcHandler(NamedPipeInterface* pipe) {
  service_to_app_pipe_ipc_handler_ =
      std::make_unique<IpcKryptonService>(this, pipe, &windows_api_);
}

void KryptonService::Start(const KryptonConfig& config) {
  (void)ppn_notification_.get();
  ppn_telemetry_manager_->NotifyStarted();
  {
    absl::MutexLock lock(&mutex_);
    krypton_->Start(config);
    krypton_stopped_ = false;
  }
  LOG(INFO) << "KryptonService: Krypton started";
  PPN_LOG_IF_ERROR(xenon_->Start());
}

void KryptonService::Stop(const absl::Status& status) {
  xenon_->Stop();
  StopKrypton();
  LOG(INFO) << "KryptonService: Krypton stopped";
  auto ppn_notification = ppn_notification_.get();
  ppn_notification_looper_.Post(
      [ppn_notification, status] { ppn_notification->PpnStopped(status); });
  ppn_telemetry_manager_->NotifyStopped();
}

absl::StatusOr<desktop::PpnTelemetry> KryptonService::CollectTelemetry() {
  return ppn_telemetry_manager_->Collect(krypton_.get());
}

absl::Status KryptonService::SetIpGeoLevel(ppn::IpGeoLevel level) {
  krypton_->SetIpGeoLevel(level);
  return absl::OkStatus();
}

void KryptonService::BestNetworkChanged(std::optional<NetworkInfo> network) {
  if (network) {
    LOG(INFO) << "PpnService(C++): Setting network";
    ppn_telemetry_manager_->NotifyNetworkAvailable();
    PPN_LOG_IF_ERROR(krypton_->SetNetwork(*network));
  } else {
    LOG(INFO) << "PpnService(C++): Setting no network";
    ppn_telemetry_manager_->NotifyNetworkUnavailable();
    PPN_LOG_IF_ERROR(krypton_->SetNoNetworkAvailable());
  }
}

}  // namespace windows
}  // namespace krypton
}  // namespace privacy
