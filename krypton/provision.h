// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#ifndef PRIVACY_NET_KRYPTON_PROVISION_H_
#define PRIVACY_NET_KRYPTON_PROVISION_H_

#include <memory>
#include <string>

#include "privacy/net/common/proto/beryllium.proto.h"
#include "privacy/net/krypton/add_egress_response.h"
#include "privacy/net/krypton/auth.h"
#include "privacy/net/krypton/crypto/session_crypto.h"
#include "privacy/net/krypton/egress_manager.h"
#include "privacy/net/krypton/http_fetcher.h"
#include "privacy/net/krypton/pal/http_fetcher_interface.h"
#include "privacy/net/krypton/proto/debug_info.proto.h"
#include "privacy/net/krypton/proto/krypton_config.proto.h"
#include "privacy/net/krypton/proto/krypton_telemetry.proto.h"
#include "privacy/net/krypton/proto/network_info.proto.h"
#include "privacy/net/krypton/utils/looper.h"
#include "third_party/absl/base/thread_annotations.h"
#include "third_party/absl/status/status.h"
#include "third_party/absl/status/statusor.h"
#include "third_party/absl/synchronization/mutex.h"

namespace privacy {
namespace krypton {

// Handles provisioning an egress through Auth and EgressManager.
// These are the parts of Session that are not related to the datapath.
class Provision : public Auth::NotificationInterface,
                  public EgressManager::NotificationInterface {
 public:
  // Notification for Session state changes.
  class NotificationInterface {
   public:
    NotificationInterface() = default;
    virtual ~NotificationInterface() = default;

    virtual void ReadyForAddEgress(bool is_rekey) = 0;
    virtual void Provisioned(const AddEgressResponse& egress_response,
                             bool is_rekey) = 0;
    virtual void ProvisioningFailure(absl::Status status, bool permanent) = 0;
  };

  Provision(const KryptonConfig& config, std::unique_ptr<Auth> auth,
            std::unique_ptr<EgressManager> egress_manager,
            HttpFetcherInterface* http_fetcher,
            NotificationInterface* notification,
            utils::LooperThread* notification_thread);

  ~Provision() override;

  // Starts provisioning.
  void Start() ABSL_LOCKS_EXCLUDED(mutex_);

  void Stop() ABSL_LOCKS_EXCLUDED(mutex_);

  void Rekey() ABSL_LOCKS_EXCLUDED(mutex_);

  void SendAddEgress(bool is_rekey, crypto::SessionCrypto* key_material)
      ABSL_LOCKS_EXCLUDED(mutex_);

  std::string GetApnType() ABSL_LOCKS_EXCLUDED(mutex_);

  // Provides an address from provisioning that corresponds to the control plane
  // server that was used.
  absl::StatusOr<std::string> GetControlPlaneAddr() ABSL_LOCKS_EXCLUDED(mutex_);

  void GetDebugInfo(KryptonDebugInfo* debug_info) ABSL_LOCKS_EXCLUDED(mutex_);

  void CollectTelemetry(KryptonTelemetry* telemetry)
      ABSL_LOCKS_EXCLUDED(mutex_);

  // Override methods from the interface.
  void AuthSuccessful(bool is_rekey) override ABSL_LOCKS_EXCLUDED(mutex_);
  void AuthFailure(const absl::Status& status) override
      ABSL_LOCKS_EXCLUDED(mutex_);

  void EgressAvailable(bool is_rekey) override ABSL_LOCKS_EXCLUDED(mutex_);
  void EgressUnavailable(const absl::Status& status) override
      ABSL_LOCKS_EXCLUDED(mutex_);

 private:
  void FailWithStatus(absl::Status status, bool permanent);

  void PpnDataplaneRequest(bool rekey, crypto::SessionCrypto* key_material)
      ABSL_EXCLUSIVE_LOCKS_REQUIRED(mutex_);

  void ParseControlPlaneSockaddr(
      const net::common::proto::PpnDataplaneResponse& ppn_dataplane)
      ABSL_EXCLUSIVE_LOCKS_REQUIRED(mutex_);

  mutable absl::Mutex mutex_;

  KryptonConfig config_;
  utils::LooperThread looper_;

  std::unique_ptr<Auth> auth_ ABSL_GUARDED_BY(mutex_);
  std::unique_ptr<EgressManager> egress_manager_ ABSL_GUARDED_BY(mutex_);

  NotificationInterface* notification_;       // Not owned.
  utils::LooperThread* notification_thread_;  // Not owned.
  HttpFetcher http_fetcher_;

  std::string control_plane_addr_ ABSL_GUARDED_BY(mutex_);
};

}  // namespace krypton
}  // namespace privacy

#endif  // PRIVACY_NET_KRYPTON_PROVISION_H_
