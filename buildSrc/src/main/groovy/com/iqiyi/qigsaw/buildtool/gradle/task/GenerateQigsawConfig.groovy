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

import com.android.utils.FileUtils
import com.iqiyi.qigsaw.buildtool.gradle.compiling.QigsawConfigGenerator
import com.iqiyi.qigsaw.buildtool.gradle.internal.entity.SplitDetails
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.SplitLogger
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.TypeClassFileParser
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * 将old.apk
 *相关信息读取
 *
 *
 *
 */

//extractTargetFilesFromOldApk.dependsOn processManifest
//qigsawAssemble.dependsOn extractTargetFilesFromOldApk
//generateQigsawConfig.dependsOn extractTargetFilesFromOldApk
//generateQigsawConfig.dependsOn generateBuildConfig
//generateBuildConfig.finalizedBy generateQigsawConfig
//@Keep
//public final class QigsawConfig {
//    public static final boolean QIGSAW_MODE = Boolean.parseBoolean("true");
//    public static final String QIGSAW_ID = "1.0.0_7fd2b19";
//    public static final String VERSION_NAME = "1.0.0";
//    public static final String DEFAULT_SPLIT_INFO_VERSION = "1.0.0_1.0.0";
//    public static final String[] DYNAMIC_FEATURES = {"java","assets","native"};
//}
class GenerateQigsawConfig extends ProcessOldOutputsBaseTask {

    /**
     * 应用版本号_git提交号
     */
    //versionName+当前git的最后一个commitid
    @Input
    String qigsawId
    /**
     * 是否开启 QIGSAW_BUILD
     * 或者
     */
    @Input
    boolean qigsawMode
    /**
     * defaultConfig{
     *     applicationId
     * }
     */
    @Input
    String applicationId
    /**
     * defaultConfig{
     *    versionName
     * }
     */
    @Input
    String versionName
    /**
     * defaultConfig{ versionName} + "_" + qigsawSplit{splitInfoVersion}
     */

    //versionName_${qigsawSplit.splitInfoVersion}
    @Input
    String defaultSplitInfoVersion
    /**
     * android{
     *     dynamicFeatures [声明的所有工程（名称）]
     * }
     */
    @Input
    Set<String> dynamicFeatureNames
    /**
     * app/build/intermediates/qigsaw/qigsaw-config/debug(release)/packagename/
     */

    ///Users/tanzx/AndroidStudioWorkSpace/GitHub/Qigsaw/app/build/intermediates/qigsaw/qigsaw-config/debug
    @OutputDirectory
    File outputDir
    ///Users/tanzx/AndroidStudioWorkSpace/GitHub/Qigsaw/app/build/generated/source/buildConfig/debug
    @OutputDirectory
    File buildConfigDir

    @TaskAction
    void generate() throws IOException {
        QigsawConfigGenerator generator = new QigsawConfigGenerator(outputDir, applicationId)
        File qigsawConfigFile = generator.getQigsawConfigFile()
        if (qigsawConfigFile.exists()) {
            qigsawConfigFile.delete()
        }
        println("GenerateQigsawConfig:qigsawConfigFile=$qigsawConfigFile")
        List<String> jointList = new ArrayList<>()
        for (String name : dynamicFeatureNames) {
            jointList.add("\"" + name + "\"")
        }
        if (targetFilesExtractedDir != null) {
            File oldSplitDetailsFile = getOldSplitDetailsFile()
            if (oldSplitDetailsFile != null && oldSplitDetailsFile.exists()) {
                qigsawId = TypeClassFileParser.parseFile(oldSplitDetailsFile, SplitDetails.class).qigsawId
                if (qigsawId == null) {
                    throw new GradleException("Qigsaw Error: Can't read qigsaw id from old apk!")
                }
                SplitLogger.w("Read qigsaw id ${qigsawId} from old apk!")
            } else {
                println("GenerateQigsawConfig:oldSplitDetailsFile isn't exist~!")
            }
        }
        generator
                .addField(
                        "boolean",
                        "QIGSAW_MODE",
                        qigsawMode ? "Boolean.parseBoolean(\"true\")" : "false")
                .addField("String", "QIGSAW_ID", '"' + qigsawId + '"')
                .addField("String", "VERSION_NAME", '"' + versionName + '"')
                .addField("String", "DEFAULT_SPLIT_INFO_VERSION", '"' + defaultSplitInfoVersion + '"')
                .addField("String[]", "DYNAMIC_FEATURES", "{" + jointList.join(",") + "}")
        generator.generate()
        File destDir = new File(buildConfigDir, applicationId.replace(".", File.separator))
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        //QigsawConfigGenerator.QIGSAW_CONFIG_NAME = "QigsawConfig.java"
        File destFile = new File(destDir, QigsawConfigGenerator.QIGSAW_CONFIG_NAME)
        if (destFile.exists()) {
            destFile.delete()
        }
        FileUtils.copyFile(qigsawConfigFile, destFile)
    }
}
