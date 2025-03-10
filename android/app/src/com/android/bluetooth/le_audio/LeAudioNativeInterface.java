/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

/*
 * Defines the native interface that is used by state machine/service to
 * send or receive messages from the native stack. This file is registered
 * for the native methods in the corresponding JNI C++ file.
 */
package com.android.bluetooth.le_audio;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * LeAudio Native Interface to/from JNI.
 */
public class LeAudioNativeInterface {
    private static final String TAG = "LeAudioNativeInterface";
    private static final boolean DBG = false;
    private BluetoothAdapter mAdapter;

    @GuardedBy("INSTANCE_LOCK")
    private static LeAudioNativeInterface sInstance;
    private static final Object INSTANCE_LOCK = new Object();

    static {
        classInitNative();
    }

    private LeAudioNativeInterface() {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter == null) {
            Log.wtf(TAG, "No Bluetooth Adapter Available");
        }
    }

    /**
     * Get singleton instance.
     */
    public static LeAudioNativeInterface getInstance() {
        synchronized (INSTANCE_LOCK) {
            if (sInstance == null) {
                sInstance = new LeAudioNativeInterface();
            }
            return sInstance;
        }
    }

    /**
     * Set singleton instance.
     */
    @VisibleForTesting
    static void setInstance(LeAudioNativeInterface instance) {
        synchronized (INSTANCE_LOCK) {
            sInstance = instance;
        }
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            return Utils.getBytesFromAddress("00:00:00:00:00:00");
        }
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private void sendMessageToService(LeAudioStackEvent event) {
        LeAudioService service = LeAudioService.getLeAudioService();
        if (service != null) {
            service.messageFromNative(event);
        } else {
            Log.e(TAG, "Event ignored, service not available: " + event);
        }
    }

    private BluetoothDevice getDevice(byte[] address) {
        return mAdapter.getRemoteDevice(address);
    }

    // Callbacks from the native stack back into the Java framework.
    // All callbacks are routed via the Service which will disambiguate which
    // state machine the message should be routed to.
    private void onInitialized() {
        LeAudioStackEvent event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_NATIVE_INITIALIZED);

        if (DBG) {
            Log.d(TAG, "onInitialized: " + event);
        }
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onConnectionStateChanged(int state, byte[] address) {
        LeAudioStackEvent event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.device = getDevice(address);
        event.valueInt1 = state;

        if (DBG) {
            Log.d(TAG, "onConnectionStateChanged: " + event);
        }
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onGroupStatus(int groupId, int groupStatus) {
        LeAudioStackEvent event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        event.valueInt1 = groupId;
        event.valueInt2 = groupStatus;

        if (DBG) {
            Log.d(TAG, "onGroupStatus: " + event);
        }
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onGroupNodeStatus(byte[] address, int groupId, int nodeStatus) {
        LeAudioStackEvent event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        event.valueInt1 = groupId;
        event.valueInt2 = nodeStatus;
        event.device = getDevice(address);

        if (DBG) {
            Log.d(TAG, "onGroupNodeStatus: " + event);
        }
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onAudioConf(int direction, int groupId, int sinkAudioLocation,
                             int sourceAudioLocation, int availableContexts) {
        LeAudioStackEvent event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        event.valueInt1 = direction;
        event.valueInt2 = groupId;
        event.valueInt3 = sinkAudioLocation;
        event.valueInt4 = sourceAudioLocation;
        event.valueInt5 = availableContexts;

        if (DBG) {
            Log.d(TAG, "onAudioConf: " + event);
        }
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onSinkAudioLocationAvailable(byte[] address, int sinkAudioLocation) {
        LeAudioStackEvent event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_SINK_AUDIO_LOCATION_AVAILABLE);
        event.device = getDevice(address);
        event.valueInt1 = sinkAudioLocation;

        if (DBG) {
            Log.d(TAG, "onSinkAudioLocationAvailable: " + event);
        }
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onAudioLocalCodecCapabilities(
                            BluetoothLeAudioCodecConfig[] localInputCodecCapabilities,
                            BluetoothLeAudioCodecConfig[] localOutputCodecCapabilities) {
        LeAudioStackEvent event =
                new LeAudioStackEvent(
                        LeAudioStackEvent.EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED);

        event.valueCodecList1 = Arrays.asList(localInputCodecCapabilities);
        event.valueCodecList2 = Arrays.asList(localOutputCodecCapabilities);

        if (DBG) {
            Log.d(TAG, "onAudioLocalCodecCapabilities: " + event);
        }
        sendMessageToService(event);
    }

    @VisibleForTesting
    void onAudioGroupCodecConf(int groupId, BluetoothLeAudioCodecConfig inputCodecConfig,
                            BluetoothLeAudioCodecConfig outputCodecConfig,
                            BluetoothLeAudioCodecConfig [] inputSelectableCodecConfig,
                            BluetoothLeAudioCodecConfig [] outputSelectableCodecConfig) {
        LeAudioStackEvent event =
                new LeAudioStackEvent(
                        LeAudioStackEvent.EVENT_TYPE_AUDIO_GROUP_CODEC_CONFIG_CHANGED);

        event.valueInt1 = groupId;
        event.valueCodec1 = inputCodecConfig;
        event.valueCodec2 = outputCodecConfig;
        event.valueCodecList1 = Arrays.asList(inputSelectableCodecConfig);
        event.valueCodecList2 = Arrays.asList(outputSelectableCodecConfig);

        if (DBG) {
            Log.d(TAG, "onAudioGroupCodecConf: " + event);
        }
        sendMessageToService(event);
    }

    /**
     * Initializes the native interface.
     *
     * priorities to configure.
     */
    public void init(BluetoothLeAudioCodecConfig[] codecConfigOffloading) {
        initNative(codecConfigOffloading);
    }

    /**
     * Cleanup the native interface.
     */
    public void cleanup() {
        cleanupNative();
    }

    /**
     * Initiates LeAudio connection to a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    public boolean connectLeAudio(BluetoothDevice device) {
        return connectLeAudioNative(getByteAddress(device));
    }

    /**
     * Disconnects LeAudio from a remote device.
     *
     * @param device the remote device
     * @return true on success, otherwise false.
     */
    public boolean disconnectLeAudio(BluetoothDevice device) {
        return disconnectLeAudioNative(getByteAddress(device));
    }

    /**
     * Add new Node into a group.
     * @param groupId group identifier
     * @param device remote device
     */
     public boolean groupAddNode(int groupId, BluetoothDevice device) {
        return groupAddNodeNative(groupId, getByteAddress(device));
    }

    /**
     * Add new Node into a group.
     * @param groupId group identifier
     * @param device remote device
     */
    public boolean groupRemoveNode(int groupId, BluetoothDevice device) {
        return groupRemoveNodeNative(groupId, getByteAddress(device));
    }

    /**
     * Set active group.
     * @param groupId group ID to set as active
     */
    public void groupSetActive(int groupId) {
        groupSetActiveNative(groupId);
    }

    /**
     * Set codec config preference.
     * @param groupId group ID for the preference
     * @param inputCodecConfig input codec configuration
     * @param outputCodecConfig output codec configuration
     */
    public void setCodecConfigPreference(int groupId,
            BluetoothLeAudioCodecConfig inputCodecConfig,
            BluetoothLeAudioCodecConfig outputCodecConfig) {
        setCodecConfigPreferenceNative(groupId, inputCodecConfig, outputCodecConfig);
    }

    /**
     * Set content control id (Ccid) along with context type.
     * @param ccid content control id
     * @param contextType assigned contextType
     */
    public void setCcidInformation(int ccid, int contextType) {
        if (DBG) {
            Log.d(TAG, "setCcidInformation ccid: " + ccid + " context type: " + contextType);
        }
        setCcidInformationNative(ccid, contextType);
    }

    /**
     * Set in call call flag.
     * @param inCall true when device in call (any state), false otherwise
     */
    public void setInCall(boolean inCall) {
        if (DBG) {
            Log.d(TAG, "setInCall inCall: " + inCall);
        }
        setInCallNative(inCall);
    }

    // Native methods that call into the JNI interface
    private static native void classInitNative();
    private native void initNative(BluetoothLeAudioCodecConfig[] codecConfigOffloading);
    private native void cleanupNative();
    private native boolean connectLeAudioNative(byte[] address);
    private native boolean disconnectLeAudioNative(byte[] address);
    private native boolean groupAddNodeNative(int groupId, byte[] address);
    private native boolean groupRemoveNodeNative(int groupId, byte[] address);
    private native void groupSetActiveNative(int groupId);
    private native void setCodecConfigPreferenceNative(int groupId,
            BluetoothLeAudioCodecConfig inputCodecConfig,
            BluetoothLeAudioCodecConfig outputCodecConfig);
    private native void setCcidInformationNative(int ccid, int contextType);
    private native void setInCallNative(boolean inCall);
}
