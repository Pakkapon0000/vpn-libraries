#include "privacy/net/common/cpp/public_metadata/fingerprint.h"

#include <cstdint>
#include <string>

#include "google/protobuf/timestamp.proto.h"
#include "privacy/net/common/proto/public_metadata.proto.h"
#include "third_party/absl/status/status.h"
#include "third_party/absl/strings/escaping.h"
#include "third_party/absl/strings/numbers.h"
#include "third_party/absl/strings/str_cat.h"
#include "third_party/openssl/digest.h"

namespace privacy::ppn {
namespace {

template <typename T>
std::string OmitDefault(T value) {
  return value == 0 ? "" : absl::StrCat(value);
}

}  // namespace

absl::Status FingerprintPublicMetadata(const PublicMetadata& metadata,
                                       uint64_t* fingerprint) {
  const EVP_MD* hasher = EVP_sha256();
  std::string digest;
  digest.resize(EVP_MAX_MD_SIZE);

  uint32_t digest_length = 0;
  // Concatenate fields in tag number order, omitting fields whose values match
  // the default. This enables new fields to be added without changing the
  // resulting encoding.
  const std::string input = absl::StrCat(            //
      metadata.exit_location().country(),            //
      metadata.exit_location().city_geo_id(),        //
      metadata.service_type(),                       //
      OmitDefault(metadata.expiration().seconds()),  //
      OmitDefault(metadata.expiration().nanos()));
  if (EVP_Digest(input.data(), input.length(),
                 reinterpret_cast<uint8_t*>(&digest[0]), &digest_length, hasher,
                 nullptr) != 1) {
    return absl::InternalError("EVP_Digest failed");
  }
  // Return the first uint64_t of the SHA-256 hash.
  const std::string hex_hash = absl::BytesToHexString(digest);
  if (!absl::SimpleHexAtoi(hex_hash.substr(0, 16), fingerprint)) {
    return absl::InternalError("SimpleHexAtoi failed");
  }
  return absl::OkStatus();
}

}  // namespace privacy::ppn