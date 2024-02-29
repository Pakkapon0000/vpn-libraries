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

#include "privacy/net/krypton/datapath/ipsec/ipsec_packet_pool.h"

#include <memory>

#include "privacy/net/krypton/datapath/ipsec/ipsec_packet.h"
#include "third_party/absl/log/log.h"
#include "third_party/absl/synchronization/mutex.h"
#include "third_party/absl/time/clock.h"
#include "third_party/absl/time/time.h"

namespace privacy {
namespace krypton {
namespace datapath {
namespace ipsec {

// Each packet is roughly 1.4K, so 400 is about 0.5MB. We need 1 pool for uplink
// and one pool for downlink. So, in total, the packets will use up 1MB when
// connected.
const int kPacketPoolSize = 400;

// If no packet is available, this is how long we'll wait for a packet to become
// available. If we reach this timeout, we'll fail encrypting the packet, but
// this is a UDP stream, so if we have to drop some packets, that's fine.
const absl::Duration kBorrowWaitTimeout = absl::Milliseconds(50);

IpSecPacketPool::IpSecPacketPool() : pool_(kPacketPoolSize) {
  for (auto &packet : pool_) {
    available_.push_back(&packet);
  }
}

IpSecPacketPool::~IpSecPacketPool() {
  absl::MutexLock m(&mutex_);
  while (available_.size() != pool_.size()) {
    LOG(WARNING) << "IpSecPacketPool was destroyed with outstanding loans.";
    condition_.Wait(&mutex_);
  }
  LOG(WARNING) << "IpSecPacketPool has all packets returned.";
}

std::shared_ptr<IpSecPacket> IpSecPacketPool::Borrow() {
  auto deadline = absl::Now() + kBorrowWaitTimeout;

  absl::MutexLock m(&mutex_);
  while (available_.empty()) {
    if (condition_.WaitWithDeadline(&mutex_, deadline)) {
      return std::shared_ptr<IpSecPacket>(nullptr);
    }
  }
  IpSecPacket *packet = available_.back();
  available_.pop_back();

  return std::shared_ptr<IpSecPacket>(
      packet, [this](IpSecPacket *p) { this->Return(p); });
}

void IpSecPacketPool::Return(IpSecPacket *packet) {
  absl::MutexLock m(&mutex_);
  available_.push_back(packet);
  condition_.SignalAll();
}

}  // namespace ipsec
}  // namespace datapath
}  // namespace krypton
}  // namespace privacy
