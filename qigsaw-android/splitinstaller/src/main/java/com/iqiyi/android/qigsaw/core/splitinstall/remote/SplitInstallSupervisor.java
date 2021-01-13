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

package com.iqiyi.android.qigsaw.core.splitinstall.remote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.RemoteException;

import androidx.annotation.RestrictTo;

import com.iqiyi.android.qigsaw.core.common.FileUtil;
import com.iqiyi.android.qigsaw.core.common.ProcessUtil;
import com.iqiyi.android.qigsaw.core.common.SplitLog;
import com.iqiyi.android.qigsaw.core.splitinstall.SplitPendingUninstallManager;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfo;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfoManager;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfoManagerService;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitPathManager;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

@RestrictTo(LIBRARY_GROUP)
public abstract class SplitInstallSupervisor {

    private static final String TAG = "SplitInstallSupervisor";

    /**
     * 1、删除qigsaw/${qigsawid}/uninstall/uninstallsplits.info中配置的
     * 2、删除每个split name对应的老版本数据
     * @param context
     */
    public final void startUninstall(Context context) {
        SplitPendingUninstallManager pendingUninstallManager = new SplitPendingUninstallManager();
        //qigsaw/${qigsawid}/uninstall/uninstallsplits.info,文件内容逗号分隔
        List<String> uninstallSplits = pendingUninstallManager.readPendingUninstallSplits();
        SplitInfoManager manager = SplitInfoManagerService.getInstance();
        List<SplitInfo> realUninstallSplits = null;
        if (uninstallSplits != null && manager != null) {
            //当前Qigsawjson配置文件中split names
            List<SplitInfo> uninstallSplitInfoList = manager.getSplitInfos(context, uninstallSplits);
            if (uninstallSplitInfoList != null) {
                ProcessUtil.killAllOtherProcess(context);
                realUninstallSplits = new ArrayList<>(uninstallSplitInfoList.size());
                for (SplitInfo uninstallSplitInfo : uninstallSplitInfoList) {
                    try {
                        String installedMark = uninstallSplitInfo.obtainInstalledMark(context);
                        //获取标识已经安装成功的mark文件
                        File installedMarkFile = SplitPathManager.require().getSplitMarkFile(uninstallSplitInfo, installedMark);
                        boolean ret = FileUtil.deleteFileSafely(installedMarkFile);
                        if (ret) {
                            realUninstallSplits.add(uninstallSplitInfo);
                        }
                    } catch (IOException ignored) {

                    }
                }
            }
        }
        //删除split模块信息,同步删除卸载模块信息配置文件
        if (realUninstallSplits != null && !realUninstallSplits.isEmpty()) {
            SplitInstallService.getHandler(context.getPackageName()).post(new SplitStartUninstallTask(realUninstallSplits));
        } else {
            SplitLog.d(TAG, "No splits need to uninstall!");
        }
        SplitInfoManager infoManager = SplitInfoManagerService.getInstance();
        if (infoManager != null) {
            Collection<SplitInfo> allSplitInfos = infoManager.getAllSplitInfo(context);
            if (allSplitInfos != null) {
                SplitInstallService.getHandler(context.getPackageName()).post(new SplitDeleteRedundantVersionTask(context, allSplitInfos));
            }
        }
    }

    public abstract void startInstall(List<Bundle> moduleNames, Callback callback) throws RemoteException;

    public abstract void deferredInstall(List<Bundle> moduleNames, Callback callback) throws RemoteException;

    public abstract void deferredUninstall(List<Bundle> moduleNames, Callback callback) throws RemoteException;

    public abstract void cancelInstall(int sessionId, Callback callback) throws RemoteException;

    public abstract void getSessionState(int sessionId, Callback callback) throws RemoteException;

    public abstract void getSessionStates(Callback callback) throws RemoteException;

    public abstract boolean continueInstallWithUserConfirmation(int sessionId);

    public abstract boolean cancelInstallWithoutUserConfirmation(int sessionId);

    protected static Bundle bundleErrorCode(int errorCode) {
        Bundle bundle = new Bundle();
        bundle.putInt("error_code", errorCode);
        return bundle;
    }

    protected static List<String> unBundleModuleNames(Collection<Bundle> moduleNamesBundle) {
        ArrayList<String> moduleNames = new ArrayList<>(moduleNamesBundle.size());
        for (Bundle bundle : moduleNamesBundle) {
            String moduleName = bundle.getString("module_name");
            moduleNames.add(moduleName);
        }
        return moduleNames;
    }

    protected static int createSessionId(Collection<SplitInfo> splitInfoList) {
        int sessionId = 0;
        for (SplitInfo info : splitInfoList) {
            String key = info.getSplitName() + "@" + info.getAppVersion() + "@" + info.getSplitVersion();
            sessionId = sessionId + createSessionId(key);
        }
        return sessionId;
    }

    private static int createSessionId(String key) {
        StringBuilder string = new StringBuilder();
        string.append(key);
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("MD5").digest(string.toString().getBytes("UTF-8"));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("NoSuchAlgorithmException", e);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UnsupportedEncodingException", e);
        }
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            if ((b & 0xFF) < 0x10) hex.append("0");
            hex.append(Integer.toHexString(b & 0xFF));
        }
        return hex.toString().hashCode();
    }

    protected static boolean isNetworkAvailable(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkInfo[] networkInfo = connectivityManager.getAllNetworkInfo();
            if (networkInfo.length > 0) {
                for (NetworkInfo info : networkInfo) {
                    if (info.getState() == NetworkInfo.State.CONNECTED) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    protected static boolean isMobileAvailable(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            return networkInfo != null && networkInfo.getType() == ConnectivityManager.TYPE_MOBILE;
        }
        return false;
    }

    public interface Callback {

        void onStartInstall(int sessionId, Bundle data);

        void onCancelInstall(int sessionId, Bundle data);

        void onGetSession(int sessionId, Bundle data);

        void onDeferredUninstall(Bundle data);

        void onDeferredInstall(Bundle data);

        void onGetSessionStates(List<Bundle> data);

        void onError(Bundle data);
    }
}
