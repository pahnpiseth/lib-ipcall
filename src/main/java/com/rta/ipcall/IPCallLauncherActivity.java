/*
LinphoneLauncherActivity.java
Copyright (C) 2011  Belledonne Communications, Grenoble, France

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.rta.ipcall;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;

import org.linphone.mediastream.Version;

import static android.content.Intent.ACTION_MAIN;

/**
 *
 * Launch Linphone main activity when Service is ready.
 *
 * @author Guillaume Beraudo
 *
 */
public class IPCallLauncherActivity extends Activity {

    private Handler mHandler;
    private ServiceWaitThread mServiceThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Hack to avoid to draw twice LinphoneActivity on tablets
        if (getResources().getBoolean(R.bool.orientation_portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        setContentView(R.layout.launch_screen);

        mHandler = new Handler();



        if (LinphoneService.isReady()) {
            onServiceReady();
        } else {
            // start linphone as background
            startService(new Intent(ACTION_MAIN).setClass(this, LinphoneService.class));
            mServiceThread = new ServiceWaitThread();
            mServiceThread.start();
        }
    }

    protected void onServiceReady() {
        final Class<? extends Activity> classToStart;
        classToStart = CallActivity.class;

        // We need LinphoneService to start bluetoothManager
        if (Version.sdkAboveOrEqual(Version.API11_HONEYCOMB_30)) {
            BluetoothManager.getInstance().initBluetooth();
        }

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent newIntent = new Intent(IPCallLauncherActivity.this, classToStart);
                Intent intent = getIntent();
                String msgShared = null;
                if (intent != null) {
                    String action = intent.getAction();
                    String type = intent.getType();
                    newIntent.setData(intent.getData());
                    if (Intent.ACTION_SEND.equals(action) && type != null) {
                        if ("text/plain".equals(type) && intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
                            msgShared = intent.getStringExtra(Intent.EXTRA_TEXT);
                            newIntent.putExtra("msgShared", msgShared);
                        }
                    }
                }
                startActivity(newIntent);
                finish();
            }
        }, 1000);
    }


    private class ServiceWaitThread extends Thread {
        public void run() {
            while (!LinphoneService.isReady()) {
                try {
                    sleep(30);
                } catch (InterruptedException e) {
                    throw new RuntimeException("waiting thread sleep() has been interrupted");
                }
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    onServiceReady();
                }
            });
            mServiceThread = null;
        }
    }
}

