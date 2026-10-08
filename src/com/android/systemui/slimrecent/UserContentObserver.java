/*
 * Copyright (C) 2016 The CyanogenMod Project
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
 * limitations under the License
 */
package com.android.systemui.slimrecent;

import android.app.ActivityManager;
import android.app.UserSwitchObserver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

/**
 * Simple extension of ContentObserver that also listens for user switch events to call update
 */
public abstract class UserContentObserver extends ContentObserver {
    private static final String TAG = "UserContentObserver";

    private Runnable mUpdateRunnable;

    private UserSwitchObserver mUserSwitchObserver = new UserSwitchObserver() {
        @Override
        public void onUserSwitchComplete(int newUserId) throws RemoteException {
            mHandler.post(mUpdateRunnable);
        }
    };

    private Handler mHandler;

    /**
     * Content observer that tracks user switches
     * to allow clients to re-load settings for current user
     */
    public UserContentObserver(Handler handler) {
        super(handler);
        mHandler = handler;
        mUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                update(false);
            }
        };
    }

    protected void observe() {
        try {
            ActivityManager.getService().registerUserSwitchObserver(mUserSwitchObserver, TAG);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to register user switch observer!", e);
        }
    }

    protected void unobserve() {
        try {
            mHandler.removeCallbacks(mUpdateRunnable);
            ActivityManager.getService().unregisterUserSwitchObserver(mUserSwitchObserver);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to unregister user switch observer!", e);
        }
    }

    /**
     *  Called to notify of registered uri changes and user switches.
     *  Always invoked on the handler passed in at construction
     */
    protected void update(Uri uri) {
        update(false);
    }

    protected abstract void update(boolean firstBoot);

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        update(uri);
    }
}
