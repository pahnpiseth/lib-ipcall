package vn.rta.ipcall.ui;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.rta.ipcall.LinphoneManager;
import com.rta.ipcall.LinphoneService;
import com.rta.ipcall.LinphoneUtils;

import org.linphone.core.LinphoneCall;
import org.linphone.core.LinphoneCallParams;
import org.linphone.core.LinphoneCore;
import org.linphone.core.LinphoneCoreListenerBase;
import org.linphone.core.Reason;
import org.linphone.mediastream.Log;

import java.util.List;

import vn.rta.survey.android.R;

public class IncomingIPCallActivity extends AppCompatActivity {
    Toolbar mToolbar;
    ImageView mAvatar, mAvatarPlaceHolder;
    RelativeLayout btnAcceptCall, btnDeclineCall;
    ImageButton btnMicro, btnDialpad, btnSpeaker, btnAddCall, btnMinimize;
    TextView txtCallNumber;
    CallLoadingIndicatorView avLoading;
    private WindowManager wm;
    private View mView;
    int screenWidth, screenHeight;
    LinphoneCall linphoneCall;
    private Numpad numpad;
    boolean isAlreadyAcceptedOrDenied = false, isInCall = false;
    boolean isMicMuted = false, isSpeakerEnabled = false;
    private Notification incallNotification;
    private final int incallNotifId = 2212;

