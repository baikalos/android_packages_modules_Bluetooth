/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.a2dp;

import static android.Manifest.permission.BLUETOOTH_CONNECT;

import static com.android.bluetooth.Utils.checkCallerTargetSdk;
import static com.android.bluetooth.Utils.enforceBluetoothPrivilegedPermission;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothA2dp.OptionalCodecsPreferenceStatus;
import android.bluetooth.BluetoothA2dp.OptionalCodecsSupportStatus;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.BufferConstraints;
import android.bluetooth.IBluetoothA2dp;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Build;
import android.os.HandlerThread;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.btservice.storage.BaikalDatabase;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.SynchronousResultReceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Provides Bluetooth A2DP profile, as a service in the Bluetooth application.
 * @hide
 */
public class A2dpService extends ProfileService {
    private static final boolean DBG = true;
    private static final String TAG = "A2dpService";

    // TODO(b/240635097): remove in U
    private static final int SOURCE_CODEC_TYPE_OPUS = 6;

    private static A2dpService sA2dpService;

    private AdapterService mAdapterService;
    private DatabaseManager mDatabaseManager;
    private BaikalDatabase mBaikalDatabase;
    private HandlerThread mStateMachinesThread;

    @VisibleForTesting
    A2dpNativeInterface mA2dpNativeInterface;
    @VisibleForTesting
    ServiceFactory mFactory = new ServiceFactory();
    private AudioManager mAudioManager;
    private A2dpCodecConfig mA2dpCodecConfig;

    @GuardedBy("mStateMachines")
    private BluetoothDevice mActiveDevice;
    private final ConcurrentMap<BluetoothDevice, A2dpStateMachine> mStateMachines =
            new ConcurrentHashMap<>();

    // Protect setActiveDevice() so all invoked is handled squentially
    private final Object mActiveSwitchingGuard = new Object();

    // Timeout for state machine thread join, to prevent potential ANR.
    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;

    // Upper limit of all A2DP devices: Bonded or Connected
    private static final int MAX_A2DP_STATE_MACHINES = 50;
    // Upper limit of all A2DP devices that are Connected or Connecting
    private int mMaxConnectedAudioDevices = 1;
    // A2DP Offload Enabled in platform
    boolean mA2dpOffloadEnabled = false;

