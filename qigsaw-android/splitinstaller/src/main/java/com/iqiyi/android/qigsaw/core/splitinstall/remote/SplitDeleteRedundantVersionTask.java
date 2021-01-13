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

import com.iqiyi.android.qigsaw.core.common.FileUtil;
import com.iqiyi.android.qigsaw.core.common.SplitLog;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfo;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitPathManager;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

final class SplitDeleteRedundantVersionTask implements Runnable {

    private static final int MAX_SPLIT_CACHE_SIZE = 1;

    private static final String TAG = "SplitDeleteRedundantVersionTask";

    private final Collection<SplitInfo> allSplits;

    private final Context appContext;

    SplitDeleteRedundantVersionTask(Context appContext, Collection<SplitInfo> allSplits) {
        this.allSplits = allSplits;
        this.appContext = appContext;
    }

    @Override
    public void run() {
        if (allSplits != null) {
            for (SplitInfo splitInfo : allSplits) {
                //Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}
                File splitDir = SplitPathManager.require().getSplitDir(splitInfo);
                //Qigsaw/{$gigsawid}/{$splitname}
                File splitRootDir = SplitPathManager.require().getSplitRootDir(splitInfo);
                try {
                    String installedMark = splitInfo.obtainInstalledMark(appContext);
                    //Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}/${mark}
                    File installedMarkFile = SplitPathManager.require().getSplitMarkFile(splitInfo, installedMark);
                    deleteRedundantSplitVersionDirs(splitDir, splitRootDir, installedMarkFile);
                } catch (IOException ignored) {

                }
            }
        }
    }

    /**
     * 保留最新的split更新包，其他的删除
     * @param currentSplitVersionDir //Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}
     * @param splitRootDir          //Qigsaw/{$gigsawid}/{$splitname}
     * @param installedMarkFile
     */
    private void deleteRedundantSplitVersionDirs(final File currentSplitVersionDir, final File splitRootDir, final File installedMarkFile) {
        final String splitName = splitRootDir.getName();
        //获取split对应的所有version进行过滤
        File[] files = splitRootDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                //如果当前文件是文件夹，而且不是当前split version的文件夹，而且split version对应的apk已经完成install
                if (pathname.isDirectory() && !pathname.equals(currentSplitVersionDir)) {
                    SplitLog.i(TAG, "Split %s version %s has been installed!", splitName, pathname.getName());
                    return installedMarkFile.exists();
                }
                return false;
            }
        });
        if (files != null && files.length > MAX_SPLIT_CACHE_SIZE) {
            //按照修改时间顺，从新到旧
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File o1, File o2) {
                    if (o1.lastModified() < o2.lastModified()) {
                        return 1;
                    } else if (o1.lastModified() == o2.lastModified()) {
                        return 0;
                    } else {
                        return -1;
                    }
                }
            });
            //Delete all from the second, keep only one split version cache file
            for (int i = MAX_SPLIT_CACHE_SIZE; i < files.length; i++) {
                SplitLog.i(TAG, "Split %s version %s is redundant, so we try to delete it", splitName, files[i].getName());
                FileUtil.deleteDir(files[i]);
            }
        }
    }
}
