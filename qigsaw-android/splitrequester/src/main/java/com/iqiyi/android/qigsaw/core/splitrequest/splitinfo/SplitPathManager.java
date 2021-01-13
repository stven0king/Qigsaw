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

package com.iqiyi.android.qigsaw.core.splitrequest.splitinfo;

import android.content.Context;

import androidx.annotation.RestrictTo;

import com.iqiyi.android.qigsaw.core.common.FileUtil;
import com.iqiyi.android.qigsaw.core.common.SplitBaseInfoProvider;
import com.iqiyi.android.qigsaw.core.common.SplitConstants;
import com.iqiyi.android.qigsaw.core.common.SplitLog;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

@RestrictTo(LIBRARY_GROUP)
public final class SplitPathManager {

    private static final String TAG = "SplitPathManager";

    private static final AtomicReference<SplitPathManager> sSplitPathManagerRef = new AtomicReference<>();

    //qigsaw/${qigsawid}
    private final File rootDir;

    private final String qigsawId;

    private SplitPathManager(File rootDir, String qigsawId) {
        this.rootDir = new File(rootDir, qigsawId);
        this.qigsawId = qigsawId;
    }

    public static void install(Context context) {
        sSplitPathManagerRef.compareAndSet(null, create(context));
    }

    private static SplitPathManager create(Context context) {
        File baseRootDir = context.getDir(SplitConstants.QIGSAW, Context.MODE_PRIVATE);
        //反射获取QigsawConfig.java中的qigsawId
        String qigsawId = SplitBaseInfoProvider.getQigsawId();
        return new SplitPathManager(baseRootDir, qigsawId);
    }

    public static SplitPathManager require() {
        if (sSplitPathManagerRef.get() == null) {
            throw new RuntimeException("SplitPathManager must be initialized firstly!");
        }
        return sSplitPathManagerRef.get();
    }

    /**
     * Qigsaw/{$gigsawid}/{$splitname}
     * @param info
     * @return
     */
    public File getSplitRootDir(SplitInfo info) {
        File splitRootDir = new File(rootDir, info.getSplitName());
        if (!splitRootDir.exists()) {
            splitRootDir.mkdirs();
        }
        return splitRootDir;
    }

    /**
     * get storage path of bundle apk
     * Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}
     *
     * @param info split info
     */
    public File getSplitDir(SplitInfo info) {
        File splitDir = new File(getSplitRootDir(info), info.getSplitVersion());
        if (!splitDir.exists()) {
            splitDir.mkdirs();
        }
        return splitDir;
    }

    /**
     * qigsaw/${qigsawid}/uninstall
     * @return
     */
    public File getUninstallSplitsDir() {
        File uninstallSplitsDir = new File(rootDir, "uninstall");
        if (!uninstallSplitsDir.exists()) {
            uninstallSplitsDir.mkdirs();
        }
        return uninstallSplitsDir;
    }

    /**
     * Get mark file for split, if file is existed, indicate the split has been installed.
     * Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}/${mark}
     * @param info split info.
     */
    public File getSplitMarkFile(SplitInfo info, String mark) {
        File splitDir = getSplitDir(info);
        return new File(splitDir, mark);
    }

    /**
     * Get special mark file for split(), if file is existed, indicate the split has been installed.
     * Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}/${mark}.ov
     * @param info split info.
     */
    public File getSplitSpecialMarkFile(SplitInfo info, String mark) {
        File splitDir = getSplitDir(info);
        return new File(splitDir, mark + ".ov");
    }

    /**
     * Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}/ov.lock
     * @param info
     * @return
     */
    public File getSplitSpecialLockFile(SplitInfo info) {
        File splitDir = getSplitDir(info);
        return new File(splitDir, "ov.lock");
    }

    /**
     * get storage path of split optimized dex
     * Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}/oat
     * @param info split info
     */
    public File getSplitOptDir(SplitInfo info) {
        File splitDir = getSplitDir(info);
        File optDir = new File(splitDir, "oat");
        if (!optDir.exists()) {
            if (optDir.mkdirs()) {
                //individual user report exception for "java.lang.IllegalArgumentException: optimizedDirectory not readable/writable:......"
                optDir.setWritable(true);
                optDir.setReadable(true);
            }
        }
        return optDir;
    }

    /**
     * Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}/code_cache
     * @param info
     * @return
     */
    public File getSplitCodeCacheDir(SplitInfo info) {
        File splitDir = getSplitDir(info);
        File codeCacheDir = new File(splitDir, "code_cache");
        if (!codeCacheDir.exists()) {
            codeCacheDir.mkdirs();
        }
        return codeCacheDir;
    }

    /**
     * get storage path of split extracted so
     * Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}/nativeLib/${splitabi}
     */
    public File getSplitLibDir(SplitInfo info, String abi) {
        File libDir = new File(getSplitDir(info), "nativeLib" + File.separator + abi);
        if (!libDir.exists()) {
            libDir.mkdirs();
        }
        return libDir;
    }

    /**
     * get storage path of temporary file
     */
    public File getSplitTmpDir() {
        File tmpDir = new File(rootDir, "tmp");
        if (!tmpDir.exists()) {
            tmpDir.mkdirs();
        }
        return tmpDir;
    }

    /**
     * Delete all file, except the current QigsawId file
     */
    public void clearCache() {
        File qigsawIdDir = rootDir.getParentFile();
        File[] qigsawIdFiles = qigsawIdDir.listFiles();
        if (qigsawIdFiles != null && qigsawIdFiles.length > 0) {
            for (File file : qigsawIdFiles) {
                if (file.isDirectory() && !file.getName().equals(qigsawId)) {
                    FileUtil.deleteDir(file);
                    SplitLog.i(TAG, "Success to delete all obsolete splits for current app version!");
                }
            }
        }
    }
}
