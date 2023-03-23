/*
 * Copyright (C) 2019 BaikalOS
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

package com.android.bluetooth.btservice.storage;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.UserHandle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import android.util.Slog;

public class BaikalDatabase extends ContentObserver {

    private static final String TAG = "Baikal.BtService";

    private static ContentResolver mResolver;
    private static Handler mHandler;
    private static Context mContext;
   	private static Object _staticLock = new Object();

    private static String mSbcBitrateString;

    private static Object mBtDatabaseLock = new Object();

    private static HashMap<String, Integer> mBtDatabase = null; 

    private static final TextUtils.StringSplitter mBtDbSplitter = new TextUtils.SimpleStringSplitter('|');


    public BaikalDatabase(Handler handler, Context context) {
        super(handler);
	    mHandler = handler;
        mContext = context;
        mResolver = context.getContentResolver();

        try {
                mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BAIKALOS_SBC_BITRATE),
                    false, this);
        } catch( Exception e ) {
        }

        updateConstants();
    }

    public void cleanup() {
        if( mResolver != null ) {
            mResolver.unregisterContentObserver(this);
        }
    }

    public void factoryReset() {
        if( mResolver != null ) {
            Settings.Global.putString(mResolver,Settings.Global.BAIKALOS_SBC_BITRATE,"");
        }
    }

    private void updateConstants() {
        synchronized(mBtDatabaseLock) {
            updateSbcBitratesLocked();
        }
    }

    public boolean setSbcBitrate(BluetoothDevice device, int value) {

        synchronized(mBtDatabaseLock) {
            Integer prev = mBtDatabase.get(device.getAddress());
            if( prev != null &&  prev.equals(Integer.valueOf(value)) ) return false;
            Slog.i(TAG, "a2dp: SBC set bitrate: device=" + device + ", rate=" + value);
            mBtDatabase.put(device.getAddress(),Integer.valueOf(value));
            saveSbcBitratesLocked();
        }
        return true;
    }

    public int getSbcBitrate(BluetoothDevice device) {
        synchronized(mBtDatabaseLock) {
            if( mBtDatabase == null ) {
                updateSbcBitratesLocked();
            }
            if( !mBtDatabase.containsKey(device.getAddress()) ) {
                Slog.i(TAG, "a2dp: SBC bitrate is not set: device=" + device);
                return 0;
            }
            int rate = mBtDatabase.get(device.getAddress()).intValue();
            Slog.i(TAG, "a2dp: SBC bitrate:device=" + device + ", rate="  + rate);
            return rate;
        }
    }

    private void updateSbcBitratesLocked() {

        mBtDatabase = new HashMap<String,Integer> ();

        String sbcBitrateString = Settings.Global.getString(mResolver,Settings.Global.BAIKALOS_SBC_BITRATE);

        if( sbcBitrateString == null ) return;
        if( sbcBitrateString.equals(mSbcBitrateString) ) return;

        mSbcBitrateString = sbcBitrateString;

        try {
            mBtDbSplitter.setString(mSbcBitrateString);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Bad mSbcBitrateString settings :" + mSbcBitrateString, e);
            return ;
        }

        for(String deviceString:mBtDbSplitter) {

            KeyValueListParser parser = new KeyValueListParser(',');

            try {
                parser.setString(deviceString);
                String address = parser.getString("addr",null);
                if( address == null || address.equals("") ) continue;
                int bitrate = parser.getInt("sbcbr",0);
                mBtDatabase.put(address,bitrate);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad deviceString :" + deviceString, e);
                continue;
            }
        }
    }

    private void saveSbcBitratesLocked() {
        String val = "";    

        for(HashMap.Entry<String, Integer> entry : mBtDatabase.entrySet()) {
            String entryString = "addr=" + entry.getKey().toString() + "," + "sbcbr=" +  entry.getValue().toString();
            if( entryString != null ) val += entryString + "|";
        } 

        if( mSbcBitrateString != null && mSbcBitrateString.equals(val) ) return;
        mSbcBitrateString = val;
        Settings.Global.putString(mResolver,Settings.Global.BAIKALOS_SBC_BITRATE,val);
    }
}
