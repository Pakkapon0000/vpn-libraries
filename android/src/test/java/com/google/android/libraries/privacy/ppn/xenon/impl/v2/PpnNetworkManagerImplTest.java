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

package com.google.android.libraries.privacy.ppn.xenon.impl.v2;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.gms.tasks.Task;
import com.google.android.libraries.privacy.ppn.PpnOptions;
import com.google.android.libraries.privacy.ppn.internal.ConnectionStatus.ConnectionQuality;
import com.google.android.libraries.privacy.ppn.internal.NetworkInfo.AddressFamily;
import com.google.android.libraries.privacy.ppn.internal.NetworkType;
import com.google.android.libraries.privacy.ppn.internal.http.HttpFetcher;
import com.google.android.libraries.privacy.ppn.xenon.PpnNetwork;
import com.google.android.libraries.privacy.ppn.xenon.PpnNetworkCallback;
import com.google.android.libraries.privacy.ppn.xenon.PpnNetworkListener;
import com.google.android.libraries.privacy.ppn.xenon.PpnNetworkListener.NetworkUnavailableReason;
import com.google.android.libraries.privacy.ppn.xenon.PpnNetworkManager;
import com.google.errorprone.annotations.ResultIgnorabilityUnspecified;
import java.io.IOException;
import java.net.DatagramSocket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowWifiManager;

@RunWith(RobolectricTestRunner.class)
public final class PpnNetworkManagerImplTest {
  private PpnNetworkManager ppnNetworkManager;
  private ShadowConnectivityManager shadowConnectivityManager;
  private ShadowWifiManager shadowWifiManager;
  private Context context;

  private static final String CONNECTIVITY_CHECK_URL = "http://gstatic_internet_check_url";

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Mock private PpnNetworkListener mockListener;
  @Mock private WifiInfo mockWifiInfo;
  @Mock private NetworkInfo wifiNetworkInfo;
  @Mock private NetworkInfo cellNetworkInfo;
  @Mock private HttpFetcher mockHttpFetcher;
  @Mock private PpnOptions mockPpnOptions;

  private Network wifiAndroidNetwork;
  private Network cellAndroidNetwork;

  @Mock private Network mockCellAndroidNetwork;

  @Before
  public void setUp() {
    ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    when(mockPpnOptions.getBackgroundExecutor()).thenReturn(backgroundExecutor);
    when(mockPpnOptions.getConnectivityCheckUrl()).thenReturn(CONNECTIVITY_CHECK_URL);
    when(mockPpnOptions.getConnectivityCheckMaxRetries()).thenReturn(3);
    when(mockPpnOptions.getConnectivityCheckRetryDelay()).thenReturn(Duration.ofSeconds(15));

    wifiAndroidNetwork = ShadowNetwork.newInstance(/* netId= */ 1);
    cellAndroidNetwork = ShadowNetwork.newInstance(/* netId= */ 2);

    context = ApplicationProvider.getApplicationContext();
    shadowConnectivityManager =
        shadowOf((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE));
    shadowWifiManager = shadowOf((WifiManager) context.getSystemService(Context.WIFI_SERVICE));

    // Assume all tested networks are valid from Android unless otherwise set in the specific test.
    shadowConnectivityManager.setNetworkCapabilities(
        wifiAndroidNetwork, ShadowNetworkCapabilities.newInstance());
    shadowConnectivityManager.setNetworkCapabilities(
        cellAndroidNetwork, ShadowNetworkCapabilities.newInstance());

    shadowConnectivityManager.addNetwork(wifiAndroidNetwork, wifiNetworkInfo);
    shadowConnectivityManager.addNetwork(cellAndroidNetwork, cellNetworkInfo);

    ppnNetworkManager = createPpnNetworkManagerImpl();

    when(wifiNetworkInfo.getType()).thenReturn(ConnectivityManager.TYPE_WIFI);
    when(cellNetworkInfo.getType()).thenReturn(ConnectivityManager.TYPE_MOBILE);
    when(wifiNetworkInfo.isConnected()).thenReturn(true);
    when(cellNetworkInfo.isConnected()).thenReturn(true);

    when(mockHttpFetcher.checkGet(eq(CONNECTIVITY_CHECK_URL), any(Network.class), any()))
        .thenReturn(true);
  }