    public static final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,//width
            WindowManager.LayoutParams.WRAP_CONTENT,//height
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT);

    // Gets an instance of the NotificationManager service
    NotificationManager mNotifyMgr;



    private LinphoneCoreListenerBase mListener;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_ipcall);

        initUI();
        avLoading.smoothToShow();
        setupEventHandler();
        LinphoneCore lc = LinphoneManager.getLcIfManagerNotDestroyedOrNull();
        if (lc != null) {
            lc.addListener(mListener);
        }

        isInCall = LinphoneService.instance().getIsInCall();
        if (isInCall) {
            setAddCallButtonEnabled(true);
            avLoading.smoothToHide();
            btnAcceptCall.setVisibility(View.GONE);
            isAlreadyAcceptedOrDenied = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinphoneCore lc = LinphoneManager.getLcIfManagerNotDestroyedOrNull();

        isAlreadyAcceptedOrDenied = false;
        linphoneCall = null;

        // Only one call ringing at a time is allowed
        if (lc != null) {
            List<LinphoneCall> calls = LinphoneUtils.getLinphoneCalls(LinphoneManager.getLc());
            for (LinphoneCall call : calls) {
                if (LinphoneCall.State.IncomingReceived == call.getState()) {
                    linphoneCall = call;
                    break;
                }
            }
        }
        if (linphoneCall == null) {
            //No incoming call
            List<LinphoneCall> callList =  LinphoneUtils.getRunningOrPausedCalls(LinphoneManager.getLc());
            if (!callList.isEmpty())
                linphoneCall = callList.get(0);
            else {
                Log.d("Couldn't find incoming call");
                this.finish();
                return;
            }
        }

        txtCallNumber.setText(linphoneCall.getRemoteAddress().getDisplayName());
    }

    private void setupEventHandler() {
        btnAcceptCall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!btnAddCall.isEnabled()) {
                    //Enable bottom actionbar button
                    setAddCallButtonEnabled(true);
                }
                answer();
            }
        });

        btnDeclineCall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAddCallButtonEnabled(false);
                if (isInCall) {
                    hangUp();
                } else {
                    decline();
                }
            }
        });

        //Bottom actionbar buttons
        btnMicro.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (LinphoneService.isReady()) {
                    if (isMicMuted) {
                        //Enable micro
                        btnMicro.setImageResource(R.drawable.ic_mic_white);
                        isMicMuted = false;
                    } else {
                        //Mute micro
                        btnMicro.setImageResource(R.drawable.ic_mic_off_white);
                        isMicMuted = true;
                    }
                    LinphoneManager.getInstance().setEnableMicro(isMicMuted);
                }
            }
        });

        btnDialpad.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideOrDisplayNumpad();
            }
        });

        btnSpeaker.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (LinphoneService.isReady()) {
                    if (isSpeakerEnabled) {
                        //Disable speaker, route audio to receiver
                        LinphoneManager.getInstance().routeAudioToReceiver();
                        LinphoneManager.getLc().enableSpeaker(false);
                        btnSpeaker.setImageResource(R.drawable.ic_low_volume_white);
                        isSpeakerEnabled = false;
                    } else {
                        //Enable speaker, route audio to speaker
                        LinphoneManager.getInstance().routeAudioToSpeaker();
                        LinphoneManager.getLc().enableSpeaker(true);
                        btnSpeaker.setImageResource(R.drawable.ic_volume_white);
                        isSpeakerEnabled = true;
                    }
                } else
                    Toast.makeText(getApplicationContext(), "Speaker is not working!", Toast.LENGTH_SHORT).show();
            }
        });

        btnAddCall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });

        btnMinimize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(IncomingIPCallActivity.this)) {
                    Toast.makeText(IncomingIPCallActivity.this, "Please grant permission for the functionality and try to open it again after permission was granted.", Toast.LENGTH_LONG).show();
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } else {
                    if (mView == null) {
                        mView = getLayoutInflater().inflate(R.layout.sip_call_mini_horizontal, null);
                    }

                    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    params.y = getStatusBarHeight();
                    params.height = getStatusBarHeight();

                    //At view and notifications
                    if (mView != null) {
                        LinphoneService.instance().setIncallMiniView(mView);
                        int count = 0;
                        while (!mView.isShown() && count < 5) {
                            wm.addView(mView, params);
                            count++;
                        }
                    }
                    LinphoneService.instance().setIncallNotifId(incallNotifId);
                    mNotifyMgr.notify(incallNotifId, incallNotification);
                }
            }
        });

        mView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //Return back to call screen.
                Intent intent = new Intent(IncomingIPCallActivity.this, IncomingIPCallActivity.class);
                startActivity(intent);
            }
        });

        mListener = new LinphoneCoreListenerBase() {
            @Override
            public void callState(LinphoneCore lc, LinphoneCall call, LinphoneCall.State state, String message) {
                if (state == LinphoneCall.State.IncomingReceived && LinphoneManager.isAllowIncomingCall()) {
                    //Another call?
                }

                if (state == LinphoneCall.State.CallEnd || state == LinphoneCall.State.CallReleased || state == LinphoneCall.State.Error) {
                    dismissCallActivity();
                }

                if (state == LinphoneCall.State.Connected || state == LinphoneCall.State.StreamsRunning) {
                    avLoading.smoothToHide();
                    btnAcceptCall.setVisibility(View.GONE);
                    isAlreadyAcceptedOrDenied = true;
                    LinphoneService.instance().setIsInCall(true);
                }
            }
        };
    }

    public void setAddCallButtonEnabled(boolean isEnabled) {
        btnAddCall.setEnabled(isEnabled);
        btnAddCall.setAlpha(isEnabled ? 1.0f : 0.5f);
    }

    private void dismissCallActivity() {
        //Animation to dismiss call
        isAlreadyAcceptedOrDenied = false;
        btnDeclineCall.setEnabled(false);
        btnDeclineCall.setBackgroundColor(Color.LTGRAY);
        btnAcceptCall.setEnabled(false);
        btnAcceptCall.setBackgroundColor(Color.LTGRAY);

        btnMicro.setEnabled(false);
        btnDialpad.setEnabled(false);
        btnSpeaker.setEnabled(false);
        btnAddCall.setEnabled(false);
        btnMinimize.setEnabled(false);

        if (mView != null && mView.isShown())
            wm.removeView(mView);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                IncomingIPCallActivity.this.finish();
            }
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        LinphoneCore lc = LinphoneManager.getLcIfManagerNotDestroyedOrNull();
        if (lc != null) {
            lc.removeListener(mListener);
        }
        super.onDestroy();
    }

    private void initUI() {
        //Get view getInstance
        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        mAvatar = (ImageView) findViewById(R.id.img_avatar);
        mAvatarPlaceHolder = (ImageView) findViewById(R.id.img_avatar_placeholder);
        avLoading = (CallLoadingIndicatorView) findViewById(R.id.av_loading);
        btnAcceptCall = (RelativeLayout) findViewById(R.id.btn_accept_call);
        btnDeclineCall = (RelativeLayout) findViewById(R.id.btn_decline_call);
        txtCallNumber = (TextView) findViewById(R.id.txt_phone_number);
        btnMicro = (ImageButton) findViewById(R.id.btn_micro);
        btnDialpad = (ImageButton) findViewById(R.id.btn_dialpad);
        btnSpeaker = (ImageButton) findViewById(R.id.btn_speaker);
        btnAddCall = (ImageButton) findViewById(R.id.btn_add_call);
        btnMinimize = (ImageButton) findViewById(R.id.btn_minimize);
        numpad = (Numpad) findViewById(R.id.numpad);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mNotifyMgr  = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mView = getLayoutInflater().inflate(R.layout.sip_call_mini_horizontal, null);


        Intent notifIntent = new Intent(IncomingIPCallActivity.this, IncomingIPCallActivity.class);
        PendingIntent comebackIntent = PendingIntent.getActivity(this, 0, notifIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        LinphoneCall call = LinphoneManager.getLc().getCurrentCall();
        incallNotification = new NotificationCompat.Builder(this)
                .setContentTitle(call != null ? call.getRemoteAddress().getDisplayName() : "Unknown call")
                .setContentText("Tap to return to call...")
                .setContentIntent(comebackIntent)
                .setSmallIcon(R.drawable.ic_stat_default)
                .setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.mipmap.ic_launcher))
                .build();
        incallNotification.flags |= Notification.FLAG_ONGOING_EVENT;

        //Setup app toolbar
        if (mToolbar != null) {
            setSupportActionBar(mToolbar);
            mToolbar.setTitle("IP Voice Call");
            mToolbar.setLogo(R.drawable.ic_call_white_24dp);
        }

        //Scale avatar
        screenWidth = getResources().getDisplayMetrics().widthPixels;
        screenHeight = getResources().getDisplayMetrics().heightPixels;

        Drawable avatar = ContextCompat.getDrawable(this, R.drawable.ic_account_circle_white);
        Drawable placeHolder = ContextCompat.getDrawable(this, R.drawable.ic_circle);

        mAvatarPlaceHolder.setImageBitmap(getBitmapFromVectorDrawble(placeHolder, screenWidth / 3 + 8, screenWidth / 3 + 8));
        mAvatar.setImageBitmap(getBitmapFromVectorDrawble(avatar, screenWidth / 3, screenWidth / 3));

        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) avLoading.getLayoutParams();
        params.width = screenWidth - 16;
        params.height = screenWidth - 16;
        avLoading.setLayoutParams(params);

        numpad.getBackground().setAlpha(240);
        setAddCallButtonEnabled(false);

        View lastMiniView = LinphoneService.instance().getIncallMiniView();
        if (lastMiniView != null && lastMiniView.isShown()) {
            wm.removeView(LinphoneService.instance().getIncallMiniView());
        }
    }

    private void decline() {
        if (isAlreadyAcceptedOrDenied) {
            return;
        }
        isAlreadyAcceptedOrDenied = true;

        if (LinphoneService.isReady()) {
            LinphoneManager.getLc().declineCall(linphoneCall, Reason.Busy);
        }
        dismissCallActivity();
    }

    private void hangUp() {
        if (LinphoneService.isReady()) {
            LinphoneCore lc = LinphoneManager.getLc();
            LinphoneCall currentCall = lc.getCurrentCall();

            if (currentCall != null) {
                lc.terminateCall(currentCall);
            } else if (lc.isInConference()) {
                lc.terminateConference();
            } else {
                lc.terminateAllCalls();
            }

            isInCall = false;
        }
    }

    private void answer() {
        if (isAlreadyAcceptedOrDenied) {
            return;
        }
        isAlreadyAcceptedOrDenied = true;

        LinphoneCallParams params = LinphoneManager.getLc().createCallParams(linphoneCall);

        boolean isLowBandwidthConnection = !LinphoneUtils.isHighBandwidthConnection(LinphoneService.instance().getApplicationContext());

        if (params != null) {
            params.enableLowBandwidth(isLowBandwidthConnection);
        } else {
            Log.e("Could not create call params for call");
        }

        if (params == null || !LinphoneManager.getInstance().acceptCallWithParams(linphoneCall, params)) {
            // the above method takes care of Samsung Galaxy S
            Toast.makeText(this, "Could not accept call", Toast.LENGTH_LONG).show();
        } else {
            LinphoneManager.getInstance().routeAudioToReceiver();
            LinphoneManager.getLc().enableSpeaker(false);
        }

        isInCall = true;
    }

    private void hideNumpad() {
        if (numpad == null || numpad.getVisibility() != View.VISIBLE) {
            return;
        }

        btnDialpad.setImageResource(R.drawable.ic_dialpad_white);
        numpad.setVisibility(View.GONE);
    }

    private void hideOrDisplayNumpad() {
        if (numpad == null) {
            return;
        }

        if (numpad.getVisibility() == View.VISIBLE) {
            hideNumpad();
        } else {
            btnDialpad.setImageResource(R.drawable.ic_dialpad_white);
            numpad.setVisibility(View.VISIBLE);
        }
    }

    public int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    Bitmap getBitmapFromVectorDrawble(Drawable drawable, int width, int height) {
        try {
            Bitmap bitmap;

            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);

            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return Bitmap.createScaledBitmap(bitmap, width, height, false);
        } catch (OutOfMemoryError e) {
            // Handle the error
            return null;
        }
    }
}
