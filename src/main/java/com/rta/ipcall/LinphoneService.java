/*
LinphoneService.java
Copyright (C) 2010  Belledonne Communications, Grenoble, France

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

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.rta.ipcall.compatibility.Compatibility;
import com.rta.ipcall.ui.OnUpdateUIListener;

import org.linphone.core.LinphoneAddress;
import org.linphone.core.LinphoneAuthInfo;
import org.linphone.core.LinphoneCall;
import org.linphone.core.LinphoneCall.State;
import org.linphone.core.LinphoneCallLog.CallStatus;
import org.linphone.core.LinphoneCore;
import org.linphone.core.LinphoneCoreFactory;
import org.linphone.core.LinphoneCoreListenerBase;
import org.linphone.core.LinphoneProxyConfig;
import org.linphone.core.Reason;
import org.linphone.mediastream.Version;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Linphone service, reacting to Incoming calls, ...<br />
 * <p>
 * Roles include:<ul>
 * <li>Initializing LinphoneManager</li>
 * <li>Starting C libLinphone through LinphoneManager</li>
 * <li>Reacting to LinphoneManager state changes</li>
 * <li>Delegating GUI state change actions to GUI listener</li>
 *
 * @author Guillaume Beraudo
 */
public class LinphoneService extends Service {
    /**
     * Extra key to contains infos about a sip call.<br/>
     */
    public static final String EXTRA_CALL_INFO = "call_info";

    /* Listener needs to be implemented in the Service as it calls
     * setLatestEventInfo and startActivity() which needs a context.
     */
    public static final String START_LINPHONE_LOGS = " ==== Phone information dump ====";
    private static final Class<?>[] mSetFgSign = new Class[]{boolean.class};
    private static final Class<?>[] mStartFgSign = new Class[]{
            int.class, Notification.class};
    private static final Class<?>[] mStopFgSign = new Class[]{boolean.class};
    private static final String TAG = LinphoneService.class.getSimpleName();
    private static LinphoneService instance;
    private static List<OnUpdateUIListener> mUpdateUIListenerList = new ArrayList<>();
    public Handler mHandler = new Handler();
    LinphoneCall callInfo;
    //	private boolean mTestDelayElapsed; // add a timer for testing
    private boolean mTestDelayElapsed = true; // no timer
    private NotificationManager mNM;
    private boolean isInCall = false;
    private int mMsgNotifCount;
    private int foregroundNotifId;
    private Notification foregroundNotif;
    private boolean mDisableRegistrationStatus;
    private LinphoneCoreListenerBase mListener;
    private WindowManager mWindowManager;
    private Application.ActivityLifecycleCallbacks activityCallbacks;
    private View mIncallMiniView;
    private int incallNotifId = -1;
    private IncallIconState mCurrentIncallIconState = IncallIconState.IDLE;
    private Method mSetForeground;
    private Method mStartForeground;
    private Method mStopForeground;
    private Object[] mSetForegroundArgs = new Object[1];
    private Object[] mStartForegroundArgs = new Object[2];
    private Object[] mStopForegroundArgs = new Object[1];
    private String incomingReceivedActivityName;

    public static boolean isReady() {
        return instance != null && instance.mTestDelayElapsed;
    }

    /**
     * @throws RuntimeException service not instantiated
     */
    public static LinphoneService instance() {
        if (isReady()) return instance;

        throw new RuntimeException("LinphoneService not instantiated yet");
    }

    public static void addOnUpdateUIListener(OnUpdateUIListener listener) {
        if (!mUpdateUIListenerList.contains(listener))
            mUpdateUIListenerList.add(listener);
    }

    public static void removeOnUpdateUIListener(OnUpdateUIListener listener) {
        if (mUpdateUIListenerList.contains(listener))
            mUpdateUIListenerList.remove(listener);
    }

