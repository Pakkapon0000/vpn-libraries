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

package com.google.android.libraries.privacy.ppn.krypton;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.os.ConditionVariable;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.testing.WorkManagerTestInitHelper;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.libraries.privacy.ppn.IpGeoLevel;
import com.google.android.libraries.privacy.ppn.PpnStatus;
import com.google.android.libraries.privacy.ppn.PpnStatus.Code;
import com.google.android.libraries.privacy.ppn.internal.AndroidAttestationData;
import com.google.android.libraries.privacy.ppn.internal.AttestationHelper;
import com.google.android.libraries.privacy.ppn.internal.DisconnectionStatus;
import com.google.android.libraries.privacy.ppn.internal.KryptonConfig;
import com.google.android.libraries.privacy.ppn.internal.KryptonConfig.DatapathProtocol;
import com.google.android.libraries.privacy.ppn.internal.TunFdData;
import com.google.android.libraries.privacy.ppn.internal.http.BoundSocketFactoryFactory;
import com.google.android.libraries.privacy.ppn.internal.http.HttpFetcher;
import com.google.android.libraries.privacy.ppn.proto.AttestationData;
import com.google.android.libraries.privacy.ppn.proto.AuthAndSignRequest;
import com.google.protobuf.Any;
import com.google.protobuf.ExtensionRegistryLite;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

/** Unit tests for {@link Krypton}. */
@RunWith(RobolectricTestRunner.class)
public class KryptonTest {
  private static final String INVALID_URL = "http://unknown";

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Mock private KryptonListener kryptonListener;
  @Mock private BoundSocketFactoryFactory socketFactoryFactory;
  private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

  private final MockZinc mockPublicKeyServer = new MockZinc();
  private final MockZinc mockInitialDataServer = new MockZinc();
  private final MockZinc mockAuthServer = new MockZinc();
  private final MockBrass mockEgressServer = new MockBrass();

  private KryptonConfig.Builder createConfig() {
    return KryptonConfig.newBuilder()
        .setZincUrl(mockAuthServer.url())
        .setZincPublicSigningKeyUrl(mockPublicKeyServer.url())
        .setBrassUrl(mockEgressServer.url())
        .setServiceType("some_service_type")
        .setDatapathProtocol(DatapathProtocol.BRIDGE)
        .setEnableBlindSigning(true);
  }

  private static class MockOAuthTokenProvider implements OAuthTokenProvider {
    @Override
    public String getOAuthToken() {
      return "some_auth_token";
    }

    @Override
    public byte[] getAttestationData(String nonce) {
      AndroidAttestationData androidAttestationData =
          AndroidAttestationData.newBuilder().setAttestationToken("foo").build();

      AttestationData proto =
          AttestationData.newBuilder()
              .setAttestationData(
                  Any.newBuilder()
                      .setTypeUrl(AttestationHelper.ANDROID_ATTESTATION_DATA_TYPE_URL)
                      .setValue(androidAttestationData.toByteString()))
              .build();

      return proto.toByteArray();
    }

    @Override
    public void clearOAuthToken(String token) {}
  }

  private Krypton createKrypton(OAuthTokenProvider tokenProvider) {
    WorkManagerTestInitHelper.initializeTestWorkManager(
        ApplicationProvider.getApplicationContext());
    when(socketFactoryFactory.withCurrentNetwork()).thenReturn(SocketFactory.getDefault());
    when(socketFactoryFactory.withNetwork(any())).thenReturn(SocketFactory.getDefault());
    HttpFetcher httpFetcher = new HttpFetcher(socketFactoryFactory);

    return new KryptonImpl(
        ApplicationProvider.getApplicationContext(),
        httpFetcher,
        tokenProvider,
        kryptonListener,
        backgroundExecutor);
  }

  private Krypton createKrypton() {
    return createKrypton(new MockOAuthTokenProvider());
  }

  @Before
  public final void startServers() throws Exception {
    mockPublicKeyServer.start();
    mockInitialDataServer.start();
    mockAuthServer.start();
    mockEgressServer.start();
  }

