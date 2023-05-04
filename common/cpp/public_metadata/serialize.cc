// Copyright 2023 Google LLC
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

#include "privacy/net/common/cpp/public_metadata/serialize.h"

#include <string>

#include "third_party/absl/log/check.h"
#include "third_party/absl/strings/string_view.h"

#ifndef htobe64
#ifdef _WIN32
#define INCL_EXTRA_HTON_FUNCTIONS
#include <winsock2.h>
#define htobe64(x) htonll((x))
#elif defined(__APPLE__)
#include <libkern/OSByteOrder.h>
#define htobe64(x) OSSwapHostToBigInt64((x))
#else
#include <endian.h>
#endif
#endif

namespace privacy::ppn {

// Converts an 8-byte uint64_t to a string such that the string keys sort in
// the same order as the original uint64_t value.
void BytesFromUint64(uint64_t fp, std::string* key) {
  uint64_t norder = htobe64(fp);
  key->resize(sizeof(norder));
  memcpy(key->data(), &norder, sizeof(norder));
}

// Convenient form of KeyFromUint64.
std::string Uint64ToBytes(uint64_t fp) {
  std::string key;
  BytesFromUint64(fp, &key);
  return key;
}

// Converts an 8-byte string key (typically generated by Uint64ToKey or
// KeyFromUint64) into a uint64_t value
uint64_t BytesToUint64(absl::string_view key) {
  uint64_t value;
  DCHECK_EQ(key.size(), sizeof(value));
  memcpy(&value, key.data(), sizeof(value));
  return htobe64(value);
}

}  // namespace privacy::ppn