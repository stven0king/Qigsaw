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

import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * 将app_debug.apk 解压 将assets/ 目录下所有内容
 * 释放到app/build/intermediates/qigsaw/old-apk/target-files/debug中
 * 版本比较时需要
 *
 * 依赖 processManifest 先执行
 *
 */
class ExtractTargetFilesFromOldApk extends DefaultTask {
    /**
     * 默认
     * app.gradle 中
     *
     * qigsawSplit {
     *    oldApk 配置
     * }
     *
     */
    @InputFile
    @Optional
    File oldApk
    /**
     * app/build/intermediates/qigsaw/old-apk/target-files/debug
     */
    @OutputDirectory
    File targetFilesExtractedDir

    @TaskAction
    void extractTargetFiles() {
        if (targetFilesExtractedDir.exists()) {
            //清除目录下所有内容
            FileUtils.deleteDir(targetFilesExtractedDir)
        }
        //创建目录
        targetFilesExtractedDir.mkdirs()

        if (oldApk != null) {
            //转换目录
            project.copy { spec ->
                //从指定的路径获取apk包
                spec.from(project.zipTree(oldApk))
                //删选assets/qigsaw/ 下所有的内容
                spec.include("assets/qigsaw/**")
                //app/build/intermediates/qigsaw/old-apk/target-files/debug/assets/qigsaw/
                spec.into(targetFilesExtractedDir)
            }
        }
    }
}
