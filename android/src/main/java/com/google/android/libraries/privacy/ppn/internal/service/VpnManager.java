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

package com.google.android.libraries.privacy.ppn.internal.service;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.privacy.ppn.BypassOptions;
import com.google.android.libraries.privacy.ppn.PpnException;
import com.google.android.libraries.privacy.ppn.PpnOptions;
import com.google.android.libraries.privacy.ppn.internal.TunFdData;
import com.google.android.libraries.privacy.ppn.internal.TunFdData.IpRange.IpFamily;
import com.google.android.libraries.privacy.ppn.xenon.PpnNetwork;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.Set;

/**
 * Wrapper around a mutable VpnService that deals with the details about how to use the service to
 * implement a VPN for PPN. The underlying service will be null if the service is not running.
 */
public class VpnManager {
  private static final String TAG = "VpnManager";

  // The optimal Socket Buffer Size from GCS experimentation is 4MB.
  private static final int SOCKET_BUFFER_SIZE_BYTES = 4 * 1024 * 1024;

  private final Context context;
  private final PpnOptions options;

  // The underlying VpnService this manager is managing.
  // This may be null if the service is not running.
  @Nullable private volatile VpnServiceWrapper vpnService;

  @Nullable private volatile PpnNetwork network;

  private volatile BypassOptions bypassOptions;

  /** Creates a new instance of VpnManager. */
  public static VpnManager create(Context context, PpnOptions options) {
    VpnManager vpnManager = new VpnManager(context, options);
    BypassOptions bypassOptions =
        BypassOptions.builder()
            .setDisallowedApplications(options.getDisallowedApplications())
            .setAllowBypass(options.allowBypass())
            .setExcludeLocalAddresses(options.excludeLocalAddresses())
            .build();
    vpnManager.setBypassOptions(bypassOptions);
    return vpnManager;
  }

  private VpnManager(Context context, PpnOptions options) {
    this.context = context;
    this.options = options;
  }

  /**
   * Resets the underlying service for this manager. This should be called whenever a service starts
   * or stops.
   */
  public void setService(@Nullable VpnService service) {
    setServiceWrapper(service == null ? null : new VpnServiceWrapper(service));
  }

  @VisibleForTesting
  void setServiceWrapper(@Nullable VpnServiceWrapper service) {
    this.vpnService = service;
  }

  /** Stops the underlying Service, if one is running. Otherwise, does nothing. */
  public void stopService() {
    VpnServiceWrapper service = vpnService;
    if (service != null) {
      service.stopSelf();
    }
  }

  /** Returns whether the underlying service has been set. */
  public boolean isRunning() {
    return vpnService != null;
  }

  /** Tells the service to set its underlying network to the given network. */
  public void setNetwork(PpnNetwork ppnNetwork) {
    network = ppnNetwork;
    VpnServiceWrapper service = vpnService;
    if (service != null) {
      Log.w(TAG, "Setting underlying network to " + ppnNetwork);
      if (!service.setUnderlyingNetworks(new Network[] {ppnNetwork.getNetwork()})) {
        Log.w(TAG, "Failed to set underlying network.");
      }
    } else {
      Log.w(TAG, "Failed to set underlying network because service is not running.");
    }
  }

  /** Changes the bypass options for the VPN. */
  public void setBypassOptions(BypassOptions bypassOptions) {
    this.bypassOptions = bypassOptions;
  }

  /**
   * Changes the set of disallowed applications which will bypass the VPN. None of the other
   * BypassOptions will be updated.
   */
  public void setDisallowedApplications(Set<String> disallowedApplications) {
    this.bypassOptions =
        this.bypassOptions.toBuilder().setDisallowedApplications(disallowedApplications).build();
  }

  @VisibleForTesting
  public ImmutableSet<String> disallowedApplications() {
    return this.bypassOptions.disallowedApplications();
  }

  @VisibleForTesting
  public boolean allowBypass() {
    return this.bypassOptions.allowBypass();
  }

  @VisibleForTesting
  public boolean excludeLocalAddresses() {
    return this.bypassOptions.excludeLocalAddresses();
  }

  @Nullable
  private PpnNetwork getPpnNetwork() {
    return network;
  }

  /** Gets the underlying network for the service. */
  @Nullable
  public Network getNetwork() {
    PpnNetwork ppnNetwork = getPpnNetwork();
    if (ppnNetwork == null) {
      return null;
    }
    return ppnNetwork.getNetwork();
  }

