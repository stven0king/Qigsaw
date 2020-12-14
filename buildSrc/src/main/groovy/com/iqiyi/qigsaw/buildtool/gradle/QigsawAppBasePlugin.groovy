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

package com.iqiyi.qigsaw.buildtool.gradle

import com.android.SdkConstants
import com.android.build.gradle.api.ApplicationVariant
import com.android.builder.model.AndroidProject
import com.iqiyi.qigsaw.buildtool.gradle.compiling.DexReMergeHandler
import com.iqiyi.qigsaw.buildtool.gradle.compiling.FixedMainDexList
import com.iqiyi.qigsaw.buildtool.gradle.extension.QigsawSplitExtension
import com.iqiyi.qigsaw.buildtool.gradle.extension.QigsawSplitExtensionHelper
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.AGPCompat
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.FileUtils
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.ApkSigner
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.SplitLogger
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.TinkerHelper
import com.iqiyi.qigsaw.buildtool.gradle.task.*
import com.iqiyi.qigsaw.buildtool.gradle.transform.SplitComponentTransform
import com.iqiyi.qigsaw.buildtool.gradle.transform.SplitResourcesLoaderTransform
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.Copy
import org.gradle.util.VersionNumber

/**
 * 主要是对base apk进行字节码操作和将插件apk信息拷贝到base apk的asset目录下
 */