    protected void onBackgroundMode() {
        Log.i(TAG, "App has entered background mode");
        if (LinphonePreferences.instance() != null && LinphonePreferences.instance().isFriendlistsubscriptionEnabled()) {
            if (LinphoneManager.isInstanciated())
                LinphoneManager.getInstance().subscribeFriendList(false);
        }
    }

    protected void onForegroundMode() {
        Log.i(TAG, "App has left background mode");
        if (LinphonePreferences.instance() != null && LinphonePreferences.instance().isFriendlistsubscriptionEnabled()) {
            if (LinphoneManager.isInstanciated())
                LinphoneManager.getInstance().subscribeFriendList(true);
        }
    }

    private void setupActivityMonitor() {
        if (activityCallbacks != null) return;
        getApplication().registerActivityLifecycleCallbacks(activityCallbacks = new ActivityMonitor());
    }

    @Deprecated
    public int getMessageNotifCount() {
        return mMsgNotifCount;
    }

    @Deprecated
    public void resetMessageNotifCount() {
        mMsgNotifCount = 0;
    }

    private boolean displayServiceNotification() {
        if (foregroundNotifId != -1 && foregroundNotif != null)
            return true;

        return false;
    }

    public void setMainNotifId(int id) {
        this.foregroundNotifId = id;
    }

    public void setForegroundNotif(Notification notif) {
        this.foregroundNotif = notif;
    }

    public void setIncallNotifId(int id) {
        if (this.incallNotifId != -1)
            mNM.cancel(this.incallNotifId);
        this.incallNotifId = id;
    }

    public void startForegroundNotification() {
        if (displayServiceNotification()) {
            startForegroundCompat(foregroundNotifId, foregroundNotif);
        }
    }