  @Test
  public void start_invalidZincUrl_terminatesSession() throws Exception {
    Krypton krypton = createKrypton();
    final ConditionVariable condition = new ConditionVariable(false);

    AtomicReference<DisconnectionStatus> firstStatus = new AtomicReference<>();
    AtomicReference<DisconnectionStatus> secondStatus = new AtomicReference<>();
    doAnswer(
            invocation -> {
              if (!firstStatus.compareAndSet(null, invocation.getArgument(0))) {
                secondStatus.set(invocation.getArgument(0));
                condition.open();
              }
              return null;
            })
        .when(kryptonListener)
        .onKryptonDisconnected(any(DisconnectionStatus.class));

    try {
      krypton.start(
          createConfig()
              .setZincUrl(INVALID_URL)
              .setZincPublicSigningKeyUrl(INVALID_URL)
              .setBrassUrl(INVALID_URL)
              .build());
      assertThat(condition.block(1000)).isTrue();
      // Validate the calls to onKryptonDisconnected
      assertThat(firstStatus.get().getCode()).isEqualTo(PpnStatus.Code.INTERNAL.getCode());
      assertThat(secondStatus.get().getCode())
          .isEqualTo(PpnStatus.Code.DEADLINE_EXCEEDED.getCode());
      assertThat(secondStatus.get().getIsBlockingTraffic()).isFalse();
    } finally {
      krypton.stop();
    }
  }

  @Test
  public void start_permanentFailureFromZinc_permanentFailure() throws Exception {
    Krypton krypton = createKrypton();
    final ConditionVariable condition = new ConditionVariable(false);

    mockPublicKeyServer.enqueuePositivePublicKeyResponse();
    mockAuthServer.enqueueNegativeResponseWithCode(403, "Auth failed");

    AtomicReference<PpnStatus> status = new AtomicReference<>();
    doAnswer(
            invocation -> {
              status.set(invocation.getArgument(0));
              condition.open();
              return null;
            })
        .when(kryptonListener)
        .onKryptonPermanentFailure(any(PpnStatus.class));

    try {
      krypton.start(createConfig().setBrassUrl(INVALID_URL).build());
      assertThat(condition.block(1000)).isTrue();
      // Validate the Status
      assertThat(status.get().getCode()).isEqualTo(PpnStatus.Code.PERMISSION_DENIED);
    } finally {
      krypton.stop();
    }
  }

  @Test
  public void start_invalidBrassUrl_terminatesSession() throws Exception {
    Krypton krypton = createKrypton();
    final ConditionVariable condition = new ConditionVariable(false);

    mockPublicKeyServer.enqueuePositivePublicKeyResponse();
    mockAuthServer.enqueuePositiveJsonAuthResponse();
    mockEgressServer.enqueuePositiveResponse();

    AtomicReference<DisconnectionStatus> firstStatus = new AtomicReference<>();
    AtomicReference<DisconnectionStatus> secondStatus = new AtomicReference<>();
    doAnswer(
            invocation -> {
              if (!firstStatus.compareAndSet(null, invocation.getArgument(0))) {
                secondStatus.set(invocation.getArgument(0));
                condition.open();
              }
              return null;
            })
        .when(kryptonListener)
        .onKryptonDisconnected(any(DisconnectionStatus.class));

    try {
      krypton.start(createConfig().setBrassUrl(INVALID_URL).build());
      assertThat(condition.block(1000)).isTrue();

      // Validate the calls to onKryptonDisconnected
      assertThat(firstStatus.get().getCode()).isEqualTo(PpnStatus.Code.INTERNAL.getCode());
      assertThat(secondStatus.get().getCode())
          .isEqualTo(PpnStatus.Code.DEADLINE_EXCEEDED.getCode());
      assertThat(secondStatus.get().getIsBlockingTraffic()).isFalse();

      // Validate the PublicKeyRequest
      final RecordedRequest publicKeyRequest = mockPublicKeyServer.takeRequest();
      final String publicKeyBodyString = publicKeyRequest.getBody().readUtf8();
      final JSONObject publicKeyBody = new JSONObject(publicKeyBodyString);
      assertThat(publicKeyBody.opt("get_public_key")).isEqualTo(true);
      assertThat(publicKeyRequest.getHeader("Content-Type"))
          .isEqualTo("application/json; charset=utf-8");

      // Validate the AuthAndSignRequest
      final RecordedRequest authAndSignRequest = mockAuthServer.takeRequest();
      final String authAndSignRequestBodyString = authAndSignRequest.getBody().readUtf8();
      final JSONObject authAndSignRequestBody = new JSONObject(authAndSignRequestBodyString);
      assertThat(authAndSignRequestBody.opt("blinded_token")).isNotNull();
      assertThat(authAndSignRequestBody.opt("service_type")).isEqualTo("some_service_type");
      assertThat(authAndSignRequest.getHeader("Content-Type"))
          .isEqualTo("application/json; charset=utf-8");

    } finally {
      krypton.stop();
    }
  }