class QigsawAppBasePlugin extends QigsawPlugin {
    /**
     * 插件执行入口
     * @param project
     */
    @Override
    void apply(Project project) {
        //create qigsaw extension.
        project.extensions.create("qigsawSplit", QigsawSplitExtension)
        if (!project.plugins.hasPlugin('com.android.application')) {
            throw new GradleException('Qigsaw Error: Android Application plugin required')
        }
        //获取当前 android gradle 版本
        def versionAGP = VersionNumber.parse(AGPCompat.getAndroidGradlePluginVersionCompat())
        if (versionAGP < VersionNumber.parse("3.2.0")) {
            throw new GradleException('Qigsaw Error: Android Gradle Version is required 3.2.0 at least!')
        }
        //判断是否集成了 qigsaw 插件
        boolean isQigsawBuild = isQigsawBuild(project)
        SplitLogger.w("qigsaw build mode? ${isQigsawBuild}")
        //获取android标签下内容
        def android = project.extensions.android
        //create ComponentInfo.class to record Android Component of dynamic features.
        SplitComponentTransform componentTransform = new SplitComponentTransform(project)
        //在Feature工程声明的组件中 调用 getResource() 时 挂载 application 中的resource
        SplitResourcesLoaderTransform resourcesLoaderTransform = new SplitResourcesLoaderTransform(project, true)
        android.registerTransform(componentTransform)
        if (isQigsawBuild) {
            android.registerTransform(resourcesLoaderTransform)
        }
        project.afterEvaluate {
            //必须是 aapt2 工具产出的包
            if (!AGPCompat.isAapt2EnabledCompat(project)) {
                throw new GradleException('Qigsaw Error: AAPT2 required')
            }
            // app-->build.gradle 下声明的 dynamicFeatures  （dynamicFeatures = [':java', ':assets', ':native']）
            Set<String> dynamicFeatures = android.dynamicFeatures
            if (dynamicFeatures == null || dynamicFeatures.isEmpty()) {
                //application 工程  build.gradle 必须声明 dynamicFeatures
                throw new GradleException("dynamicFeatures must be set in ${project.name}/build.gradle ")
            }
            //split 的 classPath
            Set<String> splitProjectClassPaths = new HashSet<>()
            //feature 的 name
            Set<String> dynamicFeaturesNames = new HashSet<>()
            dynamicFeatures.each {
                //每一个子feature工程
                Project splitProject = project.rootProject.project(it)
                String classPath = "${splitProject.group}:${splitProject.name}:${splitProject.version}"
                //Qigsaw:java:unspecified
                splitProjectClassPaths.add(classPath)
                dynamicFeaturesNames.add(splitProject.name)
            }
            componentTransform.dynamicFeatureNames = dynamicFeaturesNames
            //project.buildDir = /Users/lizhiqiang/Documents/AndroidProject/demo/QigsawV2/Qigsaw/app/build
            //AndroidProject.FD_INTERMEDIATES = intermediates
            //QIGSAW = qigsaw 将所有feature 下的 manifest 拷贝到指定目录
            File splitManifestParentDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/split-outputs/manifests")
            //merge 后的manifest 文件地址
            componentTransform.splitManifestParentDir = splitManifestParentDir
            android.applicationVariants.all { ApplicationVariant baseVariant ->
                //获取 build.gradle  android{} 下的内容
                if (baseVariant.versionName == null || baseVariant.applicationId == null) {
                    throw new GradleException("versionName and applicationId must be set in ${project.name}/build.gradle ")
                }
                String qigsawId = createQigsawId(project, baseVariant.versionName)
                SplitLogger.w("qigsaw id "+qigsawId)

                String completeSplitInfoVersion = jointCompleteSplitInfoVersion(project, baseVariant.versionName)
                SplitLogger.w("qigsaw info version "+completeSplitInfoVersion)
                //遍历 abi filter
                Set<String> baseAbiFilters = getAbiFilters(project, baseVariant)
                //获取apk 签名工具
                ApkSigner apkSigner = new ApkSigner(project, baseVariant)
                //processXXXXManifest  tesk
                Task processManifest = AGPCompat.getProcessManifestTask(project, baseVariant.name.capitalize())
                //mergeXXXXXAssets  tesk
                Task mergeAssets = AGPCompat.getMergeAssetsTask(project, baseVariant.name.capitalize())
                //packageXXXX  tesk
                Task packageApp = AGPCompat.getPackageTask(project, baseVariant.name.capitalize())
                //assemble task
                Task baseAssemble = AGPCompat.getAssemble(baseVariant)
                //generateDebugBuildConfig task
                Task generateBuildConfig = AGPCompat.getGenerateBuildConfigTask(project, baseVariant.name.capitalize())
                //transformNativeLibsWithMergeJniLibsForDebug task
                Task mergeJniLibs = AGPCompat.getMergeJniLibsTask(project, baseVariant.name.capitalize())
                //R8 压缩
                Task r8 = AGPCompat.getR8Task(project, baseVariant.name.capitalize())

                //3.2.x has no bundle_manifest dir
                File bundleManifestDir = AGPCompat.getBundleManifestDirCompat(processManifest, versionAGP)
                File bundleManifestFile = bundleManifestDir == null ? null : new File(bundleManifestDir, SdkConstants.ANDROID_MANIFEST_XML)
                File mergedManifestFile = AGPCompat.getMergedManifestFileCompat(processManifest)
                //app/build/intermediates/merged_assets/debug/out
                File mergedAssetsDir = new File(AGPCompat.getMergedAssetsBaseDirCompat(mergeAssets))
                SplitLogger.w("[mergedAssetsDir]=== "+mergedAssetsDir.toPath())
                //app/build/outputs/apk/debug
                File packageAppDir = AGPCompat.getPackageApplicationDirCompat(packageApp)
                //app/build/intermediates/transforms/mergeJniLibs/debug
                File mergedJniLibsBaseDir = AGPCompat.getMergeJniLibsBaseDirCompat(mergeJniLibs)
                // app/build/intermediates/qigsaw/old-apk/target-files/
                File targetFilesExtractedDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/old-apk/target-files/${baseVariant.name}")
                // app/build/intermediates/qigsaw/qigsaw-config/debug(release)/packagename/
                File qigsawConfigDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/qigsaw-config/${baseVariant.name}")
                //app/build/intermediates/qigsaw/split-outputs/apks/
                File splitApksDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/split-outputs/apks/${baseVariant.name}")
                File unzipSplitApkBaseDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/split-outputs/unzip/${baseVariant.name}")
                File splitManifestDir = new File(splitManifestParentDir, baseVariant.name)
                File splitInfoDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/split-outputs/split-info/${baseVariant.name}")
                File qigsawProguardDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/old-outputs/mapping/${baseVariant.name}")
                //app/build/intermediates/qigsaw/split-details/..
                File splitDetailsDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/split-details/${baseVariant.name}")
                File baseApksDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/base-outputs/apks/${baseVariant.name}")
                //build/intermediates/qigsaw/base-outputs/unzip/debug/
                File unzipBaseApkDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/base-outputs/unzip/${baseVariant.name}/${project.name}")
                //app/build/intermediates/qigsaw/split-details/{name}/qigsaw_defaultConfig{ versionName} + "_" + qigsawSplit{splitInfoVersion}.json
                File splitDetailsFile = new File(splitDetailsDir, "qigsaw" + "_" + completeSplitInfoVersion + SdkConstants.DOT_JSON)
                //app/build/intermediates/qigsaw/split-details/{name}/_update_record_.json
                File updateRecordFile = new File(splitDetailsDir, "_update_record_${SdkConstants.DOT_JSON}")
                //app/build/intermediates/qigsaw/split-details/{name}/base.app.cpu.abilist.properties
                File baseAppCpuAbiListFile = new File(splitDetailsDir, "base.app.cpu.abilist${SdkConstants.DOT_PROPERTIES}")
                File oldApk = getOldApkCompat(project)

                //${baseVariant.name.capitalize()}   Debug / Release
                //拷贝apk文件
                ExtractTargetFilesFromOldApk extractTargetFilesFromOldApk = project.tasks.create("extractTargetFilesFromOldApk${baseVariant.name.capitalize()}", ExtractTargetFilesFromOldApk)
                extractTargetFilesFromOldApk.oldApk = oldApk
                extractTargetFilesFromOldApk.targetFilesExtractedDir = targetFilesExtractedDir
                extractTargetFilesFromOldApk.setGroup(QIGSAW)

                //create ${applicationId}QigsawConfig.java
                GenerateQigsawConfig generateQigsawConfig = project.tasks.create("generate${baseVariant.name.capitalize()}QigsawConfig", GenerateQigsawConfig)
                generateQigsawConfig.qigsawMode = isQigsawBuild
                generateQigsawConfig.qigsawId = qigsawId
                generateQigsawConfig.applicationId = baseVariant.applicationId
                generateQigsawConfig.versionName = baseVariant.versionName
                generateQigsawConfig.defaultSplitInfoVersion = completeSplitInfoVersion
                generateQigsawConfig.dynamicFeatureNames = dynamicFeaturesNames
                generateQigsawConfig.outputDir = qigsawConfigDir
                generateQigsawConfig.buildConfigDir = baseVariant.variantData.scope.buildConfigSourceOutputDir
                generateQigsawConfig.targetFilesExtractedDir = targetFilesExtractedDir
                generateQigsawConfig.setGroup(QIGSAW)

                //将所有feature包 和 配置信息  拷贝到 app/build/intermediates/merged_assets/debug/out/qigsaw/
                CreateSplitDetailsFileTask qigsawAssemble = project.tasks.create("qigsawAssemble${baseVariant.name.capitalize()}", CreateSplitDetailsFileTask)
                qigsawAssemble.qigsawId = qigsawId
                qigsawAssemble.baseVersionName = baseVariant.versionName
                qigsawAssemble.completeSplitInfoVersion = completeSplitInfoVersion
                qigsawAssemble.abiFilters = baseAbiFilters
                qigsawAssemble.dynamicFeaturesNames = dynamicFeaturesNames
                qigsawAssemble.splitApksDir = splitApksDir
                qigsawAssemble.splitInfoDir = splitInfoDir
                qigsawAssemble.targetFilesExtractedDir = targetFilesExtractedDir
                qigsawAssemble.splitDetailsFile = splitDetailsFile
                qigsawAssemble.updateRecordFile = updateRecordFile
                qigsawAssemble.baseAppCpuAbiListFile = baseAppCpuAbiListFile
                qigsawAssemble.qigsawMergedAssetsDir = new File(mergedAssetsDir, "qigsaw")
                qigsawAssemble.mergedJniLibsBaseDir = mergedJniLibsBaseDir
                qigsawAssemble.setGroup(QIGSAW)

                List<File> baseApkFiles = new ArrayList<>()
                baseVariant.outputs.each {
                    //app/build/outputs/apk/debug/app-debug.apk
                    //app/build/outputs/apk/release/app-release.apk
                    baseApkFiles.add(it.outputFile)
                }
                //安装apk
                QigsawInstallTask qigsawInstall = project.tasks.create("qigsawInstall${baseVariant.name.capitalize()}", QigsawInstallTask)
                qigsawInstall.variantData = baseVariant.variantData
                qigsawInstall.versionAGP = versionAGP
                qigsawInstall.baseApkFiles = baseApkFiles
                qigsawInstall.setGroup(QIGSAW)
                //拷贝任务在编辑成功之后执行
                extractTargetFilesFromOldApk.dependsOn processManifest
                //Qigsaw 配置在 拷贝任务之后执行
                qigsawAssemble.dependsOn extractTargetFilesFromOldApk
                generateQigsawConfig.dependsOn extractTargetFilesFromOldApk
                generateQigsawConfig.dependsOn generateBuildConfig
                //generateDebugBuildConfig 在Qigsaw 配置 之后执行
                generateBuildConfig.finalizedBy generateQigsawConfig

                if (isQigsawBuild) {
                    qigsawAssemble.dependsOn mergeJniLibs
                    qigsawAssemble.finalizedBy baseAssemble
                    baseAssemble.dependsOn qigsawAssemble

                    mergeJniLibs.dependsOn mergeAssets
                    qigsawInstall.dependsOn qigsawAssemble
                    qigsawInstall.mustRunAfter baseAssemble
                    //判断 是否是 多架构支持的api build.gradle qigsawSplit{multipleApkForABIs}
                    if (QigsawSplitExtensionHelper.isMultipleApkForABIs(project)) {
                        SplitBaseApkForABIsTask splitBaseApkForABIs = project.tasks.create("split${baseVariant.name.capitalize()}BaseApkForABIs", SplitBaseApkForABIsTask)
                        splitBaseApkForABIs.baseVariant = baseVariant
                        splitBaseApkForABIs.apkSigner = apkSigner
                        splitBaseApkForABIs.use7z = QigsawSplitExtensionHelper.isUse7z(project)
                        splitBaseApkForABIs.dynamicFeaturesNames = dynamicFeaturesNames
                        splitBaseApkForABIs.baseAppCpuAbiListFile = baseAppCpuAbiListFile
                        splitBaseApkForABIs.baseApkFiles = baseApkFiles
                        splitBaseApkForABIs.packageAppDir = packageAppDir
                        splitBaseApkForABIs.baseApksDir = baseApksDir
                        splitBaseApkForABIs.unzipBaseApkDir = unzipBaseApkDir
                        baseAssemble.dependsOn splitBaseApkForABIs
                        packageApp.finalizedBy splitBaseApkForABIs
                    }

                    //for supporting split content-provider
                    QigsawProcessManifestTask qigsawProcessManifest = project.tasks.create("qigsawProcess${baseVariant.name.capitalize()}Manifest", QigsawProcessManifestTask)
                    qigsawProcessManifest.dynamicFeatureNames = dynamicFeaturesNames
                    qigsawProcessManifest.splitManifestDir = splitManifestDir
                    qigsawProcessManifest.mergedManifestFile = mergedManifestFile
                    qigsawProcessManifest.bundleManifestFile = bundleManifestFile
                    qigsawProcessManifest.mustRunAfter processManifest
                    generateQigsawConfig.dependsOn qigsawProcessManifest
                    //是否开启 multiDexEnable 配置
                    boolean multiDexEnabled
                    try {
                        multiDexEnabled = baseVariant.variantData.variantConfiguration.isMultiDexEnabled()
                    } catch (Throwable e) {
                        //compat for AGP 4.x
                        multiDexEnabled = baseVariant.variantData.variantDslInfo.isMultiDexEnabled()
                    }
                    if (multiDexEnabled) {
                        Task multiDex = AGPCompat.getMultiDexTask(project, baseVariant.name.capitalize())
                        if (multiDex != null) {
                            removeRulesAboutMultiDex(multiDex, baseVariant)
                        } else {
                            if (r8 != null) {
                                removeRulesAboutMultiDex(r8, baseVariant)
                            } else {
                                SplitLogger.w("multiDexEnabled is not necessary.")
                            }
                        }
                    }
                    boolean proguardEnable = baseVariant.getBuildType().isMinifyEnabled()
                    if (proguardEnable) {
                        QigsawProguardConfigTask qigsawProguardConfig = project.tasks.create("qigsawProguardConfig${baseVariant.name.capitalize()}", QigsawProguardConfigTask)
                        qigsawProguardConfig.outputFile = new File(qigsawProguardDir, "qigsaw_proguard.pro")
                        Task proguard = AGPCompat.getProguardTask(project, baseVariant.name.capitalize())
                        if (proguard != null) {
                            proguard.dependsOn qigsawProguardConfig
                        } else {
                            if (r8 != null) {
                                r8.dependsOn qigsawProguardConfig
                            } else {
                                throw new GradleException("Qigsaw Error:Proguard or R8 task is missing")
                            }
                        }
                        qigsawProguardConfig.mustRunAfter qigsawProcessManifest
                        baseVariant.getBuildType().buildType.proguardFiles(qigsawProguardConfig.outputFile)
                    }
                    packageApp.doFirst {
                        if (versionAGP < VersionNumber.parse("3.5.0")) {
                            Task dexSplitterTask = AGPCompat.getDexSplitterTask(project, baseVariant.name.capitalize())
                            if (dexSplitterTask != null) {
                                List<File> dexFiles = new ArrayList<>()
                                inputs.files.each { File file ->
                                    file.listFiles().each { x ->
                                        if (x.name.endsWith(SdkConstants.DOT_DEX) && x.name.startsWith("classes")) {
                                            dexFiles.add(x)
                                        }
                                    }
                                }
                                DexReMergeHandler handler = new DexReMergeHandler(project, baseVariant)
                                handler.reMerge(dexFiles)
                            }
                        }
                    }
                    packageApp.doLast {
                        File outputFile = new File(packageAppDir, splitDetailsFile.name)
                        if (outputFile.exists()) {
                            outputFile.delete()
                        }
                        if (splitDetailsFile.exists()) {
                            FileUtils.copyFile(splitDetailsFile, outputFile)
                        }
                    }
                    dynamicFeatures.each { String dynamicFeature ->
                        Project splitProject = project.rootProject.project(dynamicFeature)
                        ProcessTaskDependenciesBetweenBaseAndSplitsWithQigsaw taskDependenciesProcessor = new ProcessTaskDependenciesBetweenBaseAndSplitsWithQigsaw()
                        boolean isProjectEvaluated
                        try {
                            splitProject.extensions.android
                            isProjectEvaluated = true
                        } catch (Throwable ignored) {
                            isProjectEvaluated = false
                        }
                        if (isProjectEvaluated) {
                            taskDependenciesProcessor.apkSigner = apkSigner
                            taskDependenciesProcessor.baseProject = project
                            taskDependenciesProcessor.splitProject = splitProject
                            taskDependenciesProcessor.baseVariant = baseVariant
                            taskDependenciesProcessor.baseMergeJinLibs = mergeJniLibs
                            taskDependenciesProcessor.qigsawProcessManifest = qigsawProcessManifest
                            taskDependenciesProcessor.splitApksDir = splitApksDir
                            taskDependenciesProcessor.splitInfoDir = splitInfoDir
                            taskDependenciesProcessor.splitManifestDir = splitManifestDir
                            taskDependenciesProcessor.unzipSplitApkBaseDir = unzipSplitApkBaseDir
                            taskDependenciesProcessor.baseAbiFilters = baseAbiFilters
                            taskDependenciesProcessor.splitProjectClassPaths = splitProjectClassPaths
                            taskDependenciesProcessor.run()
                        } else {
                            splitProject.afterEvaluate {
                                taskDependenciesProcessor.apkSigner = apkSigner
                                taskDependenciesProcessor.baseProject = project
                                taskDependenciesProcessor.splitProject = splitProject
                                taskDependenciesProcessor.baseVariant = baseVariant
                                taskDependenciesProcessor.baseMergeJinLibs = mergeJniLibs
                                taskDependenciesProcessor.qigsawProcessManifest = qigsawProcessManifest
                                taskDependenciesProcessor.splitApksDir = splitApksDir
                                taskDependenciesProcessor.splitInfoDir = splitInfoDir
                                taskDependenciesProcessor.splitManifestDir = splitManifestDir
                                taskDependenciesProcessor.unzipSplitApkBaseDir = unzipSplitApkBaseDir
                                taskDependenciesProcessor.baseAbiFilters = baseAbiFilters
                                taskDependenciesProcessor.splitProjectClassPaths = splitProjectClassPaths
                                taskDependenciesProcessor.run()
                            }
                        }
                    }
                }
                else {
                    dynamicFeatures.each { String dynamicFeature ->
                        Project splitProject = project.rootProject.project(dynamicFeature)
                        ProcessTaskDependenciesBetweenBaseAndSplits taskDependenciesProcessor = new ProcessTaskDependenciesBetweenBaseAndSplits()
                        boolean isProjectEvaluated
                        try {
                            splitProject.extensions.android
                            isProjectEvaluated = true
                        } catch (Throwable ignored) {
                            isProjectEvaluated = false
                        }
                        if (isProjectEvaluated) {
                            taskDependenciesProcessor.baseProject = project
                            taskDependenciesProcessor.splitProject = splitProject
                            taskDependenciesProcessor.baseVariant = baseVariant
                            taskDependenciesProcessor.splitManifestDir = splitManifestDir
                            taskDependenciesProcessor.run()
                        } else {
                            splitProject.afterEvaluate {
                                taskDependenciesProcessor.baseProject = project
                                taskDependenciesProcessor.splitProject = splitProject
                                taskDependenciesProcessor.baseVariant = baseVariant
                                taskDependenciesProcessor.splitManifestDir = splitManifestDir
                                taskDependenciesProcessor.run()
                            }
                        }
                    }
                }
            }
        }
    }

