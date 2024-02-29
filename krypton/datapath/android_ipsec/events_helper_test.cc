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

#include "privacy/net/krypton/datapath/android_ipsec/events_helper.h"

#include <sys/eventfd.h>
#include <unistd.h>

#include <thread>  // NOLINT

#include "privacy/net/krypton/datapath/android_ipsec/event_fd.h"
#include "testing/base/public/gmock.h"
#include "testing/base/public/gunit.h"
#include "third_party/absl/time/clock.h"
#include "third_party/absl/time/time.h"

namespace privacy {
namespace krypton {
namespace datapath {
namespace android {
namespace {

TEST(EventsHelperTest, AddRemove) {
  EventsHelper helper;
  ASSERT_FALSE(helper.RemoveFile(2).ok());

  EventFd event_fd_helper;
  auto fd1 = event_fd_helper.fd();
  ASSERT_OK(helper.AddFile(fd1, EPOLLIN));
  ASSERT_OK(helper.RemoveFile(fd1));
  close(fd1);
}

TEST(EventsHelperTest, EpollWaitTimeout) {
  EventsHelper helper;

  epoll_event event;
  int num = 0;
  ASSERT_OK(helper.Wait(&event, 1 /* max events */, 0 /* timeout_ms */, &num));
  ASSERT_EQ(0, num);
  ASSERT_OK(helper.Wait(&event, 1 /* max events */, 10 /* timeout_ms */, &num));
  ASSERT_EQ(0, num);
}

TEST(EventsHelperTest, EpollWait) {
  EventsHelper helper;
  EventFd event_fd_helper;
  auto fd1 = event_fd_helper.fd();

  std::thread waiter([&helper, fd1] {
    epoll_event event;
    int num = 0;
    ASSERT_OK(
        helper.Wait(&event, 1 /* max events */, 10000 /* 10s timeout */, &num));
    ASSERT_EQ(1, num);
    ASSERT_EQ(fd1, event.data.fd);
    ASSERT_EQ(EPOLLIN, event.events);
  });

  absl::SleepFor(absl::Seconds(1));
  ASSERT_OK(helper.AddFile(fd1, EPOLLIN));
  ASSERT_OK(event_fd_helper.Notify(1));
  waiter.join();
  close(fd1);
}

}  // namespace
}  // namespace android
}  // namespace datapath
}  // namespace krypton
}  // namespace privacy