  /**
   * Establishes the VpnService and creates the TUN fd for processing requests from the device. This
   * can only be called after onStartService and before onStartService.
   *
   * @param tunFdData the data needed to create a TUN Fd.
   * @return the file descriptor of the TUN. The receiver is responsible for closing it eventually.
   * @throws PpnException if the service has not been set.
   */
  public int createTunFd(TunFdData tunFdData) throws PpnException {
    VpnServiceWrapper service = vpnService;
    if (service == null) {
      throw new PpnException("Tried to create a TUN fd when PPN service wasn't running.");
    }

    if (VpnService.prepare(context) != null) {
      throw new PpnException("VpnService was not prepared or was revoked.");
    }

    VpnService.Builder builder = service.newBuilder();
    setVpnServiceParametersForBypassOptions(builder, this.bypassOptions, options);
    setVpnServiceParametersFromTunFdData(builder, tunFdData, options);

    // If the network was set before the tunnel was established, make sure to set it on the builder.
    PpnNetwork network = getPpnNetwork();
    if (network != null) {
      Log.w(TAG, "Setting initial underlying network to " + network);
      builder.setUnderlyingNetworks(new Network[] {network.getNetwork()});
    }

    ParcelFileDescriptor tunFds;
    try {
      Log.w(TAG, "Establishing Tun FD");
      tunFds = builder.establish();
    } catch (RuntimeException e) {
      Log.e(TAG, "Failure when establishing Tun FD.", e);
      throw new PpnException("Failure when establishing TUN FD.", e);
    }
    if (tunFds == null) {
      throw new PpnException("establish() returned null. The VpnService was probably revoked.");
    }
    int fd = tunFds.detachFd();
    if (fd <= 0) {
      throw new PpnException("Invalid TUN fd: " + fd);
    }

    // There could be a race condition where we set the network between when we set the Builder and
    // when we call establish. Android doesn't track the underlying network until establish is
    // called. So we double check the network here just in case it needs to be changed.
    PpnNetwork currentNetwork = getPpnNetwork();
    if (currentNetwork != null && !currentNetwork.equals(network)) {
      Log.w(TAG, "Updating underlying network to " + currentNetwork);
      if (!service.setUnderlyingNetworks(new Network[] {currentNetwork.getNetwork()})) {
        Log.w(TAG, "Failed to set underlying network to " + currentNetwork);
      }
    }

    return fd;
  }

  private static void setVpnServiceParametersForBypassOptions(
      VpnService.Builder builder, BypassOptions bypassOptions, PpnOptions options) {
    if (options.isIntegrityAttestationEnabled()
        && options.isForceDisallowPlayStoreForAttestationEnabled()
        && options.isAttestationNetworkOverrideEnabled()) {
      // Attestation depends on the Play Integrity APIs provided by the Play Store app. On some
      // devices, Play Store may not have permission to bypass the VPN, so in order to be able to
      // reconnect when the VPN is not working, we have to add the Play Store to the bypass list.
      try {
        Log.w(TAG, "Adding com.android.vending to disallowed applications for Play Integrity.");
        builder.addDisallowedApplication("com.android.vending");
      } catch (NameNotFoundException e) {
        Log.e(TAG, "Disallowed application package not found: com.android.vending", e);
      }
    }
    for (String packageName : bypassOptions.disallowedApplications()) {
      try {
        builder.addDisallowedApplication(packageName);
      } catch (NameNotFoundException e) {
        Log.e(TAG, "Disallowed application package not found: " + packageName, e);
      }
    }

    if (bypassOptions.allowBypass()) {
      Log.w(TAG, "Setting allowBypass");
      builder.allowBypass();
    }

    RouteManager.addIpv4Routes(builder, bypassOptions.excludeLocalAddresses());
    RouteManager.addIpv6Routes(builder, bypassOptions.excludeLocalAddresses());
  }

  private static void setVpnServiceParametersFromTunFdData(
      VpnService.Builder builder, TunFdData tunFdData, PpnOptions options) {
    if (tunFdData.hasSessionName()) {
      builder.setSession(tunFdData.getSessionName());
    }

    builder.setMtu(tunFdData.getMtu());

    // To use IPv6 it must be enabled and the MTU must be at least the IPv6 minimum.
    boolean ipv6Enabled = options.isIPv6Enabled() && tunFdData.getMtu() >= 1280;

    // VpnService.Builder.setMetered(...) is only supported in API 29+.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      Log.w(TAG, "Setting metered to " + tunFdData.getIsMetered());
      builder.setMetered(tunFdData.getIsMetered());
    }

