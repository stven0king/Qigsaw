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

package com.iqiyi.qigsaw.buildtool.gradle.task

import com.android.SdkConstants
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.InputDirectory

class ProcessOldOutputsBaseTask extends DefaultTask {
    //GenerateQigsawConfig
    ///Users/tanzx/AndroidStudioWorkSpace/GitHub/Qigsaw/app/build/intermediates/qigsaw/old-apk/target-files/debug
    /**
     * app/build/intermediates/qigsaw/old-apk/target-files/{debug/release}
     */
    @InputDirectory
    File targetFilesExtractedDir
    /**
     * 读取app/build/intermediates/qigsaw/old-apk/target-files/{debug/release}/assets/qigsaw/
     * 下所有的 内容
     * @return
     */
    File getOldSplitDetailsFile() {
        File oldSplitDetailsDir = new File(targetFilesExtractedDir, "assets/qigsaw/")
        File oldSplitDetailsFile = null
        if (oldSplitDetailsDir.exists()) {
            File[] files = oldSplitDetailsDir.listFiles(new FileFilter() {
                @Override
                boolean accept(File file) {
                    //已qigsaw 开头 且 已 .json 结尾
                    //配置文件
                    return file.name.endsWith(SdkConstants.DOT_JSON) && file.name.startsWith("qigsaw")
                }
            })
            if (files != null && files.length == 1) {
                oldSplitDetailsFile = files[0]
            } else {
                if (files != null && files.length > 1) {
                    throw new GradleException("Qigsaw Error: Multiple split-details json files ${files}")
                }
            }
        }
        return oldSplitDetailsFile
    }

    /**
     * 从old-apk 中读取 上一次构建的 feature内容
     * @param splitName
     * @param abi
     * @return
     * app/build/intermediates/qigsaw/old-apk/target-files/{debug/release}/assets/qigsaw/native-arm64-v8a.zip
     */
    File getOldSplitApk(String splitName, String abi) {
        return new File(targetFilesExtractedDir, "assets/qigsaw/${splitName}-${abi + SdkConstants.DOT_ZIP}")
    }

}
