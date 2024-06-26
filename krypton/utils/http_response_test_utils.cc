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

#include "privacy/net/krypton/utils/http_response_test_utils.h"

#include <string>

#include "google/protobuf/timestamp.proto.h"
#include "privacy/net/common/proto/auth_and_sign.proto.h"
#include "privacy/net/common/proto/get_initial_data.proto.h"
#include "privacy/net/common/proto/public_metadata.proto.h"
#include "privacy/net/krypton/proto/http_fetcher.proto.h"
#include "third_party/absl/status/statusor.h"
#include "third_party/absl/strings/escaping.h"
#include "third_party/absl/strings/match.h"
#include "third_party/absl/strings/string_view.h"
#include "third_party/anonymous_tokens/cpp/testing/utils.h"
#include "third_party/anonymous_tokens/proto/anonymous_tokens.proto.h"
#include "third_party/openssl/base.h"
#include "third_party/protobuf/message_lite.h"

namespace privacy {
namespace krypton {
namespace utils {
namespace {

HttpResponse CreateHttpResponseWithProtoBody(
    const proto2::MessageLite& proto_body) {
  HttpResponse response = CreateHttpResponseWithStatus(200, "OK");
  *response.mutable_proto_body() = proto_body.SerializeAsString();
  return response;
}

HttpResponse CreateHttpResponseWithJsonBody(absl::string_view json_body) {
  HttpResponse response = CreateHttpResponseWithStatus(200, "OK");
  response.set_json_body(json_body);
  return response;
}

}  // namespace

HttpResponse CreateHttpResponseWithStatus(int status_code,
                                          absl::string_view status_message) {
  HttpResponse response;
  response.mutable_status()->set_code(status_code);
  response.mutable_status()->set_message(status_message);
  return response;
}

HttpResponse CreateGetInitialDataHttpResponse(
    const ::private_membership::anonymous_tokens::RSABlindSignaturePublicKey&
        public_key) {
  // Some of the values here are fake and may not be realistic. We may need more
  // realistic values later.
  ppn::GetInitialDataResponse response;
  *response.mutable_at_public_metadata_public_key() = public_key;
  ppn::PublicMetadataInfo* public_metadata_info =
      response.mutable_public_metadata_info();
  ppn::PublicMetadata* public_metadata =
      public_metadata_info->mutable_public_metadata();
  public_metadata->mutable_exit_location()->set_country("US");
  public_metadata->mutable_exit_location()->set_city_geo_id("us_ca_san_diego");
  public_metadata->set_service_type("service_type");
  public_metadata->mutable_expiration()->set_seconds(900);
  public_metadata->mutable_expiration()->set_nanos(0);
  public_metadata->set_debug_mode(ppn::PublicMetadata::UNSPECIFIED_DEBUG_MODE);
  public_metadata_info->set_validation_version(1);

  return CreateHttpResponseWithProtoBody(response);
}

HttpResponse CreateAuthHttpResponse(const HttpRequest& auth_request,
                                    RSA* rsa_key,
                                    absl::string_view control_plane_hostname) {
  ppn::AuthAndSignRequest request;
  if (!request.ParseFromString(auth_request.proto_body())) {
    return CreateHttpResponseWithStatus(403, "Failed to parse request");
  }

  // Construct AuthAndSignResponse.
  ppn::AuthAndSignResponse auth_response;
  for (const auto& request_token : request.blinded_token()) {
    std::string decoded_blinded_token;
    if (!absl::Base64Unescape(request_token, &decoded_blinded_token)) {
      return CreateHttpResponseWithStatus(403, "Failed to decode token");
    }
    absl::StatusOr<std::string> serialized_token =
        // TODO This is for RSA signatures which don't take
        // public metadata into account. Eventually this will need to be
        // updated.
        private_membership::anonymous_tokens::TestSign(decoded_blinded_token,
                                                       rsa_key);
    if (!serialized_token.ok()) {
      return CreateHttpResponseWithStatus(403, "Failed to sign token");
    }
    auth_response.add_blinded_token_signature(
        absl::Base64Escape(*serialized_token));
  }

  auth_response.set_copper_controller_hostname(control_plane_hostname);

  return utils::CreateHttpResponseWithProtoBody(auth_response);
}

HttpResponse CreateAddEgressHttpResponse(
    const HttpRequest& add_egress_request) {
  if (absl::StrContains(add_egress_request.json_body(),
                        R"("dataplane_protocol":"IKE")")) {
    return CreateAddEgressHttpResponseForIke();
  }
  return CreateAddEgressHttpResponseForNonIke();
}

HttpResponse CreateAddEgressHttpResponseForIke() {
  return CreateHttpResponseWithJsonBody(R"string({
      "ike": {
        "client_id": "Y2xpZW50X2lk",
        "server_address": "server_address",
        "shared_secret": "c2hhcmVkX3NlY3JldA=="
      }
    })string");
}

HttpResponse CreateAddEgressHttpResponseForNonIke() {
  return CreateHttpResponseWithJsonBody(R"string({
      "ppn_dataplane": {
        "user_private_ip": [{
          "ipv4_range": "10.2.2.123/32",
          "ipv6_range": "fec2:0001::3/64"
        }],
        "egress_point_sock_addr": ["64.9.240.165:2153", "[2604:ca00:f001:4::5]:2153"],
        "egress_point_public_value": "a22j+91TxHtS5qa625KCD5ybsyzPR1wkTDWHV2qSQQc=",
        "server_nonce": "Uzt2lEzyvZYzjLAP3E+dAA==",
        "uplink_spi": 123,
        "expiry": "2020-08-07T01:06:13+00:00"
      }
    })string");
}

HttpResponse CreateRekeyHttpResponse() {
  // Return a response with different uplink_spi, server_nonce, and
  // egress_point_public_value
  return CreateHttpResponseWithJsonBody(R"string({
      "ppn_dataplane": {
        "egress_point_public_value": "a22j+91TxHtS5qa625KCE5ybsyzPR1wkTDWHV2qSQQc=",
        "server_nonce": "Uzt2lEzyvBYzjLAP3E+dAA==",
        "uplink_spi": 456,
        "expiry": "2020-08-07T01:06:13+00:00"
      }
    })string");
}

}  // namespace utils
}  // namespace krypton
}  // namespace privacy