    static class ProcessTaskDependenciesBetweenBaseAndSplits implements Runnable {

        ApplicationVariant baseVariant

        Project baseProject

        Project splitProject

        File splitManifestDir

        @Override
        void run() {
            splitProject.extensions.android.applicationVariants.all { ApplicationVariant variant ->
                if (variant.name == baseVariant.name) {
                    Task processSplitManifest = AGPCompat.getProcessManifestTask(splitProject, variant.name.capitalize())
                    Task copySplitManifest = splitProject.tasks.create("name": "copySplitManifest${variant.name.capitalize()}", "type": Copy) {
                        destinationDir splitManifestDir
                        from(AGPCompat.getMergedManifestFileCompat(processSplitManifest)) {
                            rename {
                                String fileName ->
                                    //创建空的Manifestw文件  重命名
                                    return splitProject.name + SdkConstants.DOT_XML
                            }
                        }
                        into(splitManifestDir)
                    }
                    processSplitManifest.finalizedBy copySplitManifest
                    copySplitManifest.dependsOn processSplitManifest
                    copySplitManifest.setGroup(QIGSAW)
                    onTargetVariantFound(variant, copySplitManifest)
                }
            }
        }

        protected void onTargetVariantFound(ApplicationVariant splitVariant, Task copySplitManifest) {

        }
    }