  @Test
  public void start_establishesSession() throws Exception {
    Krypton krypton = createKrypton();
    final ConditionVariable condition = new ConditionVariable(false);

    mockPublicKeyServer.enqueuePositivePublicKeyResponse();
    mockAuthServer.enqueuePositiveJsonAuthResponse();
    mockEgressServer.enqueuePositiveResponse();

    doReturn(0xbeef).when(kryptonListener).onKryptonNeedsTunFd(any(TunFdData.class));

    doAnswer(
            invocation -> {
              condition.open();
              return null;
            })
        .when(kryptonListener)
        .onKryptonControlPlaneConnected();

    try {
      krypton.start(createConfig().build());
      assertThat(condition.block(1000)).isTrue();

      // Validate the PublicKeyRequest
      final RecordedRequest publicKeyRequest = mockPublicKeyServer.takeRequest();
      final String publicKeyBodyString = publicKeyRequest.getBody().readUtf8();
      final JSONObject publicKeyBody = new JSONObject(publicKeyBodyString);
      assertThat(publicKeyBody.opt("get_public_key")).isEqualTo(true);
      assertThat(publicKeyRequest.getHeader("Content-Type"))
          .isEqualTo("application/json; charset=utf-8");

      // Validate the AuthAndSignRequest
      final RecordedRequest authAndSignRequest = mockAuthServer.takeRequest();
      final String authAndSignRequestBodyString = authAndSignRequest.getBody().readUtf8();
      final JSONObject authAndSignRequestBody = new JSONObject(authAndSignRequestBodyString);
      assertThat(authAndSignRequestBody.opt("blinded_token")).isNotNull();
      assertThat(authAndSignRequestBody.opt("service_type")).isEqualTo("some_service_type");
      assertThat(authAndSignRequest.getHeader("Content-Type"))
          .isEqualTo("application/json; charset=utf-8");

      // Validate the AddEgressRequest
      final RecordedRequest addEgressRequest = mockEgressServer.takeRequest();
      assertThat(addEgressRequest.getHeader("Content-Type"))
          .isEqualTo("application/json; charset=utf-8");
    } finally {
      krypton.stop();
    }
  }

  @Test
  public void start_clearsAuthTokenOn401() throws Exception {
    TaskCompletionSource<String> clearedToken = new TaskCompletionSource<>();

    OAuthTokenProvider tokenProvider =
        new MockOAuthTokenProvider() {
          @Override
          public void clearOAuthToken(String token) {
            clearedToken.trySetResult(token);
          }
        };

    Krypton krypton = createKrypton(tokenProvider);
    final ConditionVariable condition = new ConditionVariable(false);

    mockInitialDataServer.enqueueNegativeResponseWithCode(401, "Unauthenticated");

    doAnswer(
            invocation -> {
              condition.open();
              return null;
            })
        .when(kryptonListener)
        .onKryptonDisconnected(any(DisconnectionStatus.class));

    try {
      KryptonConfig config =
          createConfig()
              .setPublicMetadataEnabled(true)
              .setInitialDataUrl(mockInitialDataServer.url())
              .setServiceType("test_service_type")
              .build();
      krypton.start(config);
      assertThat(condition.block(1000)).isTrue();

      assertThat(clearedToken.getTask().getResult()).isEqualTo(tokenProvider.getOAuthToken());

    } finally {
      krypton.stop();
    }
  }