    private BroadcastReceiver mBondStateChangedReceiver;

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileA2dpSourceEnabled().orElse(false);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothA2dpBinder(this);
    }

    @Override
    protected void create() {
        Log.i(TAG, "create()");
    }

    @Override
    protected boolean start() {
        Log.i(TAG, "start()");
        if (sA2dpService != null) {
            throw new IllegalStateException("start() called twice");
        }

        // Step 1: Get AdapterService, A2dpNativeInterface, DatabaseManager, AudioManager.
        // None of them can be null.
        mAdapterService = Objects.requireNonNull(AdapterService.getAdapterService(),
                "AdapterService cannot be null when A2dpService starts");
        mA2dpNativeInterface = Objects.requireNonNull(A2dpNativeInterface.getInstance(),
                "A2dpNativeInterface cannot be null when A2dpService starts");

        mDatabaseManager = Objects.requireNonNull(mAdapterService.getDatabase(),
                "DatabaseManager cannot be null when A2dpService starts");
        mBaikalDatabase = Objects.requireNonNull(mAdapterService.getBaikalDatabase(),
                "BaikalDatabase cannot be null when A2dpService starts");

        mAudioManager = getSystemService(AudioManager.class);
        Objects.requireNonNull(mAudioManager,
                               "AudioManager cannot be null when A2dpService starts");

        // Step 2: Get maximum number of connected audio devices
        mMaxConnectedAudioDevices = mAdapterService.getMaxConnectedAudioDevices();
        Log.i(TAG, "Max connected audio devices set to " + mMaxConnectedAudioDevices);

        // Step 3: Start handler thread for state machines
        mStateMachines.clear();
        mStateMachinesThread = new HandlerThread("A2dpService.StateMachines");
        mStateMachinesThread.start();

        // Step 4: Setup codec config
        mA2dpCodecConfig = new A2dpCodecConfig(this, mA2dpNativeInterface);

        // Step 5: Initialize native interface
        mA2dpNativeInterface.init(mMaxConnectedAudioDevices,
                                  mA2dpCodecConfig.codecConfigPriorities(),
                                  mA2dpCodecConfig.codecConfigOffloading());

        // Step 6: Check if A2DP is in offload mode
        mA2dpOffloadEnabled = mAdapterService.isA2dpOffloadEnabled();
        if (DBG) {
            Log.d(TAG, "A2DP offload flag set to " + mA2dpOffloadEnabled);
        }

        // Step 7: Setup broadcast receivers
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mBondStateChangedReceiver = new BondStateChangedReceiver();
        registerReceiver(mBondStateChangedReceiver, filter);

        // Step 8: Mark service as started
        setA2dpService(this);
        BluetoothDevice activeDevice = getActiveDevice();
        String deviceAddress = activeDevice != null ?
                activeDevice.getAddress() :
                AdapterService.ACTIVITY_ATTRIBUTION_NO_ACTIVE_DEVICE_ADDRESS;
        mAdapterService.notifyActivityAttributionInfo(getAttributionSource(), deviceAddress);

        // Step 9: Clear active device
        setActiveDevice(null);

        return true;
    }

    @Override
    protected boolean stop() {
        Log.i(TAG, "stop()");
        if (sA2dpService == null) {
            Log.w(TAG, "stop() called before start()");
            return true;
        }

        // Step 9: Clear active device and stop playing audio
        removeActiveDevice(true);

        // Step 8: Mark service as stopped
        BluetoothDevice activeDevice = getActiveDevice();
        String deviceAddress = activeDevice != null ?
                activeDevice.getAddress() :
                AdapterService.ACTIVITY_ATTRIBUTION_NO_ACTIVE_DEVICE_ADDRESS;
        mAdapterService.notifyActivityAttributionInfo(getAttributionSource(), deviceAddress);
        setA2dpService(null);

        // Step 7: Unregister broadcast receivers
        unregisterReceiver(mBondStateChangedReceiver);
        mBondStateChangedReceiver = null;

        // Step 6: Cleanup native interface
        mA2dpNativeInterface.cleanup();
        mA2dpNativeInterface = null;

        // Step 5: Clear codec config
        mA2dpCodecConfig = null;

        // Step 4: Destroy state machines and stop handler thread
        synchronized (mStateMachines) {
            for (A2dpStateMachine sm : mStateMachines.values()) {
                sm.doQuit();
                sm.cleanup();
            }
            mStateMachines.clear();
        }

        if (mStateMachinesThread != null) {
            try {
                mStateMachinesThread.quitSafely();
                mStateMachinesThread.join(SM_THREAD_JOIN_TIMEOUT_MS);
                mStateMachinesThread = null;
            } catch (InterruptedException e) {
                // Do not rethrow as we are shutting down anyway
            }
        }
        // Step 2: Reset maximum number of connected audio devices
        mMaxConnectedAudioDevices = 1;

        // Step 1: Clear AdapterService, A2dpNativeInterface, AudioManager
        mAudioManager = null;
        mA2dpNativeInterface = null;
        mAdapterService = null;

        return true;
    }

    @Override
    protected void cleanup() {
        Log.i(TAG, "cleanup()");
    }

    public static synchronized A2dpService getA2dpService() {
        if (sA2dpService == null) {
            Log.w(TAG, "getA2dpService(): service is null");
            return null;
        }
        if (!sA2dpService.isAvailable()) {
            Log.w(TAG, "getA2dpService(): service is not available");
            return null;
        }
        return sA2dpService;
    }

    private static synchronized void setA2dpService(A2dpService instance) {
        if (DBG) {
            Log.d(TAG, "setA2dpService(): set to: " + instance);
        }
        sA2dpService = instance;
    }

    public boolean connect(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "connect(): " + device);
        }

        if (getConnectionPolicy(device) == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.e(TAG, "Cannot connect to " + device + " : CONNECTION_POLICY_FORBIDDEN");
            return false;
        }
        if (!Utils.arrayContains(mAdapterService.getRemoteUuids(device),
                                         BluetoothUuid.A2DP_SINK)) {
            Log.e(TAG, "Cannot connect to " + device + " : Remote does not have A2DP Sink UUID");
            return false;
        }

        synchronized (mStateMachines) {
            if (!connectionAllowedCheckMaxDevices(device)) {
                // when mMaxConnectedAudioDevices is one, disconnect current device first.
                if (mMaxConnectedAudioDevices == 1) {
                    List<BluetoothDevice> sinks = getDevicesMatchingConnectionStates(
                            new int[] {BluetoothProfile.STATE_CONNECTED,
                                    BluetoothProfile.STATE_CONNECTING,
                                    BluetoothProfile.STATE_DISCONNECTING});
                    for (BluetoothDevice sink : sinks) {
                        if (sink.equals(device)) {
                            Log.w(TAG, "Connecting to device " + device + " : disconnect skipped");
                            continue;
                        }
                        disconnect(sink);
                    }
                } else {
                    Log.e(TAG, "Cannot connect to " + device + " : too many connected devices");
                    return false;
                }
            }
            A2dpStateMachine smConnect = getOrCreateStateMachine(device);
            if (smConnect == null) {
                Log.e(TAG, "Cannot connect to " + device + " : no state machine");
                return false;
            }
            smConnect.sendMessage(A2dpStateMachine.CONNECT);
            return true;
        }
    }

    /**
     * Disconnects A2dp for the remote bluetooth device
     *
     * @param device is the device with which we would like to disconnect a2dp
     * @return true if profile disconnected, false if device not connected over a2dp
     */
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "disconnect(): " + device);
        }

        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.e(TAG, "Ignored disconnect request for " + device + " : no state machine");
                return false;
            }
            sm.sendMessage(A2dpStateMachine.DISCONNECT);
            return true;
        }
    }

    public List<BluetoothDevice> getConnectedDevices() {
        synchronized (mStateMachines) {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (A2dpStateMachine sm : mStateMachines.values()) {
                if (sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
            return devices;
        }
    }

    /**
     * Check whether can connect to a peer device.
     * The check considers the maximum number of connected peers.
     *
     * @param device the peer device to connect to
     * @return true if connection is allowed, otherwise false
     */
    private boolean connectionAllowedCheckMaxDevices(BluetoothDevice device) {
        int connected = 0;
        // Count devices that are in the process of connecting or already connected
        synchronized (mStateMachines) {
            for (A2dpStateMachine sm : mStateMachines.values()) {
                switch (sm.getConnectionState()) {
                    case BluetoothProfile.STATE_CONNECTING:
                    case BluetoothProfile.STATE_CONNECTED:
                        if (Objects.equals(device, sm.getDevice())) {
                            return true;    // Already connected or accounted for
                        }
                        connected++;
                        break;
                    default:
                        break;
                }
            }
        }
        return (connected < mMaxConnectedAudioDevices);
    }

    /**
     * Check whether can connect to a peer device.
     * The check considers a number of factors during the evaluation.
     *
     * @param device the peer device to connect to
     * @param isOutgoingRequest if true, the check is for outgoing connection
     * request, otherwise is for incoming connection request
     * @return true if connection is allowed, otherwise false
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean okToConnect(BluetoothDevice device, boolean isOutgoingRequest) {
        Log.i(TAG, "okToConnect: device " + device + " isOutgoingRequest: " + isOutgoingRequest);
        // Check if this is an incoming connection in Quiet mode.
        if (mAdapterService.isQuietModeEnabled() && !isOutgoingRequest) {
            Log.e(TAG, "okToConnect: cannot connect to " + device + " : quiet mode enabled");
            return false;
        }
        // Check if too many devices
        if (!connectionAllowedCheckMaxDevices(device)) {
            Log.e(TAG, "okToConnect: cannot connect to " + device
                    + " : too many connected devices");
            return false;
        }
        // Check connectionPolicy and accept or reject the connection.
        int connectionPolicy = getConnectionPolicy(device);
        int bondState = mAdapterService.getBondState(device);
        // Allow this connection only if the device is bonded. Any attempt to connect while
        // bonding would potentially lead to an unauthorized connection.
        if (bondState != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "okToConnect: return false, bondState=" + bondState);
            return false;
        } else if (connectionPolicy != BluetoothProfile.CONNECTION_POLICY_UNKNOWN
                && connectionPolicy != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            if (!isOutgoingRequest) {
                HeadsetService headsetService = HeadsetService.getHeadsetService();
                if (headsetService != null && headsetService.okToAcceptConnection(device, true)) {
                    Log.d(TAG, "okToConnect: return false,"
                            + " Fallback connection to allowed HFP profile");
                    headsetService.connect(device);
                    return false;
                }
            }
            // Otherwise, reject the connection if connectionPolicy is not valid.
            Log.w(TAG, "okToConnect: return false, connectionPolicy=" + connectionPolicy);
            return false;
        }
        return true;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            return devices;
        }
        final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            return devices;
        }
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {
                if (!Utils.arrayContains(mAdapterService.getRemoteUuids(device),
                                                 BluetoothUuid.A2DP_SINK)) {
                    continue;
                }
                int connectionState = BluetoothProfile.STATE_DISCONNECTED;
                A2dpStateMachine sm = mStateMachines.get(device);
                if (sm != null) {
                    connectionState = sm.getConnectionState();
                }
                for (int state : states) {
                    if (connectionState == state) {
                        devices.add(device);
                        break;
                    }
                }
            }
            return devices;
        }
    }

    /**
     * Get the list of devices that have state machines.
     *
     * @return the list of devices that have state machines
     */
    @VisibleForTesting
    List<BluetoothDevice> getDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        synchronized (mStateMachines) {
            for (A2dpStateMachine sm : mStateMachines.values()) {
                devices.add(sm.getDevice());
            }
            return devices;
        }
    }

    public int getConnectionState(BluetoothDevice device) {
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return sm.getConnectionState();
        }
    }

    private void removeActiveDevice(boolean forceStopPlayingAudio) {
        synchronized (mActiveSwitchingGuard) {
            BluetoothDevice previousActiveDevice = null;
            synchronized (mStateMachines) {
                if (mActiveDevice == null) return;
                previousActiveDevice = mActiveDevice;
            }

            int prevActiveConnectionState = getConnectionState(previousActiveDevice);

            // As per b/202602952, if we remove the active device due to a disconnection,
            // we need to check if another device is connected and set it active instead.
            // Calling this before any other active related calls has the same effect as
            // a classic active device switch.
            BluetoothDevice fallbackdevice = getFallbackDevice();
            if (fallbackdevice != null && prevActiveConnectionState
                    != BluetoothProfile.STATE_CONNECTED) {
                setActiveDevice(fallbackdevice);
                return;
            }

            // This needs to happen before we inform the audio manager that the device
            // disconnected. Please see comment in updateAndBroadcastActiveDevice() for why.
            updateAndBroadcastActiveDevice(null);

            // Make sure the Audio Manager knows the previous Active device is disconnected.
            // However, if A2DP is still connected and not forcing stop audio for that remote
            // device, the user has explicitly switched the output to the local device and music
            // should continue playing. Otherwise, the remote device has been indeed disconnected
            // and audio should be suspended before switching the output to the local device.
            boolean stopAudio = forceStopPlayingAudio || (prevActiveConnectionState
                        != BluetoothProfile.STATE_CONNECTED);
            mAudioManager.handleBluetoothActiveDeviceChanged(null, previousActiveDevice,
                    BluetoothProfileConnectionInfo.createA2dpInfo(!stopAudio, -1));

            synchronized (mStateMachines) {
                // Make sure the Active device in native layer is set to null and audio is off
                if (!mA2dpNativeInterface.setActiveDevice(null)) {
                    Log.w(TAG, "setActiveDevice(null): Cannot remove active device in native "
                            + "layer");
                }
            }
        }
    }

    /**
     * Process a change in the silence mode for a {@link BluetoothDevice}.
     *
     * @param device the device to change silence mode
     * @param silence true to enable silence mode, false to disable.
     * @return true on success, false on error
     */
    @VisibleForTesting
    public boolean setSilenceMode(BluetoothDevice device, boolean silence) {
        if (DBG) {
            Log.d(TAG, "setSilenceMode(" + device + "): " + silence);
        }
        if (silence && Objects.equals(mActiveDevice, device)) {
            removeActiveDevice(true);
        } else if (!silence && mActiveDevice == null) {
            // Set the device as the active device if currently no active device.
            setActiveDevice(device);
        }
        if (!mA2dpNativeInterface.setSilenceDevice(device, silence)) {
            Log.e(TAG, "Cannot set " + device + " silence mode " + silence + " in native layer");
            return false;
        }
        return true;
    }

    /**
     * Set the active device.
     *
     * @param device the active device
     * @return true on success, otherwise false
     */
    public boolean setActiveDevice(BluetoothDevice device) {
        synchronized (mActiveSwitchingGuard) {
            if (device == null) {
                // Remove active device and continue playing audio only if necessary.
                removeActiveDevice(false);
                return true;
            }

            A2dpStateMachine sm = null;
            BluetoothDevice previousActiveDevice = null;
            synchronized (mStateMachines) {
                if (Objects.equals(device, mActiveDevice)) {
                    Log.i(TAG, "setActiveDevice(" + device + "): current is " + mActiveDevice
                            + " no changed");
                    // returns true since the device is activated even double attempted
                    return true;
                }
                if (DBG) {
                    Log.d(TAG, "setActiveDevice(" + device + "): current is " + mActiveDevice);
                }
                sm = mStateMachines.get(device);
                if (sm == null) {
                    Log.e(TAG, "setActiveDevice(" + device + "): Cannot set as active: "
                              + "no state machine");
                    return false;
                }
                if (sm.getConnectionState() != BluetoothProfile.STATE_CONNECTED) {
                    Log.e(TAG, "setActiveDevice(" + device + "): Cannot set as active: "
                              + "device is not connected");
                    return false;
                }
                previousActiveDevice = mActiveDevice;
            }

            // Switch from one A2DP to another A2DP device
            if (DBG) {
                Log.d(TAG, "Switch A2DP devices to " + device + " from " + previousActiveDevice);
            }
            // This needs to happen before we inform the audio manager that the device
            // disconnected. Please see comment in updateAndBroadcastActiveDevice() for why.
            updateAndBroadcastActiveDevice(device);
            updateLowLatencyAudioSupport(device);

            BluetoothDevice newActiveDevice = null;
            synchronized (mStateMachines) {
                if (!mA2dpNativeInterface.setActiveDevice(device)) {
                    Log.e(TAG, "setActiveDevice(" + device + "): Cannot set as active in native "
                            + "layer");
                    // Remove active device and stop playing audio.
                    removeActiveDevice(true);
                    return false;
                }
                // Send an intent with the active device codec config
                BluetoothCodecStatus codecStatus = sm.getCodecStatus();
                if (codecStatus != null) {
                    broadcastCodecConfig(mActiveDevice, codecStatus);
                }
                newActiveDevice = mActiveDevice;
            }

            // Tasks of Bluetooth are done, and now restore the AudioManager side.
            int rememberedVolume = -1;
            if (mFactory.getAvrcpTargetService() != null) {
                rememberedVolume = mFactory.getAvrcpTargetService()
                        .getRememberedVolumeForDevice(newActiveDevice);
            }
            // Make sure the Audio Manager knows the previous Active device is disconnected,
            // and the new Active device is connected.
            // And inform the Audio Service about the codec configuration
            // change, so the Audio Service can reset accordingly the audio
            // feeding parameters in the Audio HAL to the Bluetooth stack.
            mAudioManager.handleBluetoothActiveDeviceChanged(newActiveDevice, previousActiveDevice,
                    BluetoothProfileConnectionInfo.createA2dpInfo(true, rememberedVolume));
        }
        return true;
    }

    /**
     * Get the active device.
     *
     * @return the active device or null if no device is active
     */
    public BluetoothDevice getActiveDevice() {
        synchronized (mStateMachines) {
            return mActiveDevice;
        }
    }

    private boolean isActiveDevice(BluetoothDevice device) {
        synchronized (mStateMachines) {
            return (device != null) && Objects.equals(device, mActiveDevice);
        }
    }

    /**
     * Set connection policy of the profile and connects it if connectionPolicy is
     * {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED} or disconnects if connectionPolicy is
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}
     *
     * <p> The device should already be paired.
     * Connection policy can be one of:
     * {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN},
     * {@link BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
                "Need BLUETOOTH_PRIVILEGED permission");
        if (DBG) {
            Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        }

        if (!mDatabaseManager.setProfileConnectionPolicy(device, BluetoothProfile.A2DP,
                  connectionPolicy)) {
            return false;
        }
        if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p> The connection policy can be any of:
     * {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN},
     * {@link BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     * @hide
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        return mDatabaseManager
                .getProfileConnectionPolicy(device, BluetoothProfile.A2DP);
    }

    public boolean isAvrcpAbsoluteVolumeSupported() {
        // TODO (apanicke): Add a hook here for the AvrcpTargetService.
        return false;
    }


    public void setAvrcpAbsoluteVolume(int volume) {
        // TODO (apanicke): Instead of using A2DP as a middleman for volume changes, add a binder
        // service to the new AVRCP Profile and have the audio manager use that instead.
        if (mFactory.getAvrcpTargetService() != null) {
            mFactory.getAvrcpTargetService().sendVolumeChanged(volume);
            return;
        }
    }

    boolean isA2dpPlaying(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "isA2dpPlaying(" + device + ")");
        }
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return false;
            }
            return sm.isPlaying();
        }
    }

    /**
     * Gets the current codec status (configuration and capability).
     *
     * @param device the remote Bluetooth device. If null, use the current
     * active A2DP Bluetooth device.
     * @return the current codec status
     * @hide
     */
    public BluetoothCodecStatus getCodecStatus(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "getCodecStatus(" + device + ")");
        }
        synchronized (mStateMachines) {
            if (device == null) {
                device = mActiveDevice;
            }
            if (device == null) {
                return null;
            }
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                return sm.getCodecStatus();
            }
            return null;
        }
    }

    /**
     * Sets the codec configuration preference.
     *
     * @param device the remote Bluetooth device. If null, use the currect
     * active A2DP Bluetooth device.
     * @param codecConfig the codec configuration preference
     * @hide
     */
    public void setCodecConfigPreference(BluetoothDevice device,
                                         BluetoothCodecConfig codecConfig) {
        if (DBG) {
            Log.d(TAG, "setCodecConfigPreference(" + device + "): "
                    + Objects.toString(codecConfig));
        }
        if (device == null) {
            device = mActiveDevice;
        }
        if (device == null) {
            Log.e(TAG, "setCodecConfigPreference: Invalid device");
            return;
        }
        if (codecConfig == null) {
            Log.e(TAG, "setCodecConfigPreference: Codec config can't be null");
            return;
        }
        BluetoothCodecStatus codecStatus = getCodecStatus(device);
        if (codecStatus == null) {
            Log.e(TAG, "setCodecConfigPreference: Codec status is null");
            return;
        }
        mA2dpCodecConfig.setCodecConfigPreference(device, codecStatus, codecConfig);
    }

    /************************************************
     * Savitech LHDC_EXT_API -- START
     ***********************************************/
    public int getLhdcCodecExtendApiVer(BluetoothDevice device, byte[] exApiVer) {
        if (device == null) {
            Log.e(TAG, "Cannot set codec config preference: no active A2DP device");
            return -1;
        }
        return mA2dpCodecConfig.getLhdcCodecExtendApiVer(device, exApiVer);
    }

    public int setLhdcCodecExtendApiConfigAr(BluetoothDevice device, byte[] codecConfig) {
        if (device == null) {
            Log.e(TAG, "Cannot set codec config preference: no active A2DP device");
            return -1;
        }
        return mA2dpCodecConfig.setLhdcCodecExtendApiConfigAr(device, codecConfig);
    }

    public int getLhdcCodecExtendApiConfigAr(BluetoothDevice device, byte[] codecConfig) {
        if (device == null) {
            Log.e(TAG, "Cannot set codec config preference: no active A2DP device");
            return -1;
        }
        return mA2dpCodecConfig.getLhdcCodecExtendApiConfigAr(device, codecConfig);
    }

    public int setLhdcCodecExtendApiConfigMeta(BluetoothDevice device, byte[] codecConfig) {
        if (device == null) {
            Log.e(TAG, "Cannot set codec config preference: no active A2DP device");
            return -1;
        }
        return mA2dpCodecConfig.setLhdcCodecExtendApiConfigMeta(device, codecConfig);
    }

    public int getLhdcCodecExtendApiConfigMeta(BluetoothDevice device, byte[] codecConfig) {
        if (device == null) {
            Log.e(TAG, "Cannot set codec config preference: no active A2DP device");
            return -1;
        }
        return mA2dpCodecConfig.getLhdcCodecExtendApiConfigMeta(device, codecConfig);
    }

    public int getLhdcCodecExtendApiConfigA2dpCodecSpecific(BluetoothDevice device, byte[] codecConfig) {
        if (device == null) {
            Log.e(TAG, "Cannot set codec config preference: no active A2DP device");
            return -1;
        }
        return mA2dpCodecConfig.getLhdcCodecExtendApiConfigA2dpCodecSpecific(device, codecConfig);
    }

    public void setLhdcCodecExtendApiDataGyro2D(BluetoothDevice device, byte[] codecData) {
        if (device == null) {
            Log.e(TAG, "Cannot set codec config preference: no active A2DP device");
            return;
        }
        mA2dpCodecConfig.setLhdcCodecExtendApiDataGyro2D(device, codecData);
    }
    /************************************************
     * Savitech LHDC_EXT_API -- END
     ***********************************************/

    /**
     * Enables the optional codecs.
     *
     * @param device the remote Bluetooth device. If null, use the currect
     * active A2DP Bluetooth device.
     * @hide
     */
    public void enableOptionalCodecs(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "enableOptionalCodecs(" + device + ")");
        }
        if (device == null) {
            device = mActiveDevice;
        }
        if (device == null) {
            Log.e(TAG, "enableOptionalCodecs: Invalid device");
            return;
        }
        if (getSupportsOptionalCodecs(device) != BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED) {
            Log.e(TAG, "enableOptionalCodecs: No optional codecs");
            return;
        }
        BluetoothCodecStatus codecStatus = getCodecStatus(device);
        if (codecStatus == null) {
            Log.e(TAG, "enableOptionalCodecs: Codec status is null");
            return;
        }
        updateLowLatencyAudioSupport(device);
        mA2dpCodecConfig.enableOptionalCodecs(device, codecStatus.getCodecConfig());
    }

    /**
     * Disables the optional codecs.
     *
     * @param device the remote Bluetooth device. If null, use the currect
     * active A2DP Bluetooth device.
     * @hide
     */
    public void disableOptionalCodecs(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "disableOptionalCodecs(" + device + ")");
        }
        if (device == null) {
            device = mActiveDevice;
        }
        if (device == null) {
            Log.e(TAG, "disableOptionalCodecs: Invalid device");
            return;
        }
        if (getSupportsOptionalCodecs(device) != BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED) {
            Log.e(TAG, "disableOptionalCodecs: No optional codecs");
            return;
        }
        BluetoothCodecStatus codecStatus = getCodecStatus(device);
        if (codecStatus == null) {
            Log.e(TAG, "disableOptionalCodecs: Codec status is null");
            return;
        }
        updateLowLatencyAudioSupport(device);
        mA2dpCodecConfig.disableOptionalCodecs(device, codecStatus.getCodecConfig(), getSbcBitrate(device));
    }



    public void setSbcBitrate(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "setSbcBitrate(" + device + ")");
        }
        if (device == null) {
            device = mActiveDevice;
        }
        if (device == null) {
            Log.e(TAG, "disableOptionalCodecs: Invalid device");
            return;
        }
        BluetoothCodecStatus codecStatus = getCodecStatus(device);
        if (codecStatus == null) {
            Log.e(TAG, "disableOptionalCodecs: Codec status is null");
            return;
        }
        updateLowLatencyAudioSupport(device);
        mA2dpCodecConfig.setSbcBitrate(device, codecStatus.getCodecConfig(), getSbcBitrate(device));
    }

    /**
     * Checks whether optional codecs are supported
     *
     * @param device is the remote bluetooth device.
     * @return whether optional codecs are supported. Possible values are:
     * {@link OptionalCodecsSupportStatus#OPTIONAL_CODECS_SUPPORTED},
     * {@link OptionalCodecsSupportStatus#OPTIONAL_CODECS_NOT_SUPPORTED},
     * {@link OptionalCodecsSupportStatus#OPTIONAL_CODECS_SUPPORT_UNKNOWN}.
     */
    public @OptionalCodecsSupportStatus int getSupportsOptionalCodecs(BluetoothDevice device) {
        return mDatabaseManager.getA2dpSupportsOptionalCodecs(device);
    }

    public void setSupportsOptionalCodecs(BluetoothDevice device, boolean doesSupport) {
        int value = doesSupport ? BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED
                : BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED;
        mDatabaseManager.setA2dpSupportsOptionalCodecs(device, value);
    }

    /**
     * Checks whether optional codecs are enabled
     *
     * @param device is the remote bluetooth device
     * @return whether the optional codecs are enabled. Possible values are:
     * {@link OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_ENABLED},
     * {@link OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_DISABLED},
     * {@link OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_UNKNOWN}.
     */
    public @OptionalCodecsPreferenceStatus int getOptionalCodecsEnabled(BluetoothDevice device) {
        int value = mDatabaseManager.getA2dpOptionalCodecsEnabled(device);
        int bitrate = getSbcBitrate(device);
        if( bitrate > 0 && value == BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED ) {
            mDatabaseManager.setA2dpOptionalCodecsEnabled(device, BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED);
            return BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED;
        }
        return value;
    }

    /**
     * Sets the optional codecs to be set to the passed in value
     *
     * @param device is the remote bluetooth device
     * @param value is the new status for the optional codecs. Possible values are:
     * {@link OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_ENABLED},
     * {@link OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_DISABLED},
     * {@link OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_UNKNOWN}.
     */
    public void setOptionalCodecsEnabled(BluetoothDevice device,
            @OptionalCodecsPreferenceStatus int value) {

        int bitrate = getSbcBitrate(device);
        if( bitrate > 0 && value == BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED ) {
            Log.w(TAG, "Unexpected value passed to setOptionalCodecsEnabled:" + value + ", SBC HD enabled");
            return;
        }

        if (value != BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN
                && value != BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED
                && value != BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED) {
            Log.w(TAG, "Unexpected value passed to setOptionalCodecsEnabled:" + value);
            return;
        }
        mDatabaseManager.setA2dpOptionalCodecsEnabled(device, value);
    }

    /**
     * Get dynamic audio buffer size supported type
     *
     * @return support <p>Possible values are
     * {@link BluetoothA2dp#DYNAMIC_BUFFER_SUPPORT_NONE},
     * {@link BluetoothA2dp#DYNAMIC_BUFFER_SUPPORT_A2DP_OFFLOAD},
     * {@link BluetoothA2dp#DYNAMIC_BUFFER_SUPPORT_A2DP_SOFTWARE_ENCODING}.
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public int getDynamicBufferSupport() {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
                "Need BLUETOOTH_PRIVILEGED permission");
        return mAdapterService.getDynamicBufferSupport();
    }

    /**
     * Get dynamic audio buffer size
     *
     * @return BufferConstraints
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public BufferConstraints getBufferConstraints() {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
                "Need BLUETOOTH_PRIVILEGED permission");
        return mAdapterService.getBufferConstraints();
    }

    /**
     * Set dynamic audio buffer size
     *
     * @param codec Audio codec
     * @param value buffer millis
     * @return true if the settings is successful, false otherwise
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean setBufferLengthMillis(int codec, int value) {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
                "Need BLUETOOTH_PRIVILEGED permission");
        return mAdapterService.setBufferLengthMillis(codec, value);
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public int getSbcBitrate(BluetoothDevice device) {
        int rate = 0;
        try {
            rate = mBaikalDatabase.getSbcBitrate(device);
        }
        catch(Exception e) {
            Log.i(TAG, "getSbcBitrate exception:", e);
            return 0;
        }
        //Log.i(TAG, "getSbcBitrate:" + rate);
        return rate;
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public void setSbcBitrate(BluetoothDevice device, int value) {
        Log.i(TAG, "setSbcBitrate :" + value);

        try {
            mBaikalDatabase.setSbcBitrate(device, value);
            updateOptionalCodecsSupport(device);
        }
        catch(Exception e) {
            Log.i(TAG, "setSbcBitrate exception:", e);
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public void updateSbcBitrate(BluetoothDevice device) {
    	
    	int enabled = getOptionalCodecsEnabled(device);
    	if( enabled == BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED ) {
        	setSbcBitrate(device,getSbcBitrate(device));
    	}
    }

    // Handle messages from native (JNI) to Java
    void messageFromNative(A2dpStackEvent stackEvent) {
        Objects.requireNonNull(stackEvent.device,
                               "Device should never be null, event: " + stackEvent);
        synchronized (mStateMachines) {
            BluetoothDevice device = stackEvent.device;
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                if (stackEvent.type == A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
                    switch (stackEvent.valueInt) {
                        case A2dpStackEvent.CONNECTION_STATE_CONNECTING: {
                            updateSbcBitrate(device);
                        }
                        case A2dpStackEvent.CONNECTION_STATE_CONNECTED:
                            // Create a new state machine only when connecting to a device
                            if (!connectionAllowedCheckMaxDevices(device)) {
                                Log.e(TAG, "Cannot connect to " + device
                                        + " : too many connected devices");
                                return;
                            }
                            sm = getOrCreateStateMachine(device);
                            break;
                        default:
                            break;
                    }
                }
            }
            if (sm == null) {
                Log.e(TAG, "Cannot process stack event: no state machine: " + stackEvent);
                return;
            }
            sm.sendMessage(A2dpStateMachine.STACK_EVENT, stackEvent);
        }
    }

    /**
     * The codec configuration for a device has been updated.
     *
     * @param device the remote device
     * @param codecStatus the new codec status
     * @param sameAudioFeedingParameters if true the audio feeding parameters
     * haven't been changed
     */
    @VisibleForTesting
    public void codecConfigUpdated(BluetoothDevice device, BluetoothCodecStatus codecStatus,
                            boolean sameAudioFeedingParameters) {
        // Log codec config and capability metrics
        BluetoothCodecConfig codecConfig = codecStatus.getCodecConfig();
        int metricId = mAdapterService.getMetricId(device);
        BluetoothStatsLog.write(BluetoothStatsLog.BLUETOOTH_A2DP_CODEC_CONFIG_CHANGED,
                mAdapterService.obfuscateAddress(device), codecConfig.getCodecType(),
                codecConfig.getCodecPriority(), codecConfig.getSampleRate(),
                codecConfig.getBitsPerSample(), codecConfig.getChannelMode(),
                codecConfig.getCodecSpecific1(), codecConfig.getCodecSpecific2(),
                codecConfig.getCodecSpecific3(), codecConfig.getCodecSpecific4(), metricId);
        List<BluetoothCodecConfig> codecCapabilities =
                codecStatus.getCodecsSelectableCapabilities();
        for (BluetoothCodecConfig codecCapability : codecCapabilities) {
            BluetoothStatsLog.write(BluetoothStatsLog.BLUETOOTH_A2DP_CODEC_CAPABILITY_CHANGED,
                    mAdapterService.obfuscateAddress(device), codecCapability.getCodecType(),
                    codecCapability.getCodecPriority(), codecCapability.getSampleRate(),
                    codecCapability.getBitsPerSample(), codecCapability.getChannelMode(),
                    codecConfig.getCodecSpecific1(), codecConfig.getCodecSpecific2(),
                    codecConfig.getCodecSpecific3(), codecConfig.getCodecSpecific4(), metricId);
        }

        broadcastCodecConfig(device, codecStatus);

        // Inform the Audio Service about the codec configuration change,
        // so the Audio Service can reset accordingly the audio feeding
        // parameters in the Audio HAL to the Bluetooth stack.
        // Until we are able to detect from device_port_proxy if the config has changed or not,
        // the Bluetooth stack can only disable the audio session and need to ask audioManager to
        // restart the session even if feeding parameter are the same. (sameAudioFeedingParameters
        // is left unused until there)
        if (isActiveDevice(device)) {
            mAudioManager.handleBluetoothActiveDeviceChanged(device, device,
                    BluetoothProfileConnectionInfo.createA2dpInfo(false, -1));
        }
    }

    private A2dpStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                return sm;
            }
            // Limit the maximum number of state machines to avoid DoS attack
            if (mStateMachines.size() >= MAX_A2DP_STATE_MACHINES) {
                Log.e(TAG, "Maximum number of A2DP state machines reached: "
                        + MAX_A2DP_STATE_MACHINES);
                return null;
            }
            if (DBG) {
                Log.d(TAG, "Creating a new state machine for " + device);
            }
            sm = A2dpStateMachine.make(device, this, mA2dpNativeInterface,
                                       mStateMachinesThread.getLooper());
            mStateMachines.put(device, sm);
            return sm;
        }
    }

    // This needs to run before any of the Audio Manager connection functions since
    // AVRCP needs to be aware that the audio device is changed before the Audio Manager
    // changes the volume of the output devices.
    private void updateAndBroadcastActiveDevice(BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "updateAndBroadcastActiveDevice(" + device + ")");
        }

        // Make sure volume has been store before device been remove from active.
        if (mFactory.getAvrcpTargetService() != null) {
            mFactory.getAvrcpTargetService().volumeDeviceSwitched(device);
        }
        synchronized (mStateMachines) {
            mActiveDevice = device;
        }

        BluetoothStatsLog.write(BluetoothStatsLog.BLUETOOTH_ACTIVE_DEVICE_CHANGED,
                BluetoothProfile.A2DP, mAdapterService.obfuscateAddress(device),
                mAdapterService.getMetricId(device));
        Intent intent = new Intent(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        sendBroadcast(intent, BLUETOOTH_CONNECT, Utils.getTempAllowlistBroadcastOptions());
    }

    private void broadcastCodecConfig(BluetoothDevice device, BluetoothCodecStatus codecStatus) {
        if (DBG) {
            Log.d(TAG, "broadcastCodecConfig(" + device + "): " + codecStatus);
        }
        Intent intent = new Intent(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        intent.putExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS, codecStatus);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        sendBroadcast(intent, BLUETOOTH_CONNECT, Utils.getTempAllowlistBroadcastOptions());
    }

    private class BondStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                                           BluetoothDevice.ERROR);
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            Objects.requireNonNull(device, "ACTION_BOND_STATE_CHANGED with no EXTRA_DEVICE");
            bondStateChanged(device, state);
        }
    }

    /**
     * Process a change in the bonding state for a device.
     *
     * @param device the device whose bonding state has changed
     * @param bondState the new bond state for the device. Possible values are:
     * {@link BluetoothDevice#BOND_NONE},
     * {@link BluetoothDevice#BOND_BONDING},
     * {@link BluetoothDevice#BOND_BONDED}.
     */
    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        if (DBG) {
            Log.d(TAG, "Bond state changed for device: " + device + " state: " + bondState);
        }
        // Remove state machine if the bonding for a device is removed
        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            if (sm.getConnectionState() != BluetoothProfile.STATE_DISCONNECTED) {
                return;
            }
        }
        if (mFactory.getAvrcpTargetService() != null) {
            mFactory.getAvrcpTargetService().removeStoredVolumeForDevice(device);
        }
        removeStateMachine(device);
    }

    private void removeStateMachine(BluetoothDevice device) {
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.w(TAG, "removeStateMachine: device " + device
                        + " does not have a state machine");
                return;
            }
            Log.i(TAG, "removeStateMachine: removing state machine for device: " + device);
            sm.doQuit();
            sm.cleanup();
            mStateMachines.remove(device);
        }
    }


    /**
     * Update and initiate optional codec status change to native.
     *
     * @param device the device to change optional codec status
     */
    @VisibleForTesting
    public void updateOptionalCodecsSupport(BluetoothDevice device) {
        int previousSupport = getSupportsOptionalCodecs(device);
        boolean supportsOptional = false;
        boolean hasMandatoryCodec = false;

        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            BluetoothCodecStatus codecStatus = sm.getCodecStatus();
            if (codecStatus != null) {
                for (BluetoothCodecConfig config : codecStatus.getCodecsSelectableCapabilities()) {
                    if (config.isMandatoryCodec()) {
                        hasMandatoryCodec = true;
                    } else {
                        supportsOptional = true;
                    }
                }
            }
        }
        if (!hasMandatoryCodec) {
            // Mandatory codec(SBC) is not selectable. It could be caused by the remote device
            // select codec before native finish get codec capabilities. Stop use this codec
            // status as the reference to support/enable optional codecs.
            Log.i(TAG, "updateOptionalCodecsSupport: Mandatory codec is not selectable.");
            return;
        }

        //if( bitrate > 0 ) supportsOptional = false;

        if (previousSupport == BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN
                || supportsOptional != (previousSupport
                                    == BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED)) {
            setSupportsOptionalCodecs(device, supportsOptional);
        }
        if (supportsOptional) {
            int enabled = getOptionalCodecsEnabled(device);
            switch (enabled) {
                case BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN:
                    // Enable optional codec by default.
                    setOptionalCodecsEnabled(device, BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED);
                    // Fall through intended
                case BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED:
                    enableOptionalCodecs(device);
                    break;
                case BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED:
                    disableOptionalCodecs(device);
                    break;
            }
        } else {
            setSbcBitrate(device);
        }
    }

    /**
     *  Check for low-latency codec support and inform AdapterService
     *
     *  @param device device whose audio low latency will be allowed or disallowed
     */
    @VisibleForTesting
    public void updateLowLatencyAudioSupport(BluetoothDevice device) {
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            BluetoothCodecStatus codecStatus = sm.getCodecStatus();
            boolean lowLatencyAudioAllow = false;
            BluetoothCodecConfig lowLatencyCodec = new BluetoothCodecConfig.Builder()
                    .setCodecType(SOURCE_CODEC_TYPE_OPUS) // remove in U
                    .build();

            if (codecStatus != null
                    && codecStatus.isCodecConfigSelectable(lowLatencyCodec)
                    && getOptionalCodecsEnabled(device)
                            == BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED) {
                lowLatencyAudioAllow = true;
            }
            mAdapterService.allowLowLatencyAudio(lowLatencyAudioAllow, device);
        }
    }

    void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        if (!isAvailable()) {
            Log.w(TAG, "connectionStateChanged: service is not available");
            return;
        }

        if ((device == null) || (fromState == toState)) {
            return;
        }
        if (toState == BluetoothProfile.STATE_CONNECTED) {
            MetricsLogger.logProfileConnectionEvent(BluetoothMetricsProto.ProfileId.A2DP);
        }
        // Set the active device if only one connected device is supported and it was connected
        if (toState == BluetoothProfile.STATE_CONNECTED && (mMaxConnectedAudioDevices == 1)) {
            setActiveDevice(device);
        }
        // Check if the active device is not connected anymore
        if (isActiveDevice(device) && (fromState == BluetoothProfile.STATE_CONNECTED)) {
            setActiveDevice(null);
        }
        // Check if the device is disconnected - if unbond, remove the state machine
        if (toState == BluetoothProfile.STATE_DISCONNECTED) {
            if (mAdapterService.getBondState(device) == BluetoothDevice.BOND_NONE) {
                if (mFactory.getAvrcpTargetService() != null) {
                    mFactory.getAvrcpTargetService().removeStoredVolumeForDevice(device);
                }
                removeStateMachine(device);
            }
        }
    }

    /**
     * Retrieves the most recently connected device in the A2DP connected devices list.
     */
    public BluetoothDevice getFallbackDevice() {
        DatabaseManager dbManager = mAdapterService.getDatabase();
        return dbManager != null ? dbManager
            .getMostRecentlyConnectedDevicesInList(getConnectedDevices())
            : null;
    }

    /**
     * Binder object: must be a static class or memory leak may occur.
     */
    @VisibleForTesting
    static class BluetoothA2dpBinder extends IBluetoothA2dp.Stub
            implements IProfileServiceBinder {
        private A2dpService mService;

        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        private A2dpService getService(AttributionSource source) {
            if (Utils.isInstrumentationTestMode()) {
                return mService;
            }
            if (!Utils.checkServiceAvailable(mService, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(mService, TAG)
                    || !Utils.checkConnectPermissionForDataDelivery(mService, source, TAG)) {
                return null;
            }
            return mService;
        }

        // Savitech - comaptible to API before Android 12.0 
        private A2dpService getServiceLhdc() {
            /*
            if (!Utils.checkCallerIsSystemOrActiveUser()
                    || !Utils.checkServiceAvailable(mService, TAG)) {
                Log.w(TAG, "A2DP call not allowed for non-active user");
                return null;
            }*/

            if (mService != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }

        BluetoothA2dpBinder(A2dpService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @Override
        public void connect(BluetoothDevice device, SynchronousResultReceiver receiver) {
            connectWithAttribution(device, Utils.getCallingAttributionSource(mService), receiver);
        }

        @Override
        public void connectWithAttribution(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                boolean result = false;
                if (service != null) {
                    result = service.connect(device);
                }
                receiver.send(result);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void disconnect(BluetoothDevice device, SynchronousResultReceiver receiver) {
            disconnectWithAttribution(device, Utils.getCallingAttributionSource(mService),
                    receiver);
        }

        @Override
        public void disconnectWithAttribution(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                boolean result = false;
                if (service != null) {
                    result = service.disconnect(device);
                }
                receiver.send(result);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getConnectedDevices(SynchronousResultReceiver receiver) {
            getConnectedDevicesWithAttribution(Utils.getCallingAttributionSource(mService),
                    receiver);
        }

        @Override
        public void getConnectedDevicesWithAttribution(AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                List<BluetoothDevice> connectedDevices = new ArrayList<>(0);
                if (service != null) {
                    connectedDevices = service.getConnectedDevices();
                }
                receiver.send(connectedDevices);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getDevicesMatchingConnectionStates(int[] states,
                SynchronousResultReceiver receiver) {
            getDevicesMatchingConnectionStatesWithAttribution(states,
                    Utils.getCallingAttributionSource(mService), receiver);
        }

        @Override
        public void getDevicesMatchingConnectionStatesWithAttribution(int[] states,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                List<BluetoothDevice> connectedDevices = new ArrayList<>(0);
                if (service != null) {
                    connectedDevices = service.getDevicesMatchingConnectionStates(states);
                }
                receiver.send(connectedDevices);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getConnectionState(BluetoothDevice device, SynchronousResultReceiver receiver) {
            getConnectionStateWithAttribution(device, Utils.getCallingAttributionSource(mService),
                    receiver);
        }

        @Override
        public void getConnectionStateWithAttribution(BluetoothDevice device,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                int state = BluetoothProfile.STATE_DISCONNECTED;
                if (service != null) {
                    state = service.getConnectionState(device);
                }
                receiver.send(state);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setActiveDevice(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                boolean result = false;
                if (service != null) {
                    result = service.setActiveDevice(device);
                }
                receiver.send(result);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getActiveDevice(AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                BluetoothDevice activeDevice = null;
                if (service != null) {
                    activeDevice = service.getActiveDevice();
                }
                receiver.send(activeDevice);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setConnectionPolicy(BluetoothDevice device, int connectionPolicy,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                boolean result = false;
                if (service != null) {
                    result = service.setConnectionPolicy(device, connectionPolicy);
                }
                receiver.send(result);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getConnectionPolicy(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                int result = BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
                if (service != null) {
                    enforceBluetoothPrivilegedPermission(service);
                    result = service.getConnectionPolicy(device);
                }
                receiver.send(result);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void isAvrcpAbsoluteVolumeSupported(SynchronousResultReceiver receiver) {
            // TODO (apanicke): Add a hook here for the AvrcpTargetService.
            receiver.send(false);
        }

        @Override
        public void setAvrcpAbsoluteVolume(int volume, AttributionSource source) {
            A2dpService service = getService(source);
            if (service == null) {
                return;
            }
            enforceBluetoothPrivilegedPermission(service);
            service.setAvrcpAbsoluteVolume(volume);
        }

        @Override
        public void isA2dpPlaying(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                boolean result = false;
                if (service != null) {
                    result = service.isA2dpPlaying(device);
                }
                receiver.send(result);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getCodecStatus(BluetoothDevice device,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                BluetoothCodecStatus codecStatus = null;
                if (service != null) {
                    codecStatus = service.getCodecStatus(device);
                }
                receiver.send(codecStatus);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setCodecConfigPreference(BluetoothDevice device,
                BluetoothCodecConfig codecConfig, AttributionSource source) {
            A2dpService service = getService(source);
            if (service == null) {
                return;
            }
            service.setCodecConfigPreference(device, codecConfig);
        }

        /************************************************
         * Savitech Patch - LHDC_EXT_API Start
         ***********************************************/
        @Override
        public int getLhdcCodecExtendApiVer(BluetoothDevice device, 
                byte[] exApiVer) {
            A2dpService service = getServiceLhdc();
            if (service == null) {
                return -1;
            }
            return service.getLhdcCodecExtendApiVer(device, exApiVer);
        }

        @Override
        public int setLhdcCodecExtendApiConfigAr(BluetoothDevice device, 
                byte[] codecConfig) {
            A2dpService service = getServiceLhdc();
            if (service == null) {
                return -1;
            }
            return service.setLhdcCodecExtendApiConfigAr(device, codecConfig);
        }

        @Override
        public int getLhdcCodecExtendApiConfigAr(BluetoothDevice device,
                byte[] codecConfig) {
            A2dpService service = getServiceLhdc();
            if (service == null) {
                return -1;
            }
            return service.getLhdcCodecExtendApiConfigAr(device, codecConfig);
        }

        @Override
        public int setLhdcCodecExtendApiConfigMeta(BluetoothDevice device,
                byte[] codecConfig) {
            A2dpService service = getServiceLhdc();
            if (service == null) {
                return -1;
            }
            return service.setLhdcCodecExtendApiConfigMeta(device, codecConfig);
        }

        @Override
        public int getLhdcCodecExtendApiConfigMeta(BluetoothDevice device,
                byte[] codecConfig) {
            A2dpService service = getServiceLhdc();
            if (service == null) {
                return -1;
            }
            return service.getLhdcCodecExtendApiConfigMeta(device, codecConfig);
        }

        @Override
        public int getLhdcCodecExtendApiConfigA2dpCodecSpecific(BluetoothDevice device,
                byte[] codecConfig) {
            A2dpService service = getServiceLhdc();
            if (service == null) {
                return -1;
            }
            return service.getLhdcCodecExtendApiConfigA2dpCodecSpecific(device, codecConfig);
        }

        @Override
        public void setLhdcCodecExtendApiDataGyro2D(BluetoothDevice device,
                byte[] codecData) {
            A2dpService service = getServiceLhdc();
            if (service == null) {
                return;
            }
            service.setLhdcCodecExtendApiDataGyro2D(device, codecData);
        }
        /************************************************
         * Savitech Patch - LHDC_EXT_API End
         ***********************************************/


        @Override
        public void enableOptionalCodecs(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getService(source);
            if (service == null) {
                return;
            }
            if (checkCallerTargetSdk(mService, source.getPackageName(),
                        Build.VERSION_CODES.TIRAMISU)) {
                enforceBluetoothPrivilegedPermission(service);
            }
            service.enableOptionalCodecs(device);
        }

        @Override
        public void disableOptionalCodecs(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getService(source);
            if (service == null) {
                return;
            }
            if (checkCallerTargetSdk(mService, source.getPackageName(),
                        Build.VERSION_CODES.TIRAMISU)) {
                enforceBluetoothPrivilegedPermission(service);
            }
            service.disableOptionalCodecs(device);
        }

        @Override
        public void isOptionalCodecsSupported(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                int codecSupport = BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN;
                if (service != null) {
                    if (checkCallerTargetSdk(mService, source.getPackageName(),
                                Build.VERSION_CODES.TIRAMISU)) {
                        enforceBluetoothPrivilegedPermission(service);
                    }
                    codecSupport = service.getSupportsOptionalCodecs(device);
                }
                receiver.send(codecSupport);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void isOptionalCodecsEnabled(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                int optionalCodecEnabled = BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN;
                if (service != null) {
                    if (checkCallerTargetSdk(mService, source.getPackageName(),
                                Build.VERSION_CODES.TIRAMISU)) {
                        enforceBluetoothPrivilegedPermission(service);
                    }
                    optionalCodecEnabled = service.getOptionalCodecsEnabled(device);
                }
                receiver.send(optionalCodecEnabled);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setOptionalCodecsEnabled(BluetoothDevice device, int value,
                AttributionSource source) {
            A2dpService service = getService(source);
            if (service == null) {
                return;
            }
            if (checkCallerTargetSdk(mService, source.getPackageName(),
                        Build.VERSION_CODES.TIRAMISU)) {
                enforceBluetoothPrivilegedPermission(service);
            }
            service.setOptionalCodecsEnabled(device, value);
        }

        @Override
        public void getDynamicBufferSupport(AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                int bufferSupport = BluetoothA2dp.DYNAMIC_BUFFER_SUPPORT_NONE;
                if (service != null) {
                    bufferSupport = service.getDynamicBufferSupport();
                }
                receiver.send(bufferSupport);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getBufferConstraints(AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                BufferConstraints bufferConstraints = null;
                if (service != null) {
                    bufferConstraints = service.getBufferConstraints();
                }
                receiver.send(bufferConstraints);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setBufferLengthMillis(int codec, int value, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                boolean result = false;
                if (service != null) {
                    result = service.setBufferLengthMillis(codec, value);
                }
                receiver.send(result);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getSbcBitrate(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                A2dpService service = getService(source);
                int sbcBitrate = 0;
                if (service != null) {
                    sbcBitrate = service.getSbcBitrate(device);
                }
                receiver.send(sbcBitrate);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setSbcBitrate(BluetoothDevice device, int value, AttributionSource source) {
            A2dpService service = getService(source);
            if (service == null) {
                return;
            }
            service.setSbcBitrate(device, value);
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        ProfileService.println(sb, "mActiveDevice: " + mActiveDevice);
        ProfileService.println(sb, "mMaxConnectedAudioDevices: " + mMaxConnectedAudioDevices);
        if (mA2dpCodecConfig != null) {
            ProfileService.println(sb, "codecConfigPriorities:");
            for (BluetoothCodecConfig codecConfig : mA2dpCodecConfig.codecConfigPriorities()) {
                ProfileService.println(sb, "  " + BluetoothCodecConfig.getCodecName(
                        codecConfig.getCodecType()) + ": "
                        + codecConfig.getCodecPriority());
            }
            ProfileService.println(sb, "mA2dpOffloadEnabled: " + mA2dpOffloadEnabled);
            if (mA2dpOffloadEnabled) {
                ProfileService.println(sb, "codecConfigOffloading:");
                for (BluetoothCodecConfig codecConfig : mA2dpCodecConfig.codecConfigOffloading()) {
                    ProfileService.println(sb, "  " + codecConfig);
                }
            }
        } else {
            ProfileService.println(sb, "mA2dpCodecConfig: null");
        }
        for (A2dpStateMachine sm : mStateMachines.values()) {
            sm.dump(sb);
        }
    }

    public void switchCodecByBufferSize(BluetoothDevice device, boolean isLowLatency) {
        if (getOptionalCodecsEnabled(device) != BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED) {
            return;
        }
        mA2dpCodecConfig.switchCodecByBufferSize(
                device, isLowLatency, getCodecStatus(device).getCodecConfig().getCodecType());
    }
}
