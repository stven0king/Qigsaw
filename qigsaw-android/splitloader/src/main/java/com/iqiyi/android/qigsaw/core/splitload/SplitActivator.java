/*
 * MIT License
 *
 * Copyright (c) 2019-present, iQIYI, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.iqiyi.android.qigsaw.core.splitload;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.iqiyi.android.qigsaw.core.extension.AABExtension;
import com.iqiyi.android.qigsaw.core.extension.AABExtensionException;
import com.iqiyi.android.qigsaw.core.splitreport.SplitLoadError;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

final class SplitActivator {
    private static final String TAG = "SplitActivator";
    private final AABExtension aabExtension;

    private final Context appContext;

    private static final Map<String, Application> sSplitApplicationMap = new HashMap<>();

    SplitActivator(Context context) {
        this.appContext = context;
        this.aabExtension = AABExtension.getInstance();
    }

    Application createSplitApplication(ClassLoader classLoader, String splitName) throws SplitLoadException {
        Log.d(TAG, "createSplitApplication: splitName=" + splitName);
        Application app;
        try {
            app = aabExtension.createApplication(classLoader, splitName);
            if (app != null) {
                sSplitApplicationMap.put(splitName, app);
            }
        } catch (Throwable e) {
            if (debuggable()) {
                if (!(e instanceof AABExtensionException)) {
                    throw new RuntimeException(e);
                }
            }
            throw new SplitLoadException(SplitLoadError.CREATE_APPLICATION_FAILED, e);
        }
        return app;
    }

    void attachSplitApplication(Application application) throws SplitLoadException {
        try {
            aabExtension.activeApplication(application, appContext);
        } catch (AABExtensionException e) {
            throw new SplitLoadException(SplitLoadError.ACTIVATE_APPLICATION_FAILED, e);
        }
    }

    void invokeOnCreateForSplitApplication(Application application) throws SplitLoadException {
        Log.d(TAG, "invokeOnCreateForSplitApplication: ");
        if (application != null) {
            try {
                Method method = HiddenApiReflection.findMethod(Application.class, "onCreate");
                method.invoke(application);
            } catch (Throwable e) {
                if (debuggable()) {
                    throw new RuntimeException(e);
                }
                throw new SplitLoadException(SplitLoadError.ACTIVATE_APPLICATION_FAILED, e);
            }
        }
    }

    void createAndActivateSplitContentProviders(ClassLoader classLoader, String splitName) throws SplitLoadException {
        Log.d(TAG, "createAndActivateSplitContentProviders: splitName =" + splitName);
        try {
            aabExtension.createAndActivateSplitProviders(classLoader, splitName);
        } catch (AABExtensionException e) {
            throw new SplitLoadException(SplitLoadError.CREATE_PROVIDERS_FAILED, e);
        }
    }

    private boolean debuggable() {
        try {
            ApplicationInfo info = appContext.getApplicationInfo();
            return (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable ignored) {

        }
        return false;
    }
}