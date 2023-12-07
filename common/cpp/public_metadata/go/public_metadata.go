// Package binarymetadata contains definitions for public metadata structs.
package binarymetadata

import (
	"fmt"
	"time"

	"google3/privacy/net/boq/common/tokens/tokentypes"
	"google3/third_party/golang/protobuf/v2/proto/proto"
	"google3/util/task/go/status"
	stpb "google3/util/task/status_go_proto"

	tpb "google3/google/protobuf/timestamp_go_proto"
	wrap "google3/privacy/net/common/cpp/public_metadata/go/wrap_public_metadata"
	plpb "google3/privacy/net/common/proto/proxy_layer_go_proto"
	pmpb "google3/privacy/net/common/proto/public_metadata_go_proto"
)

// BinaryStruct is a wrapper type for a C++ BinaryPublicMetadata struct.
type BinaryStruct struct {
	metadata wrap.BinaryPublicMetadata
}

// GetExpiration gets expiration timestamp
func (bs *BinaryStruct) GetExpiration() *tpb.Timestamp {
	epoch := bs.metadata.GetExpiration_epoch_seconds()
	if epoch == nil || !epoch.HasValue() {
		return nil
	}
	s := int64(epoch.Value())
	return &tpb.Timestamp{Seconds: s}
}

// GetServiceType gets the service type
func (bs *BinaryStruct) GetServiceType() string {
	service := bs.metadata.GetService_type()
	if service == nil || !service.HasValue() {
		return ""
	}
	return service.Value()
}

// GetExitLocation converts the country, region, city into a Location struct
func (bs *BinaryStruct) GetExitLocation() *pmpb.PublicMetadata_Location {
	return nil
}

// GetDebugMode gets the debug mode
func (bs *BinaryStruct) GetDebugMode() pmpb.PublicMetadata_DebugMode {
	value := int32(bs.metadata.GetDebug_mode())
	if _, ok := pmpb.PublicMetadata_DebugMode_name[value]; !ok {
		return pmpb.PublicMetadata_UNSPECIFIED_DEBUG_MODE
	}
	return pmpb.PublicMetadata_DebugMode(value)
}

// GetProxyLayer gets the proxy layer
func (bs *BinaryStruct) GetProxyLayer() plpb.ProxyLayer {
	value := bs.metadata.GetProxy_layer()
	// TODO: b/306703210 - Shift the proxy values up to match the proto OR update binary struct to be
	// an optional and then remove this kludge.
	if bs.metadata.GetVersion() < 2 {
		return plpb.ProxyLayer_PROXY_LAYER_UNSPECIFIED
	}
	if value == 0 {
		return plpb.ProxyLayer_PROXY_A
	}
	if value == 1 {
		return plpb.ProxyLayer_PROXY_B
	}
	return plpb.ProxyLayer_PROXY_LAYER_UNSPECIFIED
}

// GetGeoHint gets the GeoHint (country, region, city) tuple.
func (bs *BinaryStruct) GetGeoHint() *tokentypes.GeoHint {
	country := bs.metadata.GetCountry()
	if country == nil || !country.HasValue() {
		return &tokentypes.GeoHint{}
	}
	region := bs.metadata.GetRegion()
	if region == nil || !region.HasValue() {
		return &tokentypes.GeoHint{
			Country: country.Value(),
		}
	}
	city := bs.metadata.GetCity()
	if city == nil || !city.HasValue() {
		return &tokentypes.GeoHint{
			Country: country.Value(),
			Region:  region.Value(),
		}
	}
	return &tokentypes.GeoHint{
		Country: country.Value(),
		Region:  region.Value(),
		City:    city.Value(),
	}
}

// String produces a stringified version of the extensions for debugging purposes.
func (bs *BinaryStruct) String() string {
	return fmt.Sprintf("{Version: %d\n ServiceType: %s\n Expiration: %s\n DebugMode: %s\n ProxyLayer: %s\n GeoHint (country): %s\n GeoHint (region): %s\n GeoHint (city): %s}",
		bs.metadata.GetVersion(), bs.GetServiceType(), bs.GetExpiration().String(), bs.GetDebugMode().String(), bs.GetProxyLayer().String(), bs.GetGeoHint().Country, bs.GetGeoHint().Region, bs.GetGeoHint().City)
}