    public void stopForegroundNotification() {
        if (displayServiceNotification()) {
            stopForegroundCompat(foregroundNotifId);
            foregroundNotifId = -1;
            foregroundNotif = null;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onCreate() {
        super.onCreate();

        setupActivityMonitor();

        // Needed in order for the two next calls to succeed, libraries must have been loaded first
        LinphonePreferences.instance().setContext(getBaseContext());
        LinphoneCoreFactory.instance().setLogCollectionPath(getFilesDir().getAbsolutePath());
        boolean isDebugEnabled = LinphonePreferences.instance().isDebugEnabled();
        LinphoneCoreFactory.instance().enableLogCollection(isDebugEnabled);
        LinphoneCoreFactory.instance().setDebugMode(isDebugEnabled, getString(R.string.app_name));

        // Dump some debugging information to the logs
        Log.i(TAG, START_LINPHONE_LOGS);
        dumpDeviceInformation();
        dumpInstalledLinphoneInformation();

        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        LinphoneManager.createAndStart(LinphoneService.this);

        instance = this; // instance is ready once linphone manager has been created
        updateUIByServiceStatus(true);

        LinphoneManager.getLc().addListener(mListener = new LinphoneCoreListenerBase() {
            @Override
            public void callState(LinphoneCore lc, LinphoneCall call, LinphoneCall.State state, String message) {
                if (instance == null) {
                    Log.i(TAG, "Service not ready, discarding call state change to " + state.toString());
                    return;
                }

                if (state == LinphoneCall.State.IncomingReceived) {
                    onIncomingReceived(call);
                }

                if (state == State.CallEnd || state == State.CallReleased || state == State.Error) {
                    dismissCallActivity();
                    isInCall = false;
                }

                if (state == State.CallEnd && call.getCallLog().getStatus() == CallStatus.Missed) {
                    int missedCallCount = LinphoneManager.getLcIfManagerNotDestroyedOrNull().getMissedCallsCount();
                    String body;
                    if (missedCallCount > 1) {
                        body = getString(R.string.missed_calls_notif_body).replace("%i", String.valueOf(missedCallCount));
                    } else {
                        LinphoneAddress address = call.getRemoteAddress();
                        LinphoneContact c = ContactsManager.getInstance().findContactFromAddress(address);
                        if (c != null) {
                            body = c.getFullName();
                        } else {
                            body = address.getDisplayName();
                            if (body == null) {
                                body = address.asStringUriOnly();
                            }
                        }
                    }
                }

                if (state == State.StreamsRunning) {
                    // Workaround bug current call seems to be updated after state changed to streams running
                    if (getResources().getBoolean(R.bool.enable_call_notification))
                        refreshIncallIcon(call);
                } else {
                    if (getResources().getBoolean(R.bool.enable_call_notification))
                        refreshIncallIcon(LinphoneManager.getLc().getCurrentCall());
                }
            }

            @Override
            public void globalState(LinphoneCore lc, LinphoneCore.GlobalState state, String message) {
            }

            @Override
            public void registrationState(LinphoneCore lc, LinphoneProxyConfig cfg, LinphoneCore.RegistrationState state, String smessage) {
                Log.e("IPCall", cfg.getAddress() + smessage);
                if (!LinphoneService.isReady()) {
                    return;
                }

                LinphoneAuthInfo authInfo = lc.findAuthInfo(cfg.getIdentity(), cfg.getRealm(), cfg.getDomain());
                if (state.equals(LinphoneCore.RegistrationState.RegistrationCleared)) {
                    if (lc != null) {
                        if (authInfo != null)
                            lc.removeAuthInfo(authInfo);
                    }
                }

                if(state.equals(LinphoneCore.RegistrationState.RegistrationFailed)) {
                    if (cfg.getError() == Reason.BadCredentials) {
                        Toast.makeText(LinphoneService.this, getString(R.string.error_bad_credentials), Toast.LENGTH_SHORT).show();
                    }
                    if (cfg.getError() == Reason.Unauthorized) {
                        Toast.makeText(LinphoneService.this, getString(R.string.error_unauthorized), Toast.LENGTH_SHORT).show();
                    }
                    if (cfg.getError() == Reason.IOError) {
                        Toast.makeText(LinphoneService.this, getString(R.string.error_io_error), Toast.LENGTH_SHORT).show();
                    }
                }

                if (lc.getProxyConfigList() == null) {
                    LinphoneService.this.registrationState(false, getString(R.string.no_account));
                } else {
                    LinphoneService.this.registrationState(false, getString(R.string.status_not_connected));
                }

                if (lc.getDefaultProxyConfig() != null && lc.getDefaultProxyConfig().equals(cfg)) {
                    String status = getRegistrationStatus(state);
                    if (status.equals(getString(R.string.status_connected)))
                        LinphoneService.this.registrationState(true, status);
                    else {
                        LinphoneService.this.registrationState(false, status);
                    }
                } else if (lc.getDefaultProxyConfig() == null) {
                    String status = getRegistrationStatus(state);
                    if (status.equals(getString(R.string.status_connected)))
                        LinphoneService.this.registrationState(true, status);
                    else {
                        LinphoneService.this.registrationState(false, status);
                    }
                }
            }
        });

        // Retrieve methods to publish notification and keep Android
        // from killing us and keep the audio quality high.
        if (Version.sdkStrictlyBelow(Version.API05_ECLAIR_20)) {
            try {
                mSetForeground = getClass().getMethod("setForeground", mSetFgSign);
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
                Log.e(TAG, "Couldn't find foreground method");
            }
        } else {
            try {
                mStartForeground = getClass().getMethod("startForeground", mStartFgSign);
                mStopForeground = getClass().getMethod("stopForeground", mStopFgSign);
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
                Log.e(TAG, "Couldn't find startForeground or stopForeground");
            }
        }

        getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, ContactsManager.getInstance());

        if (!mTestDelayElapsed) {
            // Only used when testing. Simulates a 5 seconds delay for launching service
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mTestDelayElapsed = true;
                }
            }, 5000);
        }

        //make sure the application will at least wakes up every 10 mn
        Intent intent = new Intent(this, KeepAliveReceiver.class);
        PendingIntent keepAlivePendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_ONE_SHOT);
        AlarmManager alarmManager = ((AlarmManager) this.getSystemService(Context.ALARM_SERVICE));
        Compatibility.scheduleAlarm(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 600000, keepAlivePendingIntent);

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    public void dismissCallActivity() {
        if (mIncallMiniView != null && mIncallMiniView.isShown()) {
            mWindowManager.removeView(mIncallMiniView);
            mIncallMiniView = null;
        }

        if (incallNotifId != -1) {
            mNM.cancel(incallNotifId);
            incallNotifId = -1;
        }

        if (mUpdateUIListenerList != null) {
            for (int i = 0; i < mUpdateUIListenerList.size(); i++) {
                mUpdateUIListenerList.get(i).dismissCallActivity();
            }
        }
    }

    private void registrationState(boolean isConnected, String statusMessage) {
        if (mUpdateUIListenerList != null) {
            for (int i = 0; i < mUpdateUIListenerList.size(); i++) {
                mUpdateUIListenerList.get(i).registrationState(isConnected, statusMessage);
            }
        }
    }

    private void updateUIByServiceStatus(boolean isConnected) {
        if (mUpdateUIListenerList != null) {
            for (int i = 0; i < mUpdateUIListenerList.size(); i++) {
                mUpdateUIListenerList.get(i).updateUIByServiceStatus(isConnected);
            }
        }
    }

    private synchronized void setIncallIcon(IncallIconState state) {
        if (state == mCurrentIncallIconState) return;
        mCurrentIncallIconState = state;

        int notificationTextId = 0;
        int inconId = 0;

        switch (state) {
            case IDLE:
                //mNM.cancel(INCALL_NOTIF_ID);
                return;
            case INCALL:
                //inconId = R.drawable.topbar_call_notification;
                notificationTextId = R.string.incall_notif_active;
                break;
            case PAUSE:
                //inconId = R.drawable.topbar_call_notification;
                notificationTextId = R.string.incall_notif_paused;
                break;
            case VIDEO:
                //inconId = R.drawable.topbar_videocall_notification;
                notificationTextId = R.string.incall_notif_video;
                break;
            default:
                throw new IllegalArgumentException("Unknown state " + state);
        }

        if (LinphoneManager.getLc().getCallsNb() == 0) {
            return;
        }

        LinphoneCall call = LinphoneManager.getLc().getCalls()[0];
        String userName = call.getRemoteAddress().getUserName();
        String domain = call.getRemoteAddress().getDomain();
        String displayName = call.getRemoteAddress().getDisplayName();
        LinphoneAddress address = LinphoneCoreFactory.instance().createLinphoneAddress(userName, domain, null);
        address.setDisplayName(displayName);

        LinphoneContact contact = ContactsManager.getInstance().findContactFromAddress(address);
        Uri pictureUri = contact != null ? contact.getPhotoUri() : null;
        Bitmap bm = null;
        try {
            bm = MediaStore.Images.Media.getBitmap(getContentResolver(), pictureUri);
        } catch (Exception e) {
            bm = BitmapFactory.decodeResource(getResources(), R.drawable.avatar);
        }
        String name = address.getDisplayName() == null ? address.getUserName() : address.getDisplayName();
        //mIncallNotif = Compatibility.createInCallNotification(getApplicationContext(), mNotificationTitle, getString(notificationTextId), inconId, bm, name, mNotifContentIntent);

        //notifyWrapper(INCALL_NOTIF_ID, mIncallNotif);
    }

    public void refreshIncallIcon(LinphoneCall currentCall) {
        LinphoneCore lc = LinphoneManager.getLc();
        if (currentCall != null) {
            if (currentCall.getCurrentParams().getVideoEnabled() && currentCall.cameraEnabled()) {
                // checking first current params is mandatory
                setIncallIcon(IncallIconState.VIDEO);
            } else {
                setIncallIcon(IncallIconState.INCALL);
            }
        } else if (lc.getCallsNb() == 0) {
            setIncallIcon(IncallIconState.IDLE);
        } else if (lc.isInConference()) {
            setIncallIcon(IncallIconState.INCALL);
        } else {
            setIncallIcon(IncallIconState.PAUSE);
        }
    }

    private String getRegistrationStatus(LinphoneCore.RegistrationState state) {
        try {
            if (state == LinphoneCore.RegistrationState.RegistrationOk && LinphoneManager.getLcIfManagerNotDestroyedOrNull().getDefaultProxyConfig().isRegistered()) {
                return getString(R.string.status_connected);
            } else if (state == LinphoneCore.RegistrationState.RegistrationProgress) {
                return getString(R.string.status_in_progress);
            } else if (state == LinphoneCore.RegistrationState.RegistrationFailed) {
                return getString(R.string.status_error);
            } else {
                return getString(R.string.status_not_connected);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return getString(R.string.status_not_connected);
    }

    @Deprecated
    public void addNotification(Intent onClickIntent, int iconResourceID, String title, String message) {
        addCustomNotification(onClickIntent, iconResourceID, title, message, true);
    }

    @Deprecated
    public void addCustomNotification(Intent onClickIntent, int iconResourceID, String title, String message, boolean isOngoingEvent) {
        /*
        PendingIntent notifContentIntent = PendingIntent.getActivity(this, 0, onClickIntent, PendingIntent.FLAG_UPDATE_CURRENT);

		Bitmap bm = null;
		try {
			bm = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
		} catch (Exception e) {
		}
		mCustomNotif = Compatibility.createNotification(this, title, message, iconResourceID, 0, bm, notifContentIntent, isOngoingEvent,notifcationsPriority);

		mCustomNotif.defaults |= Notification.DEFAULT_VIBRATE;
		mCustomNotif.defaults |= Notification.DEFAULT_SOUND;
		mCustomNotif.defaults |= Notification.DEFAULT_LIGHTS;

		notifyWrapper(CUSTOM_NOTIF_ID, mCustomNotif);
		*/
    }

    @Deprecated
    public void removeCustomNotification() {
		/*
		mNM.cancel(CUSTOM_NOTIF_ID);
		resetIntentLaunchedOnNotificationClick();
		*/
    }

    public View getIncallMiniView() {
        return mIncallMiniView;
    }

    public void setIncallMiniView(View view) {
        if (mIncallMiniView != null && mIncallMiniView.isShown())
            mWindowManager.removeView(mIncallMiniView);
        this.mIncallMiniView = view;
    }

    public boolean getIsInCall() {
        return this.isInCall;
    }

    public void setIsInCall(boolean isInCall) {
        this.isInCall = isInCall;
    }

    @Deprecated
    public void displayMessageNotification(String fromSipUri, String fromName, String message) {
		/*
		Intent notifIntent = new Intent(this, LinphoneActivity.class);
		notifIntent.putExtra("GoToChat", true);
		notifIntent.putExtra("ChatContactSipUri", fromSipUri);

		PendingIntent notifContentIntent = PendingIntent.getActivity(this, 0, notifIntent, PendingIntent.FLAG_UPDATE_CURRENT);

		if (fromName == null) {
			fromName = fromSipUri;
		}

		Uri pictureUri = null;
		try {
			LinphoneContact contact = ContactsManager.getInstance().findContactFromAddress(LinphoneCoreFactory.instance().createLinphoneAddress(fromSipUri));
			if (contact != null)
				pictureUri = contact.getThumbnailUri();
		} catch (LinphoneCoreException e1) {
			Log.e(TAG, "Cannot parse from address ", e1);
		}

		Bitmap bm = null;
		if (pictureUri != null) {
			try {
				bm = MediaStore.Images.Media.getBitmap(getContentResolver(), pictureUri);
			} catch (Exception e) {
				bm = BitmapFactory.decodeResource(getResources(), R.drawable.topbar_avatar);
			}
		} else {
			bm = BitmapFactory.decodeResource(getResources(), R.drawable.topbar_avatar);
		}
		//mMsgNotif = Compatibility.createMessageNotification(getApplicationContext(), mMsgNotifCount, fromName, message, bm, notifContentIntent);

		//notifyWrapper(MESSAGE_NOTIF_ID, mMsgNotif);
		*/
    }

    @Deprecated
    public void removeMessageNotification() {
		/*
		mNM.cancel(MESSAGE_NOTIF_ID);
		resetIntentLaunchedOnNotificationClick();
		*/
    }

    void invokeMethod(Method method, Object[] args) {
        try {
            method.invoke(this, args);
        } catch (InvocationTargetException e) {
            // Should not happen.
            e.printStackTrace();
            Log.w(TAG, "Unable to invoke method");
        } catch (IllegalAccessException e) {
            // Should not happen.
            e.printStackTrace();
            Log.w(TAG, "Unable to invoke method");
        }
    }

    /**
     * This is a wrapper around the new startForeground method, using the older
     * APIs if it is not available.
     */
    void startForegroundCompat(int id, Notification notification) {
        // If we have the new startForeground API, then use it.
        if (mStartForeground != null) {
            mStartForegroundArgs[0] = Integer.valueOf(id);
            mStartForegroundArgs[1] = notification;
            invokeMethod(mStartForeground, mStartForegroundArgs);
            return;
        }

        // Fall back on the old API.
        if (mSetForeground != null) {
            mSetForegroundArgs[0] = Boolean.TRUE;
            invokeMethod(mSetForeground, mSetForegroundArgs);
            // continue
        }

        notifyWrapper(id, notification);
    }

    /**
     * This is a wrapper around the new stopForeground method, using the older
     * APIs if it is not available.
     */
    void stopForegroundCompat(int id) {
        // If we have the new stopForeground API, then use it.
        if (mStopForeground != null) {
            mStopForegroundArgs[0] = Boolean.TRUE;
            invokeMethod(mStopForeground, mStopForegroundArgs);
            return;
        }

        // Fall back on the old API.  Note to cancel BEFORE changing the
        // foreground state, since we could be killed at that point.
        mNM.cancel(id);
        if (mSetForeground != null) {
            mSetForegroundArgs[0] = Boolean.FALSE;
            invokeMethod(mSetForeground, mSetForegroundArgs);
        }
    }

    private void dumpDeviceInformation() {
        StringBuilder sb = new StringBuilder();
        sb.append("DEVICE=").append(Build.DEVICE).append("\n");
        sb.append("MODEL=").append(Build.MODEL).append("\n");
        sb.append("MANUFACTURER=").append(Build.MANUFACTURER).append("\n");
        sb.append("SDK=").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("Supported ABIs=");
        for (String abi : Version.getCpuAbis()) {
            sb.append(abi + ", ");
        }
        sb.append("\n");
        Log.i(TAG, sb.toString());
    }

    private void dumpInstalledLinphoneInformation() {
        PackageInfo info = null;
        try {
            info = getPackageManager().getPackageInfo(getPackageName(), 0);
        } catch (NameNotFoundException nnfe) {
        }

        if (info != null) {
            Log.i(TAG, "Linphone version is " + info.versionName + " (" + info.versionCode + ")");
        } else {
            Log.i(TAG, "Linphone version is unknown");
        }
    }

    public void disableNotificationsAutomaticRegistrationStatusContent() {
        mDisableRegistrationStatus = true;
    }

    @Deprecated
    private synchronized void sendNotification(int level, int textId) {
		/*
		String text = getString(textId);
		if (text.contains("%s") && LinphoneManager.getLc() != null) {
			// Test for null lc is to avoid a NPE when Android mess up badly with the String resources.
			LinphoneProxyConfig lpc = LinphoneManager.getLc().getDefaultProxyConfig();
			String id = lpc != null ? lpc.getIdentity() : "";
			text = String.format(text, id);
		}

		Bitmap bm = null;
		try {
			bm = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
		} catch (Exception e) {
		}
		mNotif = Compatibility.createNotification(this, mNotificationTitle, text, R.drawable.status_level, 0, bm, mNotifContentIntent, true,notifcationsPriority);
		notifyWrapper(NOTIF_ID, mNotif);
		*/
    }

    /**
     * Wrap notifier to avoid setting the linphone icons while the service
     * is stopping. When the (rare) bug is triggered, the linphone icon is
     * present despite the service is not running. To trigger it one could
     * stop linphone as soon as it is started. Transport configured with TLS.
     */
    private synchronized void notifyWrapper(int id, Notification notification) {
        if (instance != null && notification != null) {
            mNM.notify(id, notification);
        } else {
            Log.i(TAG, "Service not ready, discarding notification");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (getResources().getBoolean(R.bool.kill_service_with_task_manager)) {
            Log.d(TAG, "Task removed, stop service");

            // If push is enabled, don't unregister account, otherwise do unregister
            if (LinphonePreferences.instance().isPushNotificationEnabled()) {
                LinphoneManager.getLc().setNetworkReachable(false);
            }
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public synchronized void onDestroy() {

        if (activityCallbacks != null) {
            getApplication().unregisterActivityLifecycleCallbacks(activityCallbacks);
            activityCallbacks = null;
        }

        updateUIByServiceStatus(false);

        dismissCallActivity();
        LinphoneCore lc = LinphoneManager.getLcIfManagerNotDestroyedOrNull();
        if (lc != null) {
            lc.removeListener(mListener);
        }

        //Exit form ipcall user
        LinphonePreferences prefs = LinphonePreferences.instance();
        int count = prefs.getAccountCount();
        try {
            if (count > 0) { //Leave 1 account alive
                prefs.deleteAccount(count - 1); //Delete last account
                android.util.Log.e("IPCall", "App IPCall account removed!");
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }

        instance = null;
        getContentResolver().unregisterContentObserver(ContactsManager.getInstance());
        LinphoneManager.destroy();

        // Make sure our notification is gone.
        stopForegroundNotification();

        if (incallNotifId != -1)
            mNM.cancel(incallNotifId);

        super.onDestroy();
    }

    @Deprecated
    private void resetIntentLaunchedOnNotificationClick() {
		/*
		Intent notifIntent = new Intent(this, incomingReceivedActivity);
		mNotifContentIntent = PendingIntent.getActivity(this, 0, notifIntent, PendingIntent.FLAG_UPDATE_CURRENT);
		*/

		/*if (mNotif != null) {
			mNotif.contentIntent = mNotifContentIntent;
		}
		notifyWrapper(NOTIF_ID, mNotif);*/
    }

    protected void onIncomingReceived(LinphoneCall incomingCall) {
        //wakeup linphone
        if (LinphoneManager.isAllowIncomingCall()) {
            if (mUpdateUIListenerList != null) {
                for (int i = 0; i < mUpdateUIListenerList.size(); i++) {
                    mUpdateUIListenerList.get(i).launchIncomingCallActivity();
                }
            }
        }
        else {
            LinphoneManager.getLc().declineCall(incomingCall, Reason.Busy);
            Log.e(TAG, "This user isn't allowed to receive IpCall");
        }
    }

    public LinphoneCall getExtraValue(String key) {
        if (key.equals(EXTRA_CALL_INFO)) {
            if (this.callInfo != null)
                return this.callInfo;
            else
                return LinphoneManager.getLc().getCurrentCall();
        }
        return null;
    }

    public void tryingNewOutgoingCallButAlreadyInCall() {
    }

    public void tryingNewOutgoingCallButCannotGetCallParameters() {
    }

    public void tryingNewOutgoingCallButWrongDestinationAddress() {
    }

    public void onCallEncryptionChanged(final LinphoneCall call, final boolean encrypted,
                                        final String authenticationToken) {
    }

    public Intent buildCallUiIntent(Context ctxt, LinphoneCall callInfo, String UI_CALL_PACKAGE, String action) {
        // Resolve the package to handle call.

        if (UI_CALL_PACKAGE == null) {
            UI_CALL_PACKAGE = ctxt.getPackageName();
			/*
			try {
				Map<String, DynActivityPlugin> callsUis = ExtraPlugins.getDynActivityPlugins(ctxt, SipManager.ACTION_SIP_CALL_FLOATING_UI);
				String preferredPackage  = SipConfigManager.getPreferenceStringValue(ctxt, SipConfigManager.CALL_UI_PACKAGE, UI_CALL_PACKAGE);
				String packageName = null;
				boolean foundPref = false;
				for(String activity : callsUis.keySet()) {
					packageName = activity.split("/")[0];
					if(preferredPackage.equalsIgnoreCase(packageName)) {
						UI_CALL_PACKAGE = packageName;
						foundPref = true;
						break;
					}
				}
				if(!foundPref && !TextUtils.isEmpty(packageName)) {
					UI_CALL_PACKAGE = packageName;
				}
			}catch(Exception e) {
				Log.e(TAG, THIS_FILE, "Error while resolving package", e);
			}
			*/
        }
        this.callInfo = callInfo;
        Intent intent = new Intent(action);
        intent.setPackage(UI_CALL_PACKAGE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    private enum IncallIconState {INCALL, PAUSE, VIDEO, IDLE}

    /*Believe me or not, but knowing the application visibility state on Android is a nightmare.
    After two days of hard work I ended with the following class, that does the job more or less reliabily.
    */
    class ActivityMonitor implements Application.ActivityLifecycleCallbacks {
        private ArrayList<Activity> activities = new ArrayList<Activity>();
        private boolean mActive = false;
        private int mRunningActivities = 0;
        private InactivityChecker mLastChecker;

        @Override
        public synchronized void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            Log.i(TAG, "Activity created:" + activity);
            if (!activities.contains(activity))
                activities.add(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
            Log.i(TAG, "Activity started:" + activity);
        }

        @Override
        public synchronized void onActivityResumed(Activity activity) {
            Log.i(TAG, "Activity resumed:" + activity);
            if (activities.contains(activity)) {
                mRunningActivities++;
                Log.i(TAG, "runningActivities=" + mRunningActivities);
                checkActivity();
            }

        }

        @Override
        public synchronized void onActivityPaused(Activity activity) {
            Log.i(TAG, "Activity paused:" + activity);
            if (activities.contains(activity)) {
                mRunningActivities--;
                Log.i(TAG, "runningActivities=" + mRunningActivities);
                checkActivity();
            }

        }

        @Override
        public void onActivityStopped(Activity activity) {
            Log.i(TAG, "Activity stopped:" + activity);
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

        }

        @Override
        public synchronized void onActivityDestroyed(Activity activity) {
            Log.i(TAG, "Activity destroyed:" + activity);
            if (activities.contains(activity)) {
                activities.remove(activity);
            }
        }

        void startInactivityChecker() {
            if (mLastChecker != null) mLastChecker.cancel();
            LinphoneService.this.mHandler.postDelayed(
                    (mLastChecker = new InactivityChecker()), 2000);
        }

        void checkActivity() {
            if (mRunningActivities == 0) {
                if (mActive) startInactivityChecker();
            } else if (mRunningActivities > 0) {
                if (!mActive) {
                    mActive = true;
                    LinphoneService.this.onForegroundMode();
                }
                if (mLastChecker != null) {
                    mLastChecker.cancel();
                    mLastChecker = null;
                }
            }
        }

        class InactivityChecker implements Runnable {
            private boolean isCanceled;

            public void cancel() {
                isCanceled = true;
            }

            @Override
            public void run() {
                synchronized (LinphoneService.this) {
                    if (!isCanceled) {
                        if (ActivityMonitor.this.mRunningActivities == 0 && mActive) {
                            mActive = false;
                            LinphoneService.this.onBackgroundMode();
                        }
                    }
                }
            }
        }
    }
}