  @Test
  public void testStartNetworkRequests_noExistingCallbacks() throws Exception {
    ppnNetworkManager.startNetworkRequests();

    Set<NetworkCallback> networkCallbacks = shadowConnectivityManager.getNetworkCallbacks();
    assertThat(networkCallbacks).hasSize(2);

    List<NetworkType> networkTypes =
        networkCallbacks.stream()
            .map(networkCallback -> ((PpnNetworkCallback) networkCallback).getNetworkType())
            .collect(toList());

    // Note: ContainsExactly is NOT order dependent, which we are not guaranteed.
    assertThat(networkTypes).containsExactly(NetworkType.WIFI, NetworkType.CELLULAR);
  }

  @Test
  public void testStartNetworkRequests_existingCallbacks() throws Exception {
    // Call startNetworkRequest twice to ensure that the second time calling doesn't initialize 2
    // more NetworkRequests.
    ppnNetworkManager.startNetworkRequests();
    ppnNetworkManager.startNetworkRequests();

    Set<NetworkCallback> networkCallbacks = shadowConnectivityManager.getNetworkCallbacks();
    assertThat(networkCallbacks).hasSize(2);

    List<NetworkType> networkTypes =
        networkCallbacks.stream()
            .map(networkCallback -> ((PpnNetworkCallback) networkCallback).getNetworkType())
            .collect(toList());

    // Note: ContainsExactly is NOT order dependent, which we are not guaranteed.
    assertThat(networkTypes).containsExactly(NetworkType.WIFI, NetworkType.CELLULAR);
  }

  @Test
  public void testStopNetworkRequests_callbacksStop() throws Exception {
    // Initialize the Network Callbacks
    ppnNetworkManager.startNetworkRequests();
    Set<NetworkCallback> networkCallbacksBefore = shadowConnectivityManager.getNetworkCallbacks();
    assertThat(networkCallbacksBefore).hasSize(2);

    ppnNetworkManager.stopNetworkRequests();

    // Verify that the callbacks are now empty after we stop the NetworkRequests.
    Set<NetworkCallback> networkCallbacksAfter = shadowConnectivityManager.getNetworkCallbacks();
    assertThat(networkCallbacksAfter).isEmpty();
  }

  @Test
  public void testStopNetworkRequests_clearsState() throws Exception {
    // Add a new available Network.
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    verify(mockListener).onNetworkAvailable(wifiNetwork);

    ppnNetworkManager.stopNetworkRequests();
    shadowOf(Looper.getMainLooper()).idle();

    // We should no longer have any active networks.
    verify(mockListener).onNetworkUnavailable(NetworkUnavailableReason.UNKNOWN);
    assertThat(ppnNetworkManager.getAllNetworks()).isEmpty();
  }

  @Test
  public void testStartAfterStop() throws Exception {
    // Initialize the Network Callbacks
    ppnNetworkManager.startNetworkRequests();
    Set<NetworkCallback> networkCallbacksBefore = shadowConnectivityManager.getNetworkCallbacks();
    assertThat(networkCallbacksBefore).hasSize(2);

    // Add a new available Network.
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    // Verify that we have 1 available network as expected.
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);

    ppnNetworkManager.stopNetworkRequests();
    shadowOf(Looper.getMainLooper()).idle();

    // Verify that the callbacks are now empty after we stop the NetworkRequests
    Set<NetworkCallback> networkCallbacksAfter = shadowConnectivityManager.getNetworkCallbacks();
    assertThat(networkCallbacksAfter).isEmpty();

    // Verify that we no longer have any active networks and that we got a callback.
    verify(mockListener).onNetworkUnavailable(NetworkUnavailableReason.UNKNOWN);
    assertThat(ppnNetworkManager.getAllNetworks()).isEmpty();

    // Initialize the Network Callbacks again after stopping.
    ppnNetworkManager.startNetworkRequests();
    Set<NetworkCallback> networkCallbacksSecondAfter =
        shadowConnectivityManager.getNetworkCallbacks();
    assertThat(networkCallbacksSecondAfter).hasSize(2);