// NewBinaryFields contains all the data for creating a binary representation for public metadata.
type NewBinaryFields struct {
	Version     int32
	ServiceType string
	Expiration  *tpb.Timestamp
	DebugMode   pmpb.PublicMetadata_DebugMode
	Country     string
	Region      string
	City        string
	ProxyLayer  plpb.ProxyLayer
}

// New returns a new BinaryStruct.
func New(fields *NewBinaryFields) *BinaryStruct {
	metadata := wrap.NewBinaryPublicMetadata()
	metadata.SetVersion(uint(fields.Version))
	metadata.SetCountry(wrap.NewStringOptional(fields.Country))
	metadata.SetRegion(wrap.NewStringOptional(fields.Region))
	metadata.SetCity(wrap.NewStringOptional(fields.City))
	metadata.SetService_type(wrap.NewStringOptional(fields.ServiceType))
	metadata.SetExpiration_epoch_seconds(wrap.NewUint64Optional(uint64(fields.Expiration.GetSeconds())))
	metadata.SetDebug_mode(uint(fields.DebugMode.Number()))
	if fields.ProxyLayer == plpb.ProxyLayer_PROXY_A {
		metadata.SetProxy_layer(0)
	} else if fields.ProxyLayer == plpb.ProxyLayer_PROXY_B {
		metadata.SetProxy_layer(1)
	}
	return &BinaryStruct{metadata}
}

// Free frees the memory of the wrapped C++ BinaryPublicMetadata struct.
func (bs *BinaryStruct) Free() {
	wrap.DeleteBinaryPublicMetadata(bs.metadata)
	bs.metadata = nil
}

func unmarshalStatusToErr(serializedProto []byte) error {
	// Taken from google3/privacy/net/boq/common/tokens/token_types.go.
	var sp stpb.StatusProto
	if err := proto.Unmarshal(serializedProto, &sp); err != nil {
		return fmt.Errorf("proto.Unmarshal(%v): %w", serializedProto, err)
	}
	return status.FromProto(&sp).Err()
}

// Serialize the binary public metadata to bytes in a string. When this call returns, the caller
// should ensure to call bs.Free()
func Serialize(bs *BinaryStruct) ([]byte, error) {
	st := wrap.SerializeExtensionsWrapped(bs.metadata)
	defer wrap.DeleteStatusOrExtensionsString(st)
	if err := unmarshalStatusToErr(st.GetStatus()); err != nil {
		return nil, err
	}
	return []byte(st.GetExtensions_str()), nil
}

// Deserialize bytes to binary public metadata.
func Deserialize(in []byte) (*BinaryStruct, error) {
	inStr := string(in)
	st := wrap.DeserializeExtensionsWrapped(inStr)
	defer wrap.DeleteStatusOrExtensions(st)
	if err := unmarshalStatusToErr(st.GetStatus()); err != nil {
		return nil, err
	}
	// st.GetExtensions is allocated and should be deleted within this func, so we make a new copy below.
	bs := &BinaryStruct{}
	bs.metadata = wrap.NewBinaryPublicMetadata()
	bs.metadata.SetVersion(st.GetExtensions().GetVersion())
	bs.metadata.SetVersion(st.GetExtensions().GetVersion())
	bs.metadata.SetService_type(st.GetExtensions().GetService_type())
	bs.metadata.SetCountry(st.GetExtensions().GetCountry())
	bs.metadata.SetRegion(st.GetExtensions().GetRegion())
	bs.metadata.SetCity(st.GetExtensions().GetCity())
	bs.metadata.SetDebug_mode(st.GetExtensions().GetDebug_mode())
	if st.GetExtensions().GetExpiration_epoch_seconds().HasValue() {
		bs.metadata.SetExpiration_epoch_seconds(wrap.NewUint64Optional(uint64(st.GetExtensions().GetExpiration_epoch_seconds().Value())))
	}
	bs.metadata.SetProxy_layer(st.GetExtensions().GetProxy_layer())
	return bs, nil
}

// ValidateMetadataCardinality checks that the input extensions meet client validation rules around
// cardinality.
func ValidateMetadataCardinality(in []byte, t time.Time) error {
	inStr := string(in)
	return unmarshalStatusToErr(wrap.ValidateBinaryPublicMetadataCardinality(inStr, t))
}