    for (TunFdData.IpRange ipRange : tunFdData.getTunnelIpAddressesList()) {
      if (ipRange.getIpFamily() == IpFamily.IPV6 && !ipv6Enabled) {
        Log.w(TAG, "Skipping IPv6 tunnel address: " + ipRange.getIpRange());
        continue;
      }
      Log.w(TAG, "Adding tunnel address: " + ipRange.getIpRange());
      builder.addAddress(ipRange.getIpRange(), ipRange.getPrefix());
    }
    for (TunFdData.IpRange ipRange : tunFdData.getTunnelDnsAddressesList()) {
      if (ipRange.getIpFamily() == IpFamily.IPV6 && !ipv6Enabled) {
        Log.w(TAG, "Skipping IPv6 DNS address: " + ipRange.getIpRange());
        continue;
      }
      Log.w(TAG, "Adding DNS: " + ipRange.getIpRange());
      builder.addDnsServer(ipRange.getIpRange());
    }
  }

  /**
   * Creates a new protected UDP socket, which can be used by Krypton for connecting to Copper. This
   * can only be called after onStartService and before onStopService.
   *
   * @param ppnNetwork PpnNetwork to bind to.
   * @return the file descriptor of the socket. The receiver is responsible for closing it.
   * @throws PpnException if the service has not been set.
   */
  public int createProtectedDatagramSocket(PpnNetwork ppnNetwork) throws PpnException {
    return createProtectedDatagramSocket(ppnNetwork.getNetwork());
  }

  /**
   * Creates a new protected UDP socket, which can be used by Krypton for connecting to Copper. This
   * can only be called after onStartService and before onStopService.
   *
   * @param network Network to bind to.
   * @return the file descriptor of the socket. The receiver is responsible for closing it.
   * @throws PpnException if the service has not been set.
   */
  private int createProtectedDatagramSocket(Network network) throws PpnException {
    VpnServiceWrapper service = vpnService;
    if (service == null) {
      throw new PpnException("Tried to create a protected socket when PPN service wasn't running.");
    }
    DatagramSocket socket = null;

    try {
      socket = new DatagramSocket();
      socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE_BYTES);
      socket.setSendBufferSize(SOCKET_BUFFER_SIZE_BYTES);

      if (!service.protect(socket)) {
        Log.w(TAG, "Failed to protect datagram socket.");
      }
      network.bindSocket(socket);

      // Explicitly duplicate the socket for Android version 9 (P) and older.
      ParcelFileDescriptor pfd = service.parcelSocket(socket);
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        pfd = pfd.dup();
      }
      // pfd is a duplicate of the original socket, which needs to be closed.
      int fd = pfd.detachFd();
      if (fd <= 0) {
        throw new PpnException("Invalid file descriptor from datagram socket: " + fd);
      }
      return fd;
    } catch (IOException e) {
      throw new PpnException("Unable to create socket or bind network to socket.", e);
    } finally {
      if (socket != null) {
        socket.close();
        socket = null;
      }
    }
  }

  /**
   * Creates a new protected TCP socket, which can be used by Krypton. This can only be called after
   * onStartService and before onStopService.
   *
   * @param ppnNetwork PpnNetwork to bind to.
   * @return the file descriptor of the socket. The receiver is responsible for closing it.
   * @throws PpnException if the service has not been set.
   */
  public int createProtectedStreamSocket(PpnNetwork ppnNetwork) throws PpnException {
    return createProtectedStreamSocket(ppnNetwork.getNetwork());
  }

  /**
   * Creates a new protected TCP socket, which can be used by Krypton. This can only be called after
   * onStartService and before onStopService.
   *
   * @param network Network to bind to.
   * @return the file descriptor of the socket. The receiver is responsible for closing it.
   * @throws PpnException if the service has not been set.
   */
  private int createProtectedStreamSocket(Network network) throws PpnException {
    VpnServiceWrapper service = vpnService;
    if (service == null) {
      throw new PpnException("Tried to create a protected socket when PPN service wasn't running.");
    }
    Socket socket = null;

    try {
      socket = new Socket();
      socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE_BYTES);
      socket.setSendBufferSize(SOCKET_BUFFER_SIZE_BYTES);

      if (!service.protect(socket)) {
        Log.w(TAG, "Failed to protect stream socket.");
      }
      network.bindSocket(socket);

      // Explicitly duplicate the socket for Android version 9 (P) and older.
      ParcelFileDescriptor pfd = service.parcelSocket(socket);
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        pfd = pfd.dup();
      }
      // pfd is a duplicate of the original socket, which needs to be closed.
      socket.close();
      socket = null;
      int fd = pfd.detachFd();
      if (fd <= 0) {
        throw new PpnException("Invalid file descriptor from socket: " + fd);
      }
      return fd;
    } catch (IOException e) {
      if (socket != null) {
        try {
          socket.close();
        } catch (IOException ignored) {
          // There's nothing really to be done in this case.
          Log.w(TAG, "Unable to close socket.", ignored);
        }
      }
      throw new PpnException("Unable to create socket or bind network to socket.", e);
    }
  }

  /**
   * Protects the given socket if the VpnService is running. Otherwise, does nothing. This is useful
   * for making TCP connections that should always bypass the VPN.
   */
  void protect(Socket socket) {
    VpnServiceWrapper service = vpnService;
    Log.w(TAG, "Protecting socket: " + socket);
    if (service != null) {
      if (!service.protect(socket)) {
        Log.w(TAG, "Failed to protect socket.");
      }
    } else {
      Log.w(TAG, "Cannot protect socket with no VpnService running.");
    }
  }
}