    // Another network is available.
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);

    // We expect onAvailable callback to have happened twice -- once in the beginning and once now.
    verify(mockListener, times(2)).onNetworkAvailable(wifiNetwork);
  }

  @Test
  public void testHandleNetworkAvailable() throws Exception {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);

    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    verify(mockListener).onNetworkAvailable(wifiNetwork);
  }

  @Test
  public void testHandleNetworkAvailable_cellularConnectivity() throws Exception {
    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);
    assertThat(cellularNetwork.getAddressFamily()).isEqualTo(AddressFamily.V4V6);
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));
    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));

    PpnNetwork activeNetwork = ((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork();
    assertThat(activeNetwork).isEqualTo(cellularNetwork);
    assertThat(activeNetwork.getAddressFamily()).isEqualTo(AddressFamily.V4V6);

    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, cellAndroidNetwork, AddressFamily.V4);
    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, cellAndroidNetwork, AddressFamily.V6);
  }

  @Test
  public void testHandleNetworkAvailable_wifiConnectivity() throws Exception {
    when(mockHttpFetcher.checkGet(any(), any(), eq(AddressFamily.V4))).thenReturn(true);
    when(mockHttpFetcher.checkGet(any(), any(), eq(AddressFamily.V6))).thenReturn(true);

    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    assertThat(wifiNetwork.getAddressFamily()).isEqualTo(AddressFamily.V4V6);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    PpnNetwork activeNetwork = ((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork();
    assertThat(activeNetwork).isEqualTo(wifiNetwork);
    assertThat(activeNetwork.getAddressFamily()).isEqualTo(AddressFamily.V4V6);

    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4);
    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6);
  }

  @Test
  public void testHandleNetworkAvailable_wifiConnectivityOnlyIpv4() throws Exception {
    when(mockHttpFetcher.checkGet(any(), any(), eq(AddressFamily.V4))).thenReturn(true);
    when(mockHttpFetcher.checkGet(any(), any(), eq(AddressFamily.V6))).thenReturn(false);

    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    assertThat(wifiNetwork.getAddressFamily()).isEqualTo(AddressFamily.V4V6);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    PpnNetwork activeNetwork = ((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork();
    assertThat(activeNetwork).isEqualTo(wifiNetwork);
    assertThat(activeNetwork.getAddressFamily()).isEqualTo(AddressFamily.V4);

    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4);
    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6);
  }

  @Test
  public void testHandleNetworkAvailable_wifiConnectivityOnlyIpv6() throws Exception {
    when(mockHttpFetcher.checkGet(any(), any(), eq(AddressFamily.V4))).thenReturn(false);
    when(mockHttpFetcher.checkGet(any(), any(), eq(AddressFamily.V6))).thenReturn(true);

    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    assertThat(wifiNetwork.getAddressFamily()).isEqualTo(AddressFamily.V4V6);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    PpnNetwork activeNetwork = ((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork();
    assertThat(activeNetwork).isEqualTo(wifiNetwork);
    assertThat(activeNetwork.getAddressFamily()).isEqualTo(AddressFamily.V6);

    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4);
    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6);
  }

  @Test
  public void testHandleNetworkAvailable_newNetworkIsNotBetter() throws Exception {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    verify(mockListener).onNetworkAvailable(wifiNetwork);

    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));
    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(2);
    verify(mockListener).onNetworkAvailable(wifiNetwork);

    // The wifiNetwork should still be the active network.
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);
  }

  @Test
  public void testHandleNetworkAvailable_newNetworkIsBetter() throws Exception {
    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));
    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));

    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(cellularNetwork);

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    verify(mockListener).onNetworkAvailable(cellularNetwork);

    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(2);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);
    verify(mockListener).onNetworkAvailable(wifiNetwork);
  }

  @Test
  public void testHandleNetworkAvailable_wifiHasNoConnectivityAndNoRetry() throws Exception {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);

    // Mock the connectivity check to be false.
    ConditionVariable checkGetStarted1 = new ConditionVariable(false);
    when(mockHttpFetcher.checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4))
        .thenReturn(false);
    when(mockHttpFetcher.checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6))
        .thenAnswer(
            invocation -> {
              checkGetStarted1.open();
              return false;
            });

    // Tell the network manager to try the network.
    Task<Boolean> handledTask = ppnNetworkManager.handleNetworkAvailable(wifiNetwork);
    // Verify that we checked the connectivity.
    assertThat(checkGetStarted1.block(1000)).isTrue();
    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4);
    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6);
    // Verify that it didn't get added to the active network list.
    assertThat(ppnNetworkManager.getAllNetworks()).isEmpty();

    // Mock the connectivity check to be true now.
    ConditionVariable checkGetStarted2 = new ConditionVariable(false);
    when(mockHttpFetcher.checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4))
        .thenReturn(true);
    when(mockHttpFetcher.checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6))
        .thenAnswer(
            invocation -> {
              checkGetStarted2.open();
              return true;
            });

    // Now, link properties change should check connectivity again.
    await(ppnNetworkManager.handleNetworkLinkPropertiesChanged(wifiNetwork, new LinkProperties()));
    assertThat(checkGetStarted2.block(1000)).isTrue();
    verify(mockHttpFetcher, times(2))
        .checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4);
    verify(mockHttpFetcher, times(2))
        .checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6);

    // The original handleNetworkAvailable is still waiting to retry, so advance time enough for it
    // to notice that the network is no longer pending and stop.
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
    await(handledTask);

    // Verify that the WifiNetwork is now available.
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);
  }

  @Test
  public void testHandleNetworkAvailable_wifiHasNoConnectivityAndRetries() throws Exception {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);

    // Mock the connectivity check to be false.
    ConditionVariable checkGetStarted1 = new ConditionVariable(false);
    when(mockHttpFetcher.checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4))
        .thenReturn(false);
    when(mockHttpFetcher.checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6))
        .thenAnswer(
            invocation -> {
              checkGetStarted1.open();
              return false;
            });

    // Make sure the connectivity test fails once.
    Task<Boolean> handleTask = ppnNetworkManager.handleNetworkAvailable(wifiNetwork);
    assertThat(checkGetStarted1.block(1000)).isTrue();
    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4);
    verify(mockHttpFetcher).checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6);
    // Assert that the network wasn't added to the list.
    assertThat(ppnNetworkManager.getAllNetworks()).isEmpty();

    // Mock the connectivity check to be true now.
    Semaphore checkGetStarted2 = new Semaphore(0);
    when(mockHttpFetcher.checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4))
        .thenReturn(true);
    when(mockHttpFetcher.checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6))
        .thenAnswer(
            invocation -> {
              checkGetStarted2.release();
              return true;
            });

    // Idle the Looper long enough for it to retry the connectivity check and wait for the second
    // network check to complete.
    boolean success = false;
    for (int attempt = 0; attempt < 4 && !success; ++attempt) {
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8));
      success |= checkGetStarted2.tryAcquire(100, MILLISECONDS);
    }
    assertThat(success).isTrue();
    verify(mockHttpFetcher, times(2))
        .checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4);
    verify(mockHttpFetcher, times(2))
        .checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6);

    // Verify that the WifiNetwork is now available.
    await(handleTask);
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);
  }

  @Test
  public void testHandleNetworkLost_networkStillAvailable_activeNetworkLost() throws Exception {
    // First, need to populate 2 available networks.
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);

    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);

    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));
    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));

    // Verify the precondition that we have 2 networks available and that the Active Network is Wifi
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(2);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);

    ppnNetworkManager.handleNetworkLost(wifiNetwork);
    shadowOf(Looper.getMainLooper()).idle();

    // We should only have 1 available network now, Cellular, and it should be the active network.
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(cellularNetwork);
    verify(mockListener, never()).onNetworkUnavailable(NetworkUnavailableReason.UNKNOWN);

    // If active Network was lost, we should be notifying of the cellular fallback network.
    verify(mockListener).onNetworkAvailable(cellularNetwork);

    // We expect the wifiNetwork to have only been available ONCE from the beginning when added.
    verify(mockListener).onNetworkAvailable(wifiNetwork);
  }

  @Test
  public void testHandleNetworkLost_networkStillAvailable_nonActiveNetworkLost() throws Exception {
    // First, need to populate 2 available networks.
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    verify(mockListener).onNetworkAvailable(wifiNetwork);

    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));
    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));

    // Verify the precondition that we have 2 networks available and that the Active Network is Wifi
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(2);
    verify(mockListener, never()).onNetworkAvailable(cellularNetwork);

    ppnNetworkManager.handleNetworkLost(cellularNetwork);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    verify(mockListener, never()).onNetworkUnavailable(NetworkUnavailableReason.UNKNOWN);

    // Non-active Network was lost. We expect no change on new available networks.
    verify(mockListener, never()).onNetworkAvailable(cellularNetwork);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);

    // onNetworkAvailable should have only happened ONCE for wifiNetwork from earlier.
    // No additional calls
    verify(mockListener).onNetworkAvailable(wifiNetwork);
  }

  @Test
  public void testHandleNetworkLost_networkUnavailable() throws Exception {
    // First, need to populate available networks.
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);

    ppnNetworkManager.handleNetworkLost(wifiNetwork);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(ppnNetworkManager.getAllNetworks()).isEmpty();
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork()).isNull();
    verify(mockListener).onNetworkUnavailable(NetworkUnavailableReason.UNKNOWN);
  }

  @Test
  public void handleNetworkLost_clearsNetworkValidation() throws Exception {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);
    ppnNetworkManager.handleNetworkLost(wifiNetwork);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);

    // The network should have been validated twice since the original validation is cleared when
    // the network is lost.
    verify(mockHttpFetcher, times(2))
        .checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4);
    verify(mockHttpFetcher, times(2))
        .checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6);
  }

  @Test
  public void testHandleNetworkCapabilitiesChanged() throws Exception {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    NetworkCapabilities wifiNetworkCapabilities = ShadowNetworkCapabilities.newInstance();
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);

    // Set the expected NetworkCapabilities for a valid network.
    ShadowNetworkCapabilities shadowWifiNetworkCapabilities = shadowOf(wifiNetworkCapabilities);
    shadowWifiNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
    shadowWifiNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

    await(ppnNetworkManager.handleNetworkCapabilitiesChanged(wifiNetwork, wifiNetworkCapabilities));

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);
    verify(mockListener, never()).onNetworkUnavailable(NetworkUnavailableReason.UNKNOWN);
  }

  @Test
  public void testHandleNetworkCapabilitiesChanged_networkCapabilitiesBad() throws Exception {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    NetworkCapabilities wifiNetworkCapabilities = ShadowNetworkCapabilities.newInstance();
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);

    // Remove the expected NetworkCapabilities for a valid network.
    ShadowNetworkCapabilities shadowWifiNetworkCapabilities = shadowOf(wifiNetworkCapabilities);
    shadowWifiNetworkCapabilities.removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
    shadowWifiNetworkCapabilities.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

    await(ppnNetworkManager.handleNetworkCapabilitiesChanged(wifiNetwork, wifiNetworkCapabilities));

    // The Network should no longer be active given that the NetworkCapabilities changed to
    // something we no longer accept.
    assertThat(ppnNetworkManager.getAllNetworks()).isEmpty();
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork()).isNull();
    verify(mockListener).onNetworkUnavailable(NetworkUnavailableReason.UNKNOWN);
  }

  @Test
  public void testHandleNetworkAvailable_connectionQualityResets() throws Exception {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    // Explicitly set the ConnectionQuality in the initial state.
    ((PpnNetworkManagerImpl) ppnNetworkManager).setConnectionQuality(ConnectionQuality.FAIR);

    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getConnectionQuality())
        .isEqualTo(ConnectionQuality.UNKNOWN_QUALITY);
    verify(mockListener).onNetworkAvailable(wifiNetwork);
  }

  @Test
  @Config(sdk = Build.VERSION_CODES.P)
  public void testHandleNetworkCapabilitiesChanged_newConnectionQuality_preAndroidQ()
      throws Exception {
    // The RSSI db value of a Fair Wifi Connection.
    int rssi = -70;

    // Mock the return of the RSSI getting it from the Android WifiManager that happens inside
    // PpnNetworkSelectorImpl.
    when(mockWifiInfo.getRssi()).thenReturn(rssi);
    shadowWifiManager.setConnectionInfo(mockWifiInfo);

    // Need to recreate the PpnNetworkManagerImpl instance because of the new shadowWifiManager
    // mocked return values.
    ppnNetworkManager = createPpnNetworkManagerImpl();

    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    NetworkCapabilities wifiNetworkCapabilities = ShadowNetworkCapabilities.newInstance();
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    // Verify the initial state
    verify(mockListener).onNetworkAvailable(wifiNetwork);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getConnectionQuality())
        .isEqualTo(ConnectionQuality.UNKNOWN_QUALITY);
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);

    // Set the expected NetworkCapabilities for a valid network.
    ShadowNetworkCapabilities shadowWifiNetworkCapabilities = shadowOf(wifiNetworkCapabilities);
    shadowWifiNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
    shadowWifiNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

    await(ppnNetworkManager.handleNetworkCapabilitiesChanged(wifiNetwork, wifiNetworkCapabilities));
  }

  @Test
  @Config(sdk = Build.VERSION_CODES.P)
  public void testHandleNetworkCapabilitiesChanged_sameConnectionQuality_preAndroidQ()
      throws Exception {
    // The RSSI db value of a Fair Wifi Connection.
    int rssi = -67;

    // Mock the return of the RSSI getting it from the Android WifiManager that happens inside
    // PpnNetworkSelectorImpl.
    when(mockWifiInfo.getRssi()).thenReturn(rssi);
    shadowWifiManager.setConnectionInfo(mockWifiInfo);

    // Need to recreate the PpnNetworkManagerImpl instance because of the new shadowWifiManager
    // mocked return values.
    ppnNetworkManager = createPpnNetworkManagerImpl();

    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    NetworkCapabilities wifiNetworkCapabilities = ShadowNetworkCapabilities.newInstance();
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    // Verify the initial state
    ((PpnNetworkManagerImpl) ppnNetworkManager).setConnectionQuality(ConnectionQuality.GOOD);
    verify(mockListener).onNetworkAvailable(wifiNetwork);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(wifiNetwork);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getConnectionQuality())
        .isEqualTo(ConnectionQuality.GOOD);
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);

    // Set the expected NetworkCapabilities for a valid network.
    ShadowNetworkCapabilities shadowWifiNetworkCapabilities = shadowOf(wifiNetworkCapabilities);
    shadowWifiNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
    shadowWifiNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

    await(ppnNetworkManager.handleNetworkCapabilitiesChanged(wifiNetwork, wifiNetworkCapabilities));
    shadowOf(Looper.getMainLooper()).idle();
  }

  @Test
  @Config(sdk = Build.VERSION_CODES.Q)
  public void testHandleNetworkCapabilitiesChanged_newConnectionQuality_postAndroidQ()
      throws Exception {}

  @Test
  public void testHandleNetworkLinkPropertiesChanged_addPendingNetwork() throws Exception {
    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));

    // This cellular network should NOT have been added to the available networks yet.
    assertThat(ppnNetworkManager.getAllNetworks()).isEmpty();

    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    assertThat(((PpnNetworkManagerImpl) ppnNetworkManager).getActiveNetwork())
        .isEqualTo(cellularNetwork);
  }

  @Test
  public void testHandleNetworkLinkPropertiesChanged_ignorePendingNetwork() throws Exception {
    // Throw an exception when trying to bind a socket for this network.
    doThrow(new IOException()).when(mockCellAndroidNetwork).bindSocket(any(DatagramSocket.class));

    // Turn off retries so that handleNetworkLinkPropertiesChanged can complete its work without
    // manually advancing the looper.
    when(mockPpnOptions.getConnectivityCheckMaxRetries()).thenReturn(0);

    // Note: We are relying on NetworkCapabilities to return null for this network so that the
    // evaluation for pending and available networks passes.

    PpnNetwork cellularNetwork = new PpnNetwork(mockCellAndroidNetwork, NetworkType.CELLULAR);
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));

    // This cellular network should NOT have been added to the available networks yet.
    assertThat(ppnNetworkManager.getAllNetworks()).isEmpty();

    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));

    // This network failed to bind to a socket so it should not be added as a network option.
    assertThat(ppnNetworkManager.getAllNetworks()).isEmpty();
  }

  @Test
  public void testCleanupNetworks_keepConnectedNetworks() {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));
    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(2);

    ((PpnNetworkManagerImpl) ppnNetworkManager).cleanUpNetworkMaps();

    // Verify that no networks were removed from the available network map.
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(2);
  }

  @Test
  public void testCleanupNetworks_removeDisconnectedAvailableNetworks() {
    when(wifiNetworkInfo.isConnected()).thenReturn(false);
    when(cellNetworkInfo.isConnected()).thenReturn(false);

    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));

    ((PpnNetworkManagerImpl) ppnNetworkManager).cleanUpNetworkMaps();

    // Verify that all networks were removed from the available network map.
    assertThat(ppnNetworkManager.getAllNetworks()).isEmpty();
  }

  @Test
  public void deprioritize_noNetworks() {
    assertThat(ppnNetworkManager.deprioritize(createNetworkInfo(/* networkId= */ 12345L)))
        .isFalse();
  }

  @Test
  public void deprioritize_incorrectNetworkId() {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);

    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    verify(mockListener).onNetworkAvailable(wifiNetwork);

    assertThat(ppnNetworkManager.deprioritize(createNetworkInfo(/* networkId= */ 0))).isFalse();
  }

  @Test
  public void deprioritize_onlyNetworkNotDeprioritized() {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    long wifiNetworkId = wifiNetwork.getNetworkId();

    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));

    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
    verify(mockListener).onNetworkAvailable(wifiNetwork);

    assertThat(ppnNetworkManager.deprioritize(createNetworkInfo(wifiNetworkId))).isFalse();
  }

  @Test
  public void deprioritize_deprioritizeRemovesNetworkFromAvailable() {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);
    long wifiNetworkId = wifiNetwork.getNetworkId();

    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));
    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(2);

    assertThat(ppnNetworkManager.deprioritize(createNetworkInfo(wifiNetworkId))).isTrue();
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);
  }

  @Test
  public void deprioritize_deprioritizeClearsNetworkValidation() {
    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);
    long wifiNetworkId = wifiNetwork.getNetworkId();

    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));
    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(2);
    assertThat(ppnNetworkManager.deprioritize(createNetworkInfo(wifiNetworkId))).isTrue();
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(1);

    await(ppnNetworkManager.handleNetworkLinkPropertiesChanged(wifiNetwork, new LinkProperties()));
    assertThat(ppnNetworkManager.getAllNetworks()).hasSize(2);

    // Verify that the network validation was cleared when it was deprioritized and that the network
    // is validated twice.
    verify(mockHttpFetcher, times(2))
        .checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V4);
    verify(mockHttpFetcher, times(2))
        .checkGet(CONNECTIVITY_CHECK_URL, wifiAndroidNetwork, AddressFamily.V6);
  }

  @Test
  public void testGetAllNetworks() {
    assertThat(ppnNetworkManager.getAllNetworks().size()).isEqualTo(0);

    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));
    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));
    assertThat(ppnNetworkManager.getAllNetworks().size()).isEqualTo(1);

    PpnNetwork wifiNetwork = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    await(ppnNetworkManager.handleNetworkAvailable(wifiNetwork));
    ppnNetworkManager.handleNetworkLost(cellularNetwork);
    assertThat(ppnNetworkManager.getAllNetworks().size()).isEqualTo(1);
  }

  @Test
  public void getDebugInfo_isPopulated() {
    PpnNetwork cellularNetwork = new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR);
    await(ppnNetworkManager.handleNetworkAvailable(cellularNetwork));
    await(
        ppnNetworkManager.handleNetworkLinkPropertiesChanged(
            cellularNetwork, new LinkProperties()));
    await(
        ppnNetworkManager.handleNetworkAvailable(
            new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI)));
    shadowOf(Looper.getMainLooper()).idle();

    JSONObject debugInfo = ppnNetworkManager.getDebugJson();

    assertThat(debugInfo.optJSONArray(XenonDebugJson.AVAILABLE_NETWORKS).length()).isEqualTo(2);
    assertThat(debugInfo.optJSONArray(XenonDebugJson.PENDING_NETWORKS)).isNull();
    assertThat(
            debugInfo.optJSONObject(XenonDebugJson.ACTIVE_NETWORK).opt(XenonDebugJson.NETWORK_NAME))
        .isEqualTo(wifiAndroidNetwork.toString());
    assertThat(
            debugInfo.optJSONObject(XenonDebugJson.ACTIVE_NETWORK).opt(XenonDebugJson.NETWORK_TYPE))
        .isEqualTo(NetworkType.WIFI.name());
  }

  @Test
  public void getDebugInfo_pendingNetworkIsPopulated() throws JSONException {
    await(
        ppnNetworkManager.handleNetworkAvailable(
            new PpnNetwork(cellAndroidNetwork, NetworkType.CELLULAR)));
    await(
        ppnNetworkManager.handleNetworkAvailable(
            new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI)));
    shadowOf(Looper.getMainLooper()).idle();

    JSONObject debugInfo = ppnNetworkManager.getDebugJson();

    assertThat(debugInfo.optJSONArray(XenonDebugJson.AVAILABLE_NETWORKS).length()).isEqualTo(1);
    assertThat(debugInfo.optJSONArray(XenonDebugJson.PENDING_NETWORKS).length()).isEqualTo(1);
    assertThat(
            debugInfo
                .optJSONArray(XenonDebugJson.PENDING_NETWORKS)
                .getJSONObject(0)
                .opt(XenonDebugJson.NETWORK_NAME))
        .isEqualTo(cellAndroidNetwork.toString());
    assertThat(
            debugInfo
                .optJSONArray(XenonDebugJson.PENDING_NETWORKS)
                .getJSONObject(0)
                .opt(XenonDebugJson.NETWORK_TYPE))
        .isEqualTo(NetworkType.CELLULAR.name());
    assertThat(
            debugInfo.optJSONObject(XenonDebugJson.ACTIVE_NETWORK).opt(XenonDebugJson.NETWORK_NAME))
        .isEqualTo(wifiAndroidNetwork.toString());
    assertThat(
            debugInfo.optJSONObject(XenonDebugJson.ACTIVE_NETWORK).opt(XenonDebugJson.NETWORK_TYPE))
        .isEqualTo(NetworkType.WIFI.name());
  }

  @Test
  public void ppnNetwork_isEqual_respectsType() {
    PpnNetwork network1 = new PpnNetwork(wifiAndroidNetwork, NetworkType.WIFI);
    PpnNetwork network2 = new PpnNetwork(wifiAndroidNetwork, NetworkType.CELLULAR);
    assertThat(network1.equals(network2)).isFalse();
    assertThat(network2.equals(network1)).isFalse();
  }

  private static com.google.android.libraries.privacy.ppn.internal.NetworkInfo createNetworkInfo(
      long networkId) {
    return com.google.android.libraries.privacy.ppn.internal.NetworkInfo.newBuilder()
        .setNetworkId(networkId)
        .build();
  }

  private PpnNetworkManagerImpl createPpnNetworkManagerImpl() {
    return new PpnNetworkManagerImpl(context, mockListener, mockHttpFetcher, mockPpnOptions);
  }

  /**
   * Blocks until the given task is complete. This can't use Tasks.await, because the async work may
   * need to run on the main thread.
   */
  @ResultIgnorabilityUnspecified
  private static <T> T await(Task<T> task) {
    while (!task.isComplete()) {
      // Allow the main looper to clear itself out.
      shadowOf(Looper.getMainLooper()).idle();
    }
    shadowOf(Looper.getMainLooper()).idle();
    return task.getResult();
  }
}
