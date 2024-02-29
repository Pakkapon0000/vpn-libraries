// Copyright 2021 Google LLC
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

#ifndef PRIVACY_NET_KRYPTON_DATAPATH_IPSEC_IPSEC_PACKET_POOL_H_
#define PRIVACY_NET_KRYPTON_DATAPATH_IPSEC_IPSEC_PACKET_POOL_H_

#include <memory>
#include <vector>

#include "privacy/net/krypton/datapath/ipsec/ipsec_packet.h"
#include "third_party/absl/base/thread_annotations.h"
#include "third_party/absl/synchronization/mutex.h"

namespace privacy {
namespace krypton {
namespace datapath {
namespace ipsec {

// Manages a fixed collection of IpSecPacket objects that can be re-used, so
// that we don't have to re-allocate packets constantly in the critical path.
class IpSecPacketPool {
 public:
  IpSecPacketPool();
  ~IpSecPacketPool();

  // Disallow copy and assign.
  IpSecPacketPool(const IpSecPacketPool& other) = delete;
  IpSecPacketPool(IpSecPacketPool&& other) = delete;
  IpSecPacketPool& operator=(const IpSecPacketPool& other) = delete;
  IpSecPacketPool& operator=(IpSecPacketPool&& other) = delete;

  // Takes a packet from the pool. If there are no packets available in a short
  // time, returns nullptr. Once all the copies of the shared_ptr are deleted,
  // the packet will be returned to the pool.
  std::shared_ptr<IpSecPacket> Borrow() ABSL_LOCKS_EXCLUDED(mutex_);

 private:
  // Returns the given packet to the pool.
  void Return(IpSecPacket* packet) ABSL_LOCKS_EXCLUDED(mutex_);

  absl::Mutex mutex_;
  absl::CondVar condition_ ABSL_GUARDED_BY(mutex_);
  std::vector<IpSecPacket> pool_ ABSL_GUARDED_BY(mutex_);
  std::vector<IpSecPacket*> available_ ABSL_GUARDED_BY(mutex_);
};

}  // namespace ipsec
}  // namespace datapath
}  // namespace krypton
}  // namespace privacy

#endif  // PRIVACY_NET_KRYPTON_DATAPATH_IPSEC_IPSEC_PACKET_POOL_H_