  @Test
  public void start_brassFailure_reconnectSuccessful() throws Exception {
    Krypton krypton = createKrypton();
    final ConditionVariable connectedCondition = new ConditionVariable(false);
    final ConditionVariable restartCondition = new ConditionVariable(false);

    mockPublicKeyServer.enqueuePositivePublicKeyResponse();
    mockAuthServer.enqueuePositiveJsonAuthResponse();
    mockEgressServer.enqueueNegativeResponseWithCode(402, "Something went wrong with the server");

    mockPublicKeyServer.enqueuePositivePublicKeyResponse();
    mockAuthServer.enqueuePositiveJsonAuthResponse();
    mockEgressServer.enqueuePositiveResponse();

    doReturn(0xbeef).when(kryptonListener).onKryptonNeedsTunFd(any(TunFdData.class));

    AtomicReference<DisconnectionStatus> firstStatus = new AtomicReference<>();
    AtomicReference<DisconnectionStatus> secondStatus = new AtomicReference<>();
    doAnswer(
            invocation -> {
              if (!firstStatus.compareAndSet(null, invocation.getArgument(0))) {
                secondStatus.set(invocation.getArgument(0));
                restartCondition.open();
              }
              return null;
            })
        .when(kryptonListener)
        .onKryptonDisconnected(any(DisconnectionStatus.class));

    doAnswer(
            invocation -> {
              connectedCondition.open();
              return null;
            })
        .when(kryptonListener)
        .onKryptonControlPlaneConnected();

    try {
      krypton.start(createConfig().build());

      assertThat(restartCondition.block(2000)).isTrue();

      // Validate the calls to onKryptonDisconnected
      assertThat(firstStatus.get().getCode()).isEqualTo(Code.FAILED_PRECONDITION.getCode());
      assertThat(secondStatus.get().getCode())
          .isEqualTo(PpnStatus.Code.DEADLINE_EXCEEDED.getCode());
      assertThat(secondStatus.get().getIsBlockingTraffic()).isFalse();

      // Let the Looper run everything till the first reconnect timer of 2 secs expires.
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));