    /**
     * //将feature项目的androidManifest.xml文件拷贝到Qigsaw/app/build/intermediates/qigsaw/split-outputs/manifests/debug
     */
    static class ProcessTaskDependenciesBetweenBaseAndSplitsWithQigsaw extends ProcessTaskDependenciesBetweenBaseAndSplits {

        ApkSigner apkSigner

        Task baseMergeJinLibs

        Task qigsawProcessManifest

        File splitApksDir

        File splitInfoDir

        File unzipSplitApkBaseDir

        Set<String> baseAbiFilters

        Set<String> splitProjectClassPaths

        @Override
        protected void onTargetVariantFound(ApplicationVariant splitVariant, Task copySplitManifest) {
            //
            qigsawProcessManifest.dependsOn copySplitManifest
            String versionName = splitVariant.mergedFlavor.versionName
            if (versionName == null) {
                throw new GradleException("Qigsaw Error:versionName must be set in ${splitProject.name}/build.gradle!")
            }
            Set<String> splitAbiFilters = getAbiFilters(splitProject, splitVariant)
            if (!baseAbiFilters.isEmpty() && !baseAbiFilters.containsAll(splitAbiFilters)) {
                throw new GradleException("abiFilters config in project ${splitProject.name} must be less than base project.")
            }
            //copy and sign and unzip split apk
            List<File> splitApks = new ArrayList<>()
            splitVariant.outputs.each {
                splitApks.add(it.outputFile)
            }
            String splitVersion = versionName + "@" + splitVariant.mergedFlavor.versionCode
            int minApiLevel = splitVariant.mergedFlavor.minSdkVersion.apiLevel
            Set<String> splitProjectDependencies = new HashSet<>()
            Configuration configuration = splitProject.configurations."${splitVariant.name}CompileClasspath"
            configuration.incoming.dependencies.each {
                splitProjectDependencies.add("${it.group}:${it.name}:${it.version}")
            }

            Task splitAssemble = AGPCompat.getAssemble(splitVariant)
            ProcessSplitApkTask processSplitApk = splitProject.tasks.create("processSplitApk${splitVariant.name.capitalize()}", ProcessSplitApkTask)
            processSplitApk.apkSigner = apkSigner
            processSplitApk.aapt2File = new File(AGPCompat.getAapt2FromMavenCompat(baseVariant), SdkConstants.FN_AAPT2)
            processSplitApk.releaseSplitApk = QigsawSplitExtensionHelper.isReleaseSplitApk(baseProject)
            processSplitApk.restrictWorkProcessesForSplits = QigsawSplitExtensionHelper.getRestrictWorkProcessesForSplits(baseProject)
            processSplitApk.minApiLevel = minApiLevel
            processSplitApk.splitVersion = splitVersion
            processSplitApk.applicationId = baseVariant.applicationId
            processSplitApk.splitProjectClassPaths = splitProjectClassPaths
            processSplitApk.splitProjectDependencies = splitProjectDependencies
            processSplitApk.splitApks = splitApks
            processSplitApk.splitManifestDir = splitManifestDir
            processSplitApk.splitApksDir = splitApksDir
            processSplitApk.splitInfoDir = splitInfoDir
            processSplitApk.unzipSplitApkBaseDir = unzipSplitApkBaseDir

            processSplitApk.dependsOn splitAssemble
            baseMergeJinLibs.dependsOn processSplitApk
        }
    }
    /**
     *
     * @param project
     * @param versionName
     * @return
     *
     * defaultConfig{ versionName} + "_" + qigsawSplit{splitInfoVersion}
     *
     */
    static String jointCompleteSplitInfoVersion(Project project, String versionName) {
        //QigsawSplitExtensionHelper  获取 build.gradle 中 qigsawSplit 中的声明
        return versionName + "_" + QigsawSplitExtensionHelper.getSplitInfoVersion(project)
    }
    /**
     * 创建 qgID
     * @param project
     * @param versionName
     * @return
     * versionName_GitHead
     *
     * 应用版本号+ "_"+ git提交号 （前8位）
     *
     */
    static String createQigsawId(Project project, String versionName) {
        try {
            String gitRev = 'git rev-parse --short HEAD'.execute(null, project.rootDir).text.trim()
            if (gitRev == null) {
                return "NO_GIT"
            }
            return "${versionName}_${gitRev}"
        } catch (Exception e) {
            return "${versionName}_NO_GIT"
        }
    }
    /**
     * 获取 abifilter
     * @param project
     * @param variant
     * @return
     */
    static Set<String> getAbiFilters(Project project, def variant) {
        Set<String> mergedAbiFilters = new HashSet<>(0)
        //遍历 productFlavors 下的 abi 声明
        if (project.extensions.android.productFlavors != null) {
            project.extensions.android.productFlavors.each {
                if (variant.flavorName == it.name.capitalize() || variant.flavorName == it.name.uncapitalize()) {
                    Set<String> flavorAbiFilter = it.ndk.abiFilters
                    if (flavorAbiFilter != null) {
                        mergedAbiFilters.addAll(flavorAbiFilter)
                    }
                }
            }
        }
        //ndk 下的 abi 声明
        Set<String> abiFilters = project.extensions.android.defaultConfig.ndk.abiFilters
        if (abiFilters != null) {
            mergedAbiFilters.addAll(abiFilters)
        }
        return mergedAbiFilters
    }

    static void removeRulesAboutMultiDex(Task multiDexTask, ApplicationVariant appVariant) {
        multiDexTask.doFirst {
            FixedMainDexList handler = new FixedMainDexList(appVariant)
            handler.execute()
        }
    }
    /**
     * 获取已安装apk地址
     * app/build.gradle
     * qigsawSplit {
     *    oldApk
     * }
     * @param project
     * @return
     */
    static File getOldApkCompat(Project project) {
        File oldApk = TinkerHelper.getOldApkFile(project)
        if (oldApk == null) {
            oldApk = QigsawSplitExtensionHelper.getOldApkFile(project)
        }
        return oldApk
    }
}
