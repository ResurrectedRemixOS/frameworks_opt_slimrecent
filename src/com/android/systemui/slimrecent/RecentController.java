/*
 * Copyright (C) 2014-2017 SlimRoms Project
 * Author: Lars Greiss - email: kufikugel@googlemail.com
 * Copyright (C) 2017 ABC rom
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.systemui.slimrecent;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.slimrecent.icons.IconsHandler;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Our main recents controller.
 * Takes care of the toggle, preload, cancelpreload and close requests
 * and passes the requested actions trough to our views and the panel
 * view controller #link:RecentPanelView.
 *
 * As well the in out animation, constructing the main window container
 * and the remove all tasks animation/detection (#link:RecentListOnScaleGestureListener)
 * are handled here.
 */
public class RecentController implements RecentPanelView.OnExitListener,
        RecentPanelView.OnTasksLoadedListener {

    private static final String TAG = "SlimRecentsController";

    private static final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";

    /** What the panel needs from SystemUI. */
    public interface Host {
        void pinTask(int taskId);

        /** @return true when split screen accepted both tasks */
        boolean splitTasks(int topTaskId, int bottomTaskId);
    }

    // Animation control values.
    private static final int ANIMATION_STATE_NONE = 0;
    private static final int ANIMATION_STATE_OUT  = 1;

    // Animation state.
    private int mAnimationState = ANIMATION_STATE_NONE;

    private final Host mHost;

    private RecyclerView mCardRecyclerView;
    private Configuration mConfiguration;
    private Context mContext;
    private ActivityManager mAm;
    private WindowManager mWindowManager;

    private CacheMoreCardsLayoutManager mLayoutManager;

    private boolean mIsShowing;
    private boolean mIsToggled;
    private boolean mIsPreloaded;
    private boolean mIgnoreOwnClose;

    private boolean mIsUserSetup;

    private boolean mExpandAnimation;

    // The different views we need.
    private ViewGroup mParentView;
    private ViewGroup mRecentContainer;
    private View mKeyguardView;
    private LinearLayout mRecentContent;
    private LinearLayout mRecentWarningContent;
    private ImageView mEmptyRecentView;
    private ImageView mKeyguardImage;
    private TextView mKeyguardText;

    private int mLayoutDirection = -1;
    private int mMainGravity;
    private int mUserGravity;
    private int mPanelColor;
    private int mEnterExitAnimation;

    TextView mMemText;
    ProgressBar mMemBar;
    boolean enableMemDisplay;
    private int mMembarcolor;
    private int mMemtextcolor;

    private boolean mMemBarLongClickToClear;

    private float mScaleFactor;

    private boolean mRREmptyView;

    // Main panel view.
    private RecentPanelView mRecentPanelView;

    private ArrayList<TaskDescription> mTasks = new ArrayList<>();
    private boolean mIsTopTaskInForeground;

    private Handler mHandler;

    private IconsHandler mIconsHandler;

    private boolean mWaitingClearAllConfirmation;
    private ObjectAnimator mClearAllAnimation;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            // Screen goes off or system dialogs should close.
            // Get rid of our recents screen
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                String reason = intent.getStringExtra("reason");
                if (SYSTEM_DIALOG_REASON_RECENT_APPS.equals(reason) && mIgnoreOwnClose) {
                    mIgnoreOwnClose = false;
                    return;
                }
                hideRecents(false);
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)){
                hideRecents(true);
            }
        }
    };

    public RecentController(Context context, Host host) {
        mContext = context;
        mHost = host;
        mLayoutDirection = getLayoutDirection();
        mScaleFactor = Settings.System.getIntForUser(
                mContext.getContentResolver(), SlimSettings.RECENT_PANEL_SCALE_FACTOR, 100,
                UserHandle.USER_CURRENT) / 100.0f;

        mHandler = new Handler();
        mAm = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        mWindowManager = (WindowManager)
                mContext.getSystemService(Context.WINDOW_SERVICE);

        /**
         * Add intent actions to listen on it.
         * Screen off to get rid of recents,
         * same if close system dialogs is requested.
         */
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);
        mConfiguration = new Configuration();
        mConfiguration.updateFrom(context.getResources().getConfiguration());

        mParentView = new RecentBaseFrameLayout(mContext, this);

        // Inflate our recents layout
        mRecentContainer =
                (RelativeLayout) View.inflate(context, R.layout.slim_recent, null);

        // Get contents for rebuilding and gesture detector.
        mRecentContent =
                (LinearLayout) mRecentContainer.findViewById(R.id.recent_content);

        mRecentWarningContent =
                (LinearLayout) mRecentContainer.findViewById(R.id.recent_warning_content);

        mCardRecyclerView =
                (RecyclerView) mRecentContainer.findViewById(R.id.recent_list);

        mMemText = (TextView) mRecentContainer.findViewById(R.id.recents_memory_text);
        mMemBar = (ProgressBar) mRecentContainer.findViewById(R.id.recents_memory_bar);
        mRecentContainer.findViewById(R.id.recents_membar)
                .setOnLongClickListener(mMemBarLongClickListener);

        mCardRecyclerView.setHasFixedSize(true);

        mEmptyRecentView =
                (ImageView) mRecentContainer.findViewById(R.id.empty_recent);

        mKeyguardView = View.inflate(context, R.layout.slim_recent_keyguard, null);

        mKeyguardImage =
                (ImageView) mKeyguardView.findViewById(R.id.keyguard_recent_img);

        mKeyguardText = (TextView) mKeyguardView.findViewById(R.id.keyguard_recent_text);

        mClearAllAnimation = ObjectAnimator.ofPropertyValuesHolder(
                mCardRecyclerView,
                PropertyValuesHolder.ofFloat("alpha", 0.3f)/*,
                maybe add more anim props here like scaleX and Y*/);
        mClearAllAnimation.setDuration(850);
        mClearAllAnimation.setRepeatCount(ObjectAnimator.INFINITE);
        mClearAllAnimation.setRepeatMode(ObjectAnimator.REVERSE);


        // Prepare gesture detector.
        final ScaleGestureDetector recentListGestureDetector =
                new ScaleGestureDetector(mContext,
                        new RecentListOnScaleGestureListener(mRecentWarningContent));

        // Prepare recents panel view and set the listeners
        mCardRecyclerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (mWaitingClearAllConfirmation) {
                    cancelClearAllWaiting();
                }
                recentListGestureDetector.onTouchEvent(event);
                return false;
            }
        });

        mRecentPanelView = new RecentPanelView(mContext, this, mCardRecyclerView, mEmptyRecentView);
        mRecentPanelView.setOnExitListener(this);
        mRecentPanelView.setOnTasksLoadedListener(this);

        // Add finally the views and listen for outside touches.
        mParentView.setFocusableInTouchMode(true);
        mParentView.addView(mRecentContainer);
        mParentView.addView(mKeyguardView);
        mParentView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    // Touch outside the recents window....hide recents window.
                    onExit();
                    return true;
                }
                return false;
            }
        });

        mIconsHandler = new IconsHandler(mContext, R.dimen.recent_app_icon_size, mScaleFactor);
        mRecentPanelView.setIconsHandler(mIconsHandler);

        // Settings observer
        new SettingsObserver(mHandler).observe();
        new KeepOpenSettingsObserver(mHandler).observe();

        mContext.registerComponentCallbacks(new ComponentCallback());
    }

    public void refreshCachedPackage(String packageName, boolean removedPackage) {
        CacheController.getInstance(mContext, null).refreshPackage(packageName, removedPackage);
        InfosCacheController.getInstance(mContext).refreshPackage(packageName);
    }

    public void evictAllCaches() {
        CacheController.getInstance(mContext, null).clearCache();
        InfosCacheController.getInstance(mContext).clearCache();
    }

    public void trimCaches(boolean lowMem) {
        int maxMemory;
        if (lowMem) {
            maxMemory = CacheController.getInstance(mContext, null).getMaxMemory();
            CacheController.getInstance(mContext, null).trimToSize(maxMemory / 8);
            InfosCacheController.getInstance(mContext).trimToSize(10);
        } else {
            maxMemory = CacheController.getInstance(mContext, null).getMaxMemory();
            CacheController.getInstance(mContext, null).trimToSize(maxMemory / 6);
            InfosCacheController.getInstance(mContext).trimToSize(20);
        }
    }

    /**
     * External call from theme engines to apply
     * new styles.
     */
    public void rebuildRecentsScreen() {
        // Set new layout parameters and backgrounds.
        if (mRecentContainer != null) {
            final ViewGroup.LayoutParams layoutParams = mRecentContainer.getLayoutParams();
            if (layoutParams != null) {
                layoutParams.width = (int) (mContext.getResources()
                        .getDimensionPixelSize(R.dimen.recent_width) * mScaleFactor);
                mRecentContainer.setLayoutParams(layoutParams);
            }

            setGravityAndImageResources();
        }
        // Rebuild complete adapter and lists to force style updates.
        if (mRecentPanelView != null) {
            mRecentPanelView.buildCardListAndAdapter();
        }
    }

    /**
     * Calculate main gravity based on layout direction and user gravity value.
     * Set and update all resources and notify the different layouts about the change.
     */
    private void setGravityAndImageResources() {
        // Calculate and set gravitiy.
        if (mLayoutDirection == View.LAYOUT_DIRECTION_RTL) {
            mMainGravity = reverseGravity(mUserGravity);
        } else {
            mMainGravity = mUserGravity;
        }

        // Set layout direction.
        mRecentContainer.setLayoutDirection(mLayoutDirection);

        // Reset all backgrounds.
        mRecentContent.setBackgroundResource(0);
        mRecentWarningContent.setBackgroundResource(0);
        mEmptyRecentView.setImageResource(0);

        // Set correct backgrounds based on calculated main gravity.
        mRecentWarningContent.setBackgroundColor(Color.RED);

        int tintColor = getEmptyRecentColor();
        int backgroundColor = mPanelColor;
        if (backgroundColor == 0x00ffffff) {
            backgroundColor = mContext.getColor(R.color.recent_background);
        }

        Drawable empty = mContext.getDrawable(mRREmptyView
                ? R.drawable.rr_main_conf_icon : R.drawable.ic_empty_recent).mutate();
        empty.setTint(tintColor);
        mEmptyRecentView.setImageDrawable(empty);

        Drawable keyguard = mContext.getDrawable(R.drawable.ic_recent_keyguard).mutate();
        keyguard.setTint(tintColor);
        mKeyguardImage.setImageDrawable(keyguard);
        mKeyguardText.setTextColor(tintColor);

        int padding = mContext.getResources().getDimensionPixelSize(R.dimen.slim_recents_elevation);
        if (mMainGravity == Gravity.START) {
            mRecentContainer.setPadding(0, 0, padding, 0);
            mEmptyRecentView.setRotation(mRREmptyView ? 0 : 180);
        } else {
            mRecentContainer.setPadding(padding, 0, 0, 0);
            mEmptyRecentView.setRotation(0);
        }


        // Set custom background color (or reset to default, as the case may be
        if (mRecentContent != null) {
            mRecentContent.setElevation(50);
            mRecentContent.setBackgroundColor(backgroundColor);
        }

        if (mKeyguardView != null) {
            mKeyguardView.setBackgroundColor(backgroundColor);
        }
    }

    private int getEmptyRecentColor() {
        int color = mPanelColor == 0x00ffffff ?
                mContext.getColor(R.color.recent_background) : mPanelColor;
        if (ColorUtils.isDarkColor(color)) {
            return mContext.getColor(R.color.recents_empty_light_color);
        } else {
            return mContext.getColor(R.color.recents_empty_dark_color);
        }
    }

    private int getLayoutDirection() {
        final Configuration currentConfig = mContext.getResources()
                .getConfiguration();
        Locale locale = currentConfig.getLocales().get(0);
        return TextUtils.getLayoutDirectionFromLocale(locale);
    }

    public void toggleRecentApps() {
        if (!mIsUserSetup) {
            return;
        }
        toggle();
    }

    /** Opens the panel; unlike the recents key it never closes it or switches apps. */
    public void showRecentApps() {
        if (!mIsUserSetup || isShowing()) {
            return;
        }
        toggle();
    }

    private void toggle() {
        int ld = getLayoutDirection();
        if (mLayoutDirection != ld) {
            mLayoutDirection = ld;
            setGravityAndImageResources();
        }

        if (mAnimationState == ANIMATION_STATE_NONE) {
            if (!isShowing()) {
                mIsToggled = true;
                if (!mIsPreloaded) {
                    // This should never happen due that preload should
                    // always be done if someone calls recents. Well a lot
                    // 3rd party apps forget the preload step. So we do it now.
                    // Due that mIsToggled is true preloader will open the recent
                    // screen as soon the preload is finished and the listener
                    // notifies us that we are ready.
                    preloadRecentApps();
                } else if (mRecentPanelView.atLeastOneTaskAvailable()) {
                    showRecents();
                }
            } else {
                openLastAppPanelToggle();
                hideRecents(false);
            }
        }
    }

    /** Docks {@code taskId} on top and the other recent app under it. */
    protected void startTaskinMultiWindow(int taskId) {
        int other = -1;
        for (TaskDescription task : mTasks) {
            if (task.persistentTaskId != taskId) {
                other = task.persistentTaskId;
                break;
            }
        }
        if (other == -1) {
            Log.w(TAG, "No second task to split with " + taskId);
            return;
        }
        splitTasks(taskId, other);
    }

    protected void splitTasks(int topTaskId, int bottomTaskId) {
        if (mHost != null && mHost.splitTasks(topTaskId, bottomTaskId)) {
            hideRecents(false);
        }
    }

    protected void addTasks(TaskDescription task) {
        mTasks.add(task);
    }

    protected void resetTasks() {
        mTasks.clear();
    }

    protected void isTopTaskInForeground(boolean toptask) {
        mIsTopTaskInForeground = toptask;
    }

    private void openLastAppPanelToggle() {
        if (mTasks != null && !mTasks.isEmpty()) {
            if (!mIsTopTaskInForeground) {
                startApplication(mTasks.get(0));
            } else if (mTasks.size() > 1) {
                startApplication(mTasks.get(1));
            }
        }
    }

    protected void startApplication(TaskDescription td) {
        // Starting app is requested by the user.
        // Move it to foreground or start it with custom animation.
        final ActivityOptions options = getAnimation(mContext);
        try {
            if (ActivityTaskManager.getService().startActivityFromRecents(
                    td.persistentTaskId, options.toBundle()) >= 0) {
                return;
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Could not start task " + td.persistentTaskId, e);
        }
        final Intent intent = new Intent(td.intent);
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
        } catch (SecurityException e) {
            Log.e(TAG, "Recents does not have the permission to launch " + intent, e);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Error launching activity " + intent, e);
        }
    }

    /**
     * Get custom animation for app starting.
     * @return Bundle
     */
    protected static ActivityOptions getAnimation(Context context) {
        ActivityOptions options = ActivityOptions.makeCustomAnimation(context,
                R.anim.recent_enter,
                R.anim.recent_screen_fade_out);
        options.setOverrideTaskTransition(true);
        return options;
    }

    /**
     * External call. Preload recent tasks.
     */
    public void preloadRecentApps() {
        if (!mIsUserSetup) {
            return;
        }
        // Post this to ensure that we don't block the touch feedback
        // on the nav bar button which triggers this.
        mHandler.post(() -> {
           if (mRecentPanelView != null) {
                mIsPreloaded = true;
                mRecentPanelView.setCancelledByUser(false);
                mRecentPanelView.loadTasks();
            }
        });
    }

    /**
     * External call. Cancel preload recent tasks.
     */
    public void cancelPreloadRecentApps() {
        if (!mIsUserSetup) {
            return;
        }
        if (mIsToggled) {
            Log.w(TAG, "cancelPreloadRecentApps() called after toggle, discarding");
            return;
        }
        if (mRecentPanelView != null && !isShowing()) {
            mIsPreloaded = false;
            mRecentPanelView.setCancelledByUser(true);
        }
    }

    protected void closeRecents() {
        hideRecents(false);
    }

    /**
     * Get LayoutParams we need for the recents panel.
     *
     * @return LayoutParams
     */
    private WindowManager.LayoutParams generateLayoutParameter() {
        final int width = (int) (mContext.getResources().getDimensionPixelSize(
                R.dimen.recent_width) * mScaleFactor);
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        // Set gravitiy.
        params.gravity = mMainGravity | Gravity.CENTER_VERTICAL;

        // Set animation for our recent window.
        switch (mEnterExitAnimation) {
            case 1:
                params.windowAnimations = R.style.Animation_SlimRecentScreen;
                break;
            case 0:
            default:
                if (mMainGravity == Gravity.START) {
                    params.windowAnimations = R.style.Animation_SlimRecentScreenOrig_Left;
                } else {
                    params.windowAnimations = R.style.Animation_SlimRecentScreenOrig_Right;
                }
                break;
        }

        // This title is for debugging only. See: dumpsys window
        params.setTitle("RecentControlPanel");
        return params;
    }

    // Returns if panel is currently showing.
    public boolean isShowing() {
        return mIsShowing;
    }

    public void scrollPanel(boolean down) {
        if (mWaitingClearAllConfirmation) {
            cancelClearAllWaiting();
        }
        mRecentPanelView.scrollPanel(down);
    }

    public void clearAllAppsFromSwipe() {
        if (mTasks == null || mTasks.isEmpty()) {
            return;
        }
        if (!mWaitingClearAllConfirmation) {
            mClearAllAnimation.start();
            mWaitingClearAllConfirmation = true;
        } else {
            cancelClearAllWaiting();
            if (mRecentPanelView.removeAllApplications()) {
                hideRecents(false);
            }
        }
    }

    protected void cancelClearAllWaiting() {
        mClearAllAnimation.cancel();
        mCardRecyclerView.setAlpha(1.0f);
        mWaitingClearAllConfirmation = false;
    }

    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        hideRecents(triggeredFromHomeKey);
    }

    // Hide the recent window.
    public boolean hideRecents(boolean forceHide) {
        if (!mIsUserSetup) {
            return false;
        }
        if (isShowing()) {
            mIsPreloaded = false;
            mIsToggled = false;
            mIsShowing = false;
            mIgnoreOwnClose = false;
            // stop async task if still loading
            mRecentPanelView.setCancelledByUser(true);
            if (forceHide) {
                mAnimationState = ANIMATION_STATE_NONE;
                mHandler.removeCallbacks(mRecentRunnable);
                mWindowManager.removeViewImmediate(mParentView);
                return true;
            } else if (mAnimationState != ANIMATION_STATE_OUT) {
                mAnimationState = ANIMATION_STATE_OUT;
                mHandler.removeCallbacks(mRecentRunnable);
                mHandler.postDelayed(mRecentRunnable, mContext.getResources().getInteger(
                        R.integer.config_recentDefaultDur));
                mWindowManager.removeView(mParentView);
                return true;
            }
        }
        return false;
    }

    // Show the recent window.
    private void showRecents() {
        mIsShowing = true;
        cancelClearAllWaiting();
        mIgnoreOwnClose = true;
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_RECENT_APPS);
        mAnimationState = ANIMATION_STATE_NONE;
        mHandler.removeCallbacks(mRecentRunnable);
        mWindowManager.addView(mParentView, generateLayoutParameter());
        mRecentPanelView.scrollToFirst();

        KeyguardManager km =
                (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        String restrictionMsg = null;
        if (km.isKeyguardLocked()) {
            restrictionMsg = mContext.getString(R.string.slim_recent_keyguard);
        }
        if (mAm.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
            restrictionMsg = mContext.getString(R.string.slim_recent_pinned);
        }
        if (restrictionMsg == null) {
            mRecentContainer.setVisibility(View.VISIBLE);
            mKeyguardView.setVisibility(View.GONE);
        } else {
            mKeyguardText.setText(restrictionMsg);
            mRecentContainer.setVisibility(View.GONE);
            mKeyguardView.setVisibility(View.VISIBLE);
        }
    }

    protected static void sendCloseSystemWindows(String reason) {
        try {
            ActivityManager.getService().closeSystemDialogs(reason);
        } catch (RemoteException e) {
            Log.w(TAG, "Could not close system dialogs", e);
        }
    }

    // Listener callback.
    @Override
    public void onExit() {
        hideRecents(false);
    }

    // Listener callback.
    @Override
    public void onTasksLoaded() {
        if (mIsToggled && !isShowing()) {
            showRecents();
        }
    }

    /**
     * Runnable if recent panel closed to notify the cache controller about the state.
     */
    private final Runnable mRecentRunnable = new Runnable() {
        @Override
        public void run() {
            mAnimationState = ANIMATION_STATE_NONE;
        }
    };

    /**
     * Settingsobserver to take care of the user settings.
     * Either gravity or scale factor of our recent panel can change.
     */
    private class SettingsObserver extends UserContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        protected void observe() {
            super.observe();
            ContentResolver resolver = mContext.getContentResolver();
            for (String key : new String[] {
                    SlimSettings.RECENT_PANEL_GRAVITY,
                    SlimSettings.RECENT_PANEL_SCALE_FACTOR,
                    SlimSettings.RECENT_PANEL_EXPANDED_MODE,
                    SlimSettings.RECENT_PANEL_BG_COLOR,
                    SlimSettings.SLIM_RECENT_RR_EMPTY_DRAWABLE,
                    SlimSettings.SLIM_RECENTS_MEM_DISPLAY,
                    SlimSettings.SLIM_RECENTS_MEM_DISPLAY_LONG_CLICK_CLEAR,
                    SlimSettings.RECENT_CARD_BG_COLOR,
                    SlimSettings.SLIM_RECENTS_ICON_PACK,
                    Settings.System.LOCK_TO_APP_ENABLED,
                    SlimSettings.RECENTS_MAX_APPS,
                    SlimSettings.SLIM_MEM_BAR_COLOR,
                    SlimSettings.SLIM_MEM_TEXT_COLOR,
                    SlimSettings.SLIM_RECENTS_CORNER_RADIUS,
                    SlimSettings.SLIM_RECENTS_BLACKLIST_VALUES,
                    SlimSettings.SLIM_RECENT_ENTER_EXIT_ANIMATION}) {
                resolver.registerContentObserver(Settings.System.getUriFor(key),
                        false, this, UserHandle.USER_ALL);
            }
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.DEVICE_PROVISIONED),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.USER_SETUP_COMPLETE),
                    false, this, UserHandle.USER_ALL);
            update(true);
        }

        @Override
        protected void update(boolean firstBoot) {
            // Close recent panel if it is opened
            hideRecents(false);

            ContentResolver resolver = mContext.getContentResolver();

            int expandMode = Settings.System.getIntForUser(
                        resolver, SlimSettings.RECENT_PANEL_EXPANDED_MODE,
                        RecentPanelView.EXPANDED_MODE_AUTO,
                        UserHandle.USER_CURRENT);
            mLayoutManager =
                    new CacheMoreCardsLayoutManager(mContext, mWindowManager, expandMode);
            mLayoutManager.setReverseLayout(true);
            mCardRecyclerView.setLayoutManager(mLayoutManager);
            mCardRecyclerView.setItemAnimator(mItemAnimator);

            // Get user gravity.
            mUserGravity = Settings.System.getIntForUser(
                    resolver, SlimSettings.RECENT_PANEL_GRAVITY, Gravity.END,
                    UserHandle.USER_CURRENT);

            mEnterExitAnimation = Settings.System.getIntForUser(
                    resolver, SlimSettings.SLIM_RECENT_ENTER_EXIT_ANIMATION, 0,
                    UserHandle.USER_CURRENT);

            mRREmptyView = Settings.System.getIntForUser(resolver,
                    SlimSettings.SLIM_RECENT_RR_EMPTY_DRAWABLE, 1,
                    UserHandle.USER_CURRENT) == 1;

            // Update colors in RecentPanelView
            mPanelColor = Settings.System.getIntForUser(resolver,
                    SlimSettings.RECENT_PANEL_BG_COLOR, 0x00ffffff, UserHandle.USER_CURRENT);

            // Set main gravity and background images.
            setGravityAndImageResources();

            // Get user scale factor.
            float scaleFactor = Settings.System.getIntForUser(
                    resolver, SlimSettings.RECENT_PANEL_SCALE_FACTOR, 100,
                    UserHandle.USER_CURRENT) / 100.0f;

            // If changed set new scalefactor, rebuild the recent panel
            // and notify RecentPanelView about new value.
            if (scaleFactor != mScaleFactor) {
                mScaleFactor = scaleFactor;
                rebuildRecentsScreen();
                CacheController.getInstance(mContext, null).clearCache();
                mIconsHandler.refresh();
                mIconsHandler.setScaleFactor(scaleFactor);
            }

            if (mRecentPanelView != null) {
                mRecentPanelView.setScaleFactor(mScaleFactor);
                mRecentPanelView.setExpandedMode(expandMode);
                mRecentPanelView.setCardColor(Settings.System.getIntForUser(
                    resolver, SlimSettings.RECENT_CARD_BG_COLOR, 0x00ffffff,
                    UserHandle.USER_CURRENT));
                mRecentPanelView.isScreenPinningEnabled(Settings.System.getIntForUser(
                        resolver, Settings.System.LOCK_TO_APP_ENABLED, 0,
                        UserHandle.USER_CURRENT) == 1);
                mRecentPanelView.setMaxAppsToLoad(Settings.System.getIntForUser(
                        resolver, SlimSettings.RECENTS_MAX_APPS, 15,
                        UserHandle.USER_CURRENT));
                mRecentPanelView.setCornerRadius(Converter.floatDpToPx(mContext,
                        Settings.System.getIntForUser(resolver,
                                SlimSettings.SLIM_RECENTS_CORNER_RADIUS, 5,
                                UserHandle.USER_CURRENT)));
                mRecentPanelView.setBlackList(Settings.System.getStringForUser(
                        resolver, SlimSettings.SLIM_RECENTS_BLACKLIST_VALUES,
                        UserHandle.USER_CURRENT));
            }

            mRecentContent.setElevation(50);

            int backgroundColor = mPanelColor;
            if (backgroundColor == 0x00ffffff) {
                backgroundColor = mContext.getColor(R.color.recent_background);
            }
            mRecentContent.setBackgroundColor(backgroundColor);
            mKeyguardView.setBackgroundColor(backgroundColor);

            enableMemDisplay = Settings.System.getIntForUser(resolver,
                    SlimSettings.SLIM_RECENTS_MEM_DISPLAY, 0, UserHandle.USER_CURRENT) == 1;
            showMemDisplay();

            mMemBarLongClickToClear = Settings.System.getIntForUser(resolver,
                    SlimSettings.SLIM_RECENTS_MEM_DISPLAY_LONG_CLICK_CLEAR, 0,
                    UserHandle.USER_CURRENT) == 1;
            mMembarcolor = Settings.System.getIntForUser(resolver,
                    SlimSettings.SLIM_MEM_BAR_COLOR, 0x00ffffff, UserHandle.USER_CURRENT);
            mMemtextcolor = Settings.System.getIntForUser(resolver,
                    SlimSettings.SLIM_MEM_TEXT_COLOR, 0x00ffffff, UserHandle.USER_CURRENT);

            String currentIconPack = Settings.System.getStringForUser(resolver,
                    SlimSettings.SLIM_RECENTS_ICON_PACK, UserHandle.USER_CURRENT);
            CacheController.getInstance(mContext, null).clearCache();
            mIconsHandler.updatePrefs(currentIconPack != null ? currentIconPack : "");

            mIsUserSetup = Settings.Global.getInt(resolver,
                    Settings.Global.DEVICE_PROVISIONED, 0) != 0
                    && Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_CURRENT) != 0;

            // force a new preloading on next Recents call after boot or a settings change
            // to refresh the panel before the user shows it again.
            mIsPreloaded = false;
        }
    }

    /**
     * Settingsobserver to take care of the user settings that don't require closing the panel.
     */
    private class KeepOpenSettingsObserver extends UserContentObserver {
        KeepOpenSettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        protected void observe() {
            super.observe();
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    SlimSettings.RECENT_PANEL_FAVORITES),
                    false, this, UserHandle.USER_ALL);
            update(true);
        }

        @Override
        protected void update(boolean firstBoot) {
            ContentResolver resolver = mContext.getContentResolver();
            if (mRecentPanelView != null) {
                mRecentPanelView.setCurrentFavorites(Settings.System.getStringForUser(
                        resolver, SlimSettings.RECENT_PANEL_FAVORITES,
                        UserHandle.USER_CURRENT));
            }
        }
    }

    public LinearLayoutManager getLayoutManager() {
        return (LinearLayoutManager) mLayoutManager;
    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (mConfiguration.densityDpi != newConfig.densityDpi) {
            hideRecents(true);
            evictAllCaches();
            mIconsHandler.onDpiChanged(mContext);
            rebuildRecentsScreen();
            preloadRecentApps();
        }
        mConfiguration.updateFrom(newConfig);
    }

    /**
     * Extended SimpleOnScaleGestureListener to take
     * care of a pinch to zoom out gesture. This class
     * takes as well care on a bunch of animations which are needed
     * to control the final action.
     */
    private class RecentListOnScaleGestureListener extends SimpleOnScaleGestureListener {

        // Constants for scaling max/min values.
        private final static float MAX_SCALING_FACTOR       = 1.0f;
        private final static float MIN_SCALING_FACTOR       = 0.5f;
        private final static float MIN_ALPHA_SCALING_FACTOR = 0.55f;

        private final static int ANIMATION_FADE_IN_DURATION  = 400;
        private final static int ANIMATION_FADE_OUT_DURATION = 300;

        private float mScalingFactor = MAX_SCALING_FACTOR;
        private boolean mActionDetected;

        // Views we need and are passed trough the constructor.
        private LinearLayout mRecentWarningContent;

        RecentListOnScaleGestureListener(LinearLayout recentWarningContent) {
            mRecentWarningContent = recentWarningContent;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // Get gesture scaling factor and calculate the values we need.
            mScalingFactor *= detector.getScaleFactor();
            mScalingFactor = Math.max(MIN_SCALING_FACTOR,
                    Math.min(mScalingFactor, MAX_SCALING_FACTOR));
            final float alphaValue = Math.max(MIN_ALPHA_SCALING_FACTOR,
                    Math.min(mScalingFactor, MAX_SCALING_FACTOR));

            // Reset detection value.
            mActionDetected = false;

            // Set alpha value for content.
            mRecentContent.setAlpha(alphaValue);

            // Check if we are under MIN_ALPHA_SCALING_FACTOR and show
            // warning view.
            if (mScalingFactor < MIN_ALPHA_SCALING_FACTOR) {
                mActionDetected = true;
                mRecentWarningContent.setVisibility(View.VISIBLE);
            } else {
                mRecentWarningContent.setVisibility(View.GONE);
            }
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return mRecentPanelView.hasClearableTasks();
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            super.onScaleEnd(detector);
            // Reset to default scaling factor to prepare for next gesture.
            mScalingFactor = MAX_SCALING_FACTOR;

            final float currentAlpha = mRecentContent.getAlpha();

            // Gesture was detected and activated. Prepare and play the animations.
            if (mActionDetected) {
                final boolean hasFavorite = mRecentPanelView.hasFavorite();

                // Setup animation for warning content - fade out.
                ValueAnimator animation1 = ValueAnimator.ofFloat(1.0f, 0.0f);
                animation1.setDuration(ANIMATION_FADE_OUT_DURATION);
                animation1.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mRecentWarningContent.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Setup animation for list view - fade out.
                ValueAnimator animation2 = ValueAnimator.ofFloat(1.0f, 0.0f);
                animation2.setDuration(ANIMATION_FADE_OUT_DURATION);
                animation2.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mCardRecyclerView.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Setup animation for base content - fade in.
                ValueAnimator animation3 = ValueAnimator.ofFloat(currentAlpha, 1.0f);
                animation3.setDuration(ANIMATION_FADE_IN_DURATION);
                animation3.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mRecentContent.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Setup animation for empty recent image - fade in.
                if (!hasFavorite && !mRREmptyView) {
                    mEmptyRecentView.setAlpha(0.0f);
                    mEmptyRecentView.setVisibility(View.VISIBLE);
                }
                ValueAnimator animation4 = ValueAnimator.ofFloat(0.0f, 1.0f);
                animation4.setDuration(ANIMATION_FADE_IN_DURATION);
                animation4.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mEmptyRecentView.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Start all ValueAnimator animations
                // and listen onAnimationEnd to prepare the views for the next call.
                AnimatorSet animationSet = new AnimatorSet();
                if (hasFavorite || mRREmptyView) {
                    animationSet.playTogether(animation1, animation3);
                } else {
                    animationSet.playTogether(animation1, animation2, animation3, animation4);
                }
                animationSet.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // Animation is finished. Prepare warning content for next call.
                        mRecentWarningContent.setVisibility(View.GONE);
                        mRecentWarningContent.setAlpha(1.0f);
                        // Remove all tasks now.
                        if (mRecentPanelView.removeAllApplications()) {
                            // Prepare listview for next recent call.
                            mCardRecyclerView.setVisibility(View.GONE);
                            mCardRecyclerView.setAlpha(1.0f);
                            // Finally hide our recents screen.
                            hideRecents(false);
                        }
                    }
                });
                animationSet.start();

            } else if (currentAlpha < 1.0f) {
                // No gesture action was detected. But we may have a lower alpha
                // value for the content. Animate back to full opacitiy.
                ValueAnimator animation = ValueAnimator.ofFloat(currentAlpha, 1.0f);
                animation.setDuration(100);
                animation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mRecentContent.setAlpha((Float) animation.getAnimatedValue());
                    }
                });
                animation.start();
            }
        }
    }

    private int reverseGravity(int gravity){
        return gravity == Gravity.START ? Gravity.END : Gravity.START;
    }

    public void onLaunchApplication() {
    }

    private boolean showMemDisplay() {
        if (!enableMemDisplay) {
            mMemText.setVisibility(View.GONE);
            mMemBar.setVisibility(View.GONE);
            return false;
        }
        mMemText.setVisibility(View.VISIBLE);
        mMemBar.setVisibility(View.VISIBLE);

        updateMemoryStatus();
        return true;
    }

    public void updateMemoryStatus() {
        if (mMemText.getVisibility() == View.GONE
                || mMemBar.getVisibility() == View.GONE) return;

        MemoryInfo memInfo = new MemoryInfo();
        mAm.getMemoryInfo(memInfo);
            int available = (int)(memInfo.availMem / 1048576L);
            int max = (int)(getTotalMemory() / 1048576L);
            mMemText.setText(mContext.getResources().getString(R.string.slim_recents_free_ram,
                    available));
            mMemBar.setMax(max);
            mMemBar.setProgress(available);
            mMemBar.getProgressDrawable().setColorFilter(mMembarcolor == 0x00ffffff
                    ? mContext.getColor(R.color.recents_membar_color)
                    : mMembarcolor, Mode.MULTIPLY);
            mMemText.setTextColor(mMemtextcolor == 0x00ffffff
                    ? mContext.getColor(R.color.recents_membar_text_color)
                    : mMemtextcolor);
    }

    public long getTotalMemory() {
        MemoryInfo memInfo = new MemoryInfo();
        mAm.getMemoryInfo(memInfo);
        long totalMem = memInfo.totalMem;
        return totalMem;
    }

    private class CacheMoreCardsLayoutManager extends LinearLayoutManager {
        private Context context;
        private WindowManager mWindowManager;
        private int mExpandMode;

        public CacheMoreCardsLayoutManager(Context context, WindowManager windowManager,
                                           int expandMode) {
            super(context);
            this.context = context;
            this.mWindowManager = windowManager;
            this.mExpandMode = expandMode;
        }

        @Override
        public boolean supportsPredictiveItemAnimations() {
            return true;
        }

        @Override
        protected void calculateExtraLayoutSpace(RecyclerView.State state,
                int[] extraLayoutSpace) {
            int space = 300;
            switch(mExpandMode) {
                case RecentPanelView.EXPANDED_MODE_DISABLED:
                    space = 300;
                    break;
                case RecentPanelView.EXPANDED_MODE_NEVER:
                case RecentPanelView.EXPANDED_MODE_AUTO:
                    space = 600;
                    break;
                case RecentPanelView.EXPANDED_MODE_ALWAYS:
                    space = getScreenHeight();
                    break;
            }
            extraLayoutSpace[0] = space;
            extraLayoutSpace[1] = space;
        }

        private int getScreenHeight() {
            return mWindowManager.getCurrentWindowMetrics().getBounds().height();
        }
    }

    protected void pinApp(int persistentTaskId) {
        if (mHost != null) {
            mHost.pinTask(persistentTaskId);
            hideRecents(false);
        }
    }

    protected static boolean killAppLongClick(Context context,
            String packageName, int persistentTaskId) {
        boolean killed = false;
        if (context.checkCallingOrSelfPermission(
                android.Manifest.permission.FORCE_STOP_PACKAGES)
                == PackageManager.PERMISSION_GRANTED) {
            if (packageName != null) {
                try {
                    ActivityManager.getService().forceStopPackage(
                            packageName, UserHandle.USER_CURRENT);
                    killed = true;
                } catch (RemoteException e) {
                    Log.w(TAG, "Could not force stop " + packageName, e);
                }
                if (killed) {
                    RecentPanelView.removeTask(persistentTaskId);
                }
            } else {
                Log.w(TAG, "Tried to kill package NULL");
            }
        } else {
            Log.e(TAG, "Slim recents is missing the permission to force stop packages");
        }
        return killed;
    }

    private class ComponentCallback implements ComponentCallbacks2 {
        @Override
        public void onTrimMemory(int level) {
            switch (level) {
                case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                    // Stop the loader immediately when the UI is no longer visible
                    cancelPreloadRecentApps();
                    break;
                case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
                case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                 break;
                case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                    trimCaches(false);
                    break;
                case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                    // We are going to be low on memory
                    trimCaches(true);
                   break;
                case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                    // We are low on memory, so release everything
                    evictAllCaches();
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onLowMemory() {
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
        }
    }

    private View.OnLongClickListener mMemBarLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            if (!mMemBarLongClickToClear) {
                return false;
            }
            if (mRecentPanelView.hasClearableTasks()) {
                if (mRecentPanelView.removeAllApplications()) {
                    hideRecents(false);
                }
                return true;
            }
            return false;
        }
    };

    public void onStartExpandAnimation() {
        mExpandAnimation = true;
    }

    private DefaultItemAnimator mItemAnimator = new DefaultItemAnimator() {
        @Override
        public boolean canReuseUpdatedViewHolder(RecyclerView.ViewHolder viewHolder) {
            // Similar to SimpleItemAnimator.setSupportsChangeAnimations(mExpandAnimation)
            if (mExpandAnimation) {
                mExpandAnimation = false;
                return super.canReuseUpdatedViewHolder(viewHolder);
            }
            // Returning true means we don't support change animations here
            return true;
        }
    };
}