      assertThat(connectedCondition.block(1000)).isTrue();
    } finally {
      krypton.stop();
    }
  }

  @Test
  public void start_passesSafeDisconnect() throws Exception {
    Krypton krypton = createKrypton();
    final ConditionVariable condition = new ConditionVariable(false);

    mockPublicKeyServer.enqueuePositivePublicKeyResponse();
    mockAuthServer.enqueuePositiveJsonAuthResponse();
    mockEgressServer.enqueuePositiveResponse();
    doReturn(0xbeef).when(kryptonListener).onKryptonNeedsTunFd(any(TunFdData.class));

    doAnswer(
            invocation -> {
              condition.open();
              return null;
            })
        .when(kryptonListener)
        .onKryptonControlPlaneConnected();

    try {
      krypton.start(createConfig().setSafeDisconnectEnabled(true).build());
      assertThat(condition.block(1000)).isTrue();

      // Validate the Safe Disconnect config value.
      assertThat(krypton.isSafeDisconnectEnabled()).isTrue();

      // Update Safe Disconnect while Krypton is alive.
      krypton.setSafeDisconnectEnabled(false);
      assertThat(krypton.isSafeDisconnectEnabled()).isFalse();
    } finally {
      krypton.stop();
    }
  }

  @Test
  public void start_usesIpGeoLevelFromConfig() throws Exception {
    Krypton krypton = createKrypton();
    final ConditionVariable condition = new ConditionVariable(false);

    doAnswer(
            invocation -> {
              condition.open();
              return null;
            })
        .when(kryptonListener)
        .onKryptonControlPlaneConnected();

    // Set up responses to successfully establish control plane
    mockPublicKeyServer.enqueuePositivePublicKeyResponse();
    mockAuthServer.enqueuePositiveJsonAuthResponse();
    mockEgressServer.enqueuePositiveResponse();

    try {
      // Overwrite the default value of IpGeoLevel in the config
      krypton.start(createConfig().setIpGeoLevel(IpGeoLevel.CITY).build());
      condition.block();

      // Verify that the value from the config was used by Krypton
      assertThat(krypton.getIpGeoLevel()).isEqualTo(IpGeoLevel.CITY);
    } finally {
      krypton.stop();
    }
  }

  @Test
  public void start_passesIpGeoLevel() throws Exception {
    Krypton krypton = createKrypton();
    final ConditionVariable condition = new ConditionVariable(false);

    mockPublicKeyServer.enqueuePositivePublicKeyResponse();
    mockAuthServer.enqueuePositiveJsonAuthResponse();
    mockEgressServer.enqueuePositiveResponse();

    doAnswer(
            invocation -> {
              condition.open();
              return null;
            })
        .when(kryptonListener)
        .onKryptonControlPlaneConnected();

    try {
      krypton.start(createConfig().build());
      assertThat(condition.block(1000)).isTrue();

      assertThat(krypton.getIpGeoLevel()).isEqualTo(IpGeoLevel.COUNTRY);

      condition.close();
      mockPublicKeyServer.enqueuePositivePublicKeyResponse();
      mockAuthServer.enqueuePositiveJsonAuthResponse();
      mockEgressServer.enqueuePositiveResponse();

      // Update IP Geo Level while Krypton is alive.
      krypton.setIpGeoLevel(IpGeoLevel.CITY);
      assertThat(krypton.getIpGeoLevel()).isEqualTo(IpGeoLevel.CITY);

      assertThat(condition.block(1000)).isTrue();

    } finally {
      krypton.stop();
    }
  }

  @Test
  public void start_disableNativeKeepalive() throws Exception {
    Krypton krypton = createKrypton();
    final ConditionVariable condition = new ConditionVariable(false);

    mockPublicKeyServer.enqueuePositivePublicKeyResponse();
    mockAuthServer.enqueuePositiveJsonAuthResponse();
    mockEgressServer.enqueuePositiveResponse();

    doReturn(0xbeef).when(kryptonListener).onKryptonNeedsTunFd(any(TunFdData.class));

    doAnswer(
            invocation -> {
              condition.open();
              return null;
            })
        .when(kryptonListener)
        .onKryptonControlPlaneConnected();

    try {
      krypton.start(createConfig().build());
      assertThat(condition.block(1000)).isTrue();

      krypton.disableKryptonKeepalive();
    } finally {
      krypton.stop();
    }
  }

  @Test
  public void forceTunnelUpdate_successfullyCallsNativeMethod() throws Exception {
    Krypton krypton = createKrypton();
    final ConditionVariable condition = new ConditionVariable(false);

    mockPublicKeyServer.enqueuePositivePublicKeyResponse();
    mockAuthServer.enqueuePositiveJsonAuthResponse();
    mockEgressServer.enqueuePositiveResponse();

    doReturn(0xbeef).when(kryptonListener).onKryptonNeedsTunFd(any(TunFdData.class));

    doAnswer(
            invocation -> {
              condition.open();
              return null;
            })
        .when(kryptonListener)
        .onKryptonControlPlaneConnected();

    try {
      krypton.start(createConfig().build());
      assertThat(condition.block(1000)).isTrue();

      // Verify this does not throw an exception from calling native method
      krypton.forceTunnelUpdate();
    } finally {
      krypton.stop();
    }
  }

  @Test
  public void debugInfo_isPopulated() throws Exception {
    Krypton krypton = createKrypton();
    mockPublicKeyServer.enqueuePositivePublicKeyResponse();
    mockAuthServer.enqueuePositiveJsonAuthResponse();
    mockEgressServer.enqueuePositiveResponse();
    doReturn(0xbeef).when(kryptonListener).onKryptonNeedsTunFd(any(TunFdData.class));

    final ConditionVariable connectedCondition = new ConditionVariable(false);
    doAnswer(
            invocation -> {
              connectedCondition.open();
              return null;
            })
        .when(kryptonListener)
        .onKryptonControlPlaneConnected();

    try {
      krypton.start(createConfig().build());
      assertThat(connectedCondition.block(1000)).isTrue();

      JSONObject debugInfo = krypton.getDebugJson();

      assertThat(debugInfo.opt(KryptonDebugJson.AUTH_STATE)).isEqualTo("Authenticated");
      assertThat(debugInfo.opt(KryptonDebugJson.AUTH_STATUS)).isEqualTo("OK");
      assertThat(debugInfo.opt(KryptonDebugJson.BRASS_URL)).isEqualTo(mockEgressServer.url());
      assertThat(debugInfo.optBoolean(KryptonDebugJson.CANCELLED)).isFalse();
      assertThat(debugInfo.opt(KryptonDebugJson.SUCCESSIVE_CONTROL_PLANE_FAILURES)).isEqualTo(1);
      assertThat(debugInfo.opt(KryptonDebugJson.EGRESS_STATE)).isEqualTo("kEgressSessionCreated");
      assertThat(debugInfo.opt(KryptonDebugJson.EGRESS_STATUS)).isEqualTo("OK");
      assertThat(debugInfo.opt(KryptonDebugJson.RECONNECTOR_STATE)).isEqualTo("Connected");
      assertThat(debugInfo.opt(KryptonDebugJson.SERVICE_TYPE)).isEqualTo("some_service_type");
      assertThat(debugInfo.opt(KryptonDebugJson.SESSION_RESTART_COUNTER)).isEqualTo(1);
      assertThat(debugInfo.opt(KryptonDebugJson.SESSION_STATE)).isEqualTo("kControlPlaneConnected");
      assertThat(debugInfo.opt(KryptonDebugJson.SESSION_STATUS)).isEqualTo("OK");
      assertThat(debugInfo.has(KryptonDebugJson.SESSION_ACTIVE_TUN_FD)).isFalse();
      assertThat(debugInfo.opt(KryptonDebugJson.ZINC_URL)).isEqualTo(mockAuthServer.url());
    } finally {
      krypton.stop();
    }
  }

  @Test
  public void attestationData_isPopulated() throws Exception {
    final String nonce = "some_nonce";
    Krypton krypton = createKrypton();
    mockPublicKeyServer.enqueuePositivePublicKeyResponse(Optional.of(nonce));
    mockAuthServer.enqueuePositiveJsonAuthResponse();
    mockEgressServer.enqueuePositiveResponse();
    doReturn(0xbeef).when(kryptonListener).onKryptonNeedsTunFd(any(TunFdData.class));

    final ConditionVariable connectedCondition = new ConditionVariable(false);
    doAnswer(
            invocation -> {
              connectedCondition.open();
              return null;
            })
        .when(kryptonListener)
        .onKryptonControlPlaneConnected();
    try {
      krypton.start(createConfig().setIntegrityAttestationEnabled(true).build());
      assertThat(connectedCondition.block(1000)).isTrue();

      // Validate the PublicKeyRequest
      final RecordedRequest publicKeyRequest = mockPublicKeyServer.takeRequest();
      final String publicKeyBodyString = publicKeyRequest.getBody().readUtf8();
      final JSONObject publicKeyBody = new JSONObject(publicKeyBodyString);
      assertThat(publicKeyBody.optBoolean("request_nonce")).isTrue();
      assertThat(publicKeyRequest.getHeader("Content-Type"))
          .isEqualTo("application/json; charset=utf-8");

      // validate the AuthAndSignRequest
      final RecordedRequest authAndSignRequest = mockAuthServer.takeRequest();
      assertThat(authAndSignRequest.getHeader("Content-Type")).isEqualTo("application/x-protobuf");
      byte[] protoBytes = authAndSignRequest.getBody().readByteArray();
      AuthAndSignRequest proto =
          AuthAndSignRequest.parseFrom(protoBytes, ExtensionRegistryLite.getEmptyRegistry());
      assertThat(proto.getAttestation().getAttestationData().getTypeUrl())
          .isEqualTo(AttestationHelper.ANDROID_ATTESTATION_DATA_TYPE_URL);

    } finally {
      krypton.stop();
    }
  }
}
