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

class QigsawAppBasePlugin extends QigsawPlugin {

    @Override
    void apply(Project project) {
        //create qigsaw extension.
        project.extensions.create("qigsawSplit", QigsawSplitExtension)
        if (!project.plugins.hasPlugin('com.android.application')) {
            throw new GradleException('Qigsaw Error: Android Application plugin required')
        }
        def versionAGP = VersionNumber.parse(AGPCompat.getAndroidGradlePluginVersionCompat())
        if (versionAGP < VersionNumber.parse("3.2.0")) {
            throw new GradleException('Qigsaw Error: Android Gradle Version is required 3.2.0 at least!')
        }
        boolean isQigsawBuild = isQigsawBuild(project)
        SplitLogger.w("qigsaw build mode? ${isQigsawBuild}")
        def android = project.extensions.android
        //create ComponentInfo.class to record Android Component of dynamic features.
        SplitComponentTransform componentTransform = new SplitComponentTransform(project)
        SplitResourcesLoaderTransform resourcesLoaderTransform = new SplitResourcesLoaderTransform(project, true)
        android.registerTransform(componentTransform)

        if (isQigsawBuild) {
            android.registerTransform(resourcesLoaderTransform)
        }
        //配置完了以后，有一个重要的回调
        project.afterEvaluate {
            if (!AGPCompat.isAapt2EnabledCompat(project)) {
                throw new GradleException('Qigsaw Error: AAPT2 required')
            }
            Set<String> dynamicFeatures = android.dynamicFeatures
            if (dynamicFeatures == null || dynamicFeatures.isEmpty()) {
                throw new GradleException("dynamicFeatures must be set in ${project.name}/build.gradle ")
            }
            Set<String> splitProjectClassPaths = new HashSet<>()
            Set<String> dynamicFeaturesNames = new HashSet<>()
            dynamicFeatures.each {
                Project splitProject = project.rootProject.project(it)
                String classPath = "${splitProject.group}:${splitProject.name}:${splitProject.version}"
                println("QigsawAppBasePlugin:dynamicFeatures:$splitProject.name:$classPath")
                splitProjectClassPaths.add(classPath)
                dynamicFeaturesNames.add(splitProject.name)
            }
            componentTransform.dynamicFeatureNames = dynamicFeaturesNames
            File splitManifestParentDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/split-outputs/manifests")
            componentTransform.splitManifestParentDir = splitManifestParentDir
            android.applicationVariants.all { ApplicationVariant baseVariant ->
                if (baseVariant.versionName == null || baseVariant.applicationId == null) {
                    throw new GradleException("versionName and applicationId must be set in ${project.name}/build.gradle ")
                }
                //versionName+当前git的最后一个commitid
                String qigsawId = createQigsawId(project, baseVariant.versionName)
                //versionName_${qigsawSplit.splitInfoVersion}
                String completeSplitInfoVersion = jointCompleteSplitInfoVersion(project, baseVariant.versionName)
                //获取project中productFlavors下对应的Application的ndk.abiFilters,和defaultConfig.ndk.abiFilters
                Set<String> baseAbiFilters = getAbiFilters(project, baseVariant)
                ApkSigner apkSigner = new ApkSigner(project, baseVariant)

                Task processManifest = AGPCompat.getProcessManifestTask(project, baseVariant.name.capitalize())
                //println("QigsawAppBasePlugin:android.applicationVariants.all:$processManifest")
                Task mergeAssets = AGPCompat.getMergeAssetsTask(project, baseVariant.name.capitalize())
                Task packageApp = AGPCompat.getPackageTask(project, baseVariant.name.capitalize())
                Task baseAssemble = AGPCompat.getAssemble(baseVariant)
                Task generateBuildConfig = AGPCompat.getGenerateBuildConfigTask(project, baseVariant.name.capitalize())
                Task mergeJniLibs = AGPCompat.getMergeJniLibsTask(project, baseVariant.name.capitalize())
                Task r8 = AGPCompat.getR8Task(project, baseVariant.name.capitalize())

                //3.2.x has no bundle_manifest dir
                File bundleManifestDir = AGPCompat.getBundleManifestDirCompat(processManifest, versionAGP)
                File bundleManifestFile = bundleManifestDir == null ? null : new File(bundleManifestDir, SdkConstants.ANDROID_MANIFEST_XML)
                File mergedManifestFile = AGPCompat.getMergedManifestFileCompat(processManifest)
                //app/build/intermediates/merged_assets/debug/out/
                File mergedAssetsDir = new File(AGPCompat.getMergedAssetsBaseDirCompat(mergeAssets))
                //app/build/outputs/apk/debug
                File packageAppDir = AGPCompat.getPackageApplicationDirCompat(packageApp)
                //app/build/intermediates/transforms/mergeJniLibs/debug
                File mergedJniLibsBaseDir = AGPCompat.getMergeJniLibsBaseDirCompat(mergeJniLibs)
                //build/intermediates/qigsaw/old-apk/target-files/{debug/release}
                File targetFilesExtractedDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/old-apk/target-files/${baseVariant.name}")
                File qigsawConfigDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/qigsaw-config/${baseVariant.name}")
                File splitApksDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/split-outputs/apks/${baseVariant.name}")
                File unzipSplitApkBaseDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/split-outputs/unzip/${baseVariant.name}")
                File splitManifestDir = new File(splitManifestParentDir, baseVariant.name)
                //build/intermediates/qigsaw/split-outputs/split-info/debug(release)
                File splitInfoDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/split-outputs/split-info/${baseVariant.name}")
                File qigsawProguardDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/old-outputs/mapping/${baseVariant.name}")
                File splitDetailsDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/split-details/${baseVariant.name}")
                File baseApksDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/base-outputs/apks/${baseVariant.name}")
                //build/intermediates/qigsaw/base-outputs/unzip/debug/
                File unzipBaseApkDir = project.file("${project.buildDir}/${AndroidProject.FD_INTERMEDIATES}/${QIGSAW}/base-outputs/unzip/${baseVariant.name}/${project.name}")

                File splitDetailsFile = new File(splitDetailsDir, "qigsaw" + "_" + completeSplitInfoVersion + SdkConstants.DOT_JSON)
                File updateRecordFile = new File(splitDetailsDir, "_update_record_${SdkConstants.DOT_JSON}")
                File baseAppCpuAbiListFile = new File(splitDetailsDir, "base.app.cpu.abilist${SdkConstants.DOT_PROPERTIES}")
                File oldApk = getOldApkCompat(project)

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

                //create split-details file.
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
                    baseApkFiles.add(it.outputFile)
                    println("QigsawAppBasePlugin:baseVariant.outputs:$it.outputFile")
                }
                QigsawInstallTask qigsawInstall = project.tasks.create("qigsawInstall${baseVariant.name.capitalize()}", QigsawInstallTask)
                qigsawInstall.variantData = baseVariant.variantData
                qigsawInstall.versionAGP = versionAGP
                qigsawInstall.baseApkFiles = baseApkFiles
                qigsawInstall.setGroup(QIGSAW)

                extractTargetFilesFromOldApk.dependsOn processManifest
                qigsawAssemble.dependsOn extractTargetFilesFromOldApk
                generateQigsawConfig.dependsOn extractTargetFilesFromOldApk
                generateQigsawConfig.dependsOn generateBuildConfig
                generateBuildConfig.finalizedBy generateQigsawConfig

                if (isQigsawBuild) {
                    qigsawAssemble.dependsOn mergeJniLibs
                    qigsawAssemble.finalizedBy baseAssemble
                    baseAssemble.dependsOn qigsawAssemble

                    mergeJniLibs.dependsOn mergeAssets
                    qigsawInstall.dependsOn qigsawAssemble
                    qigsawInstall.mustRunAfter baseAssemble

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
                } else {
                    //将feature项目的androidManifest.xml文件拷贝到Users/tanzx/AndroidStudioWorkSpace/GitHub/Qigsaw/app/build/intermediates/qigsaw/split-outputs/manifests/debug
                    dynamicFeatures.each { String dynamicFeature ->
                        Project splitProject = project.rootProject.project(dynamicFeature)
                        ProcessTaskDependenciesBetweenBaseAndSplits taskDependenciesProcessor = new ProcessTaskDependenciesBetweenBaseAndSplits()
                        //该feature是否配置完成，目前这个时候都是false
                        boolean isProjectEvaluated
                        try {
                            splitProject.extensions.android
                            isProjectEvaluated = true
                        } catch (Throwable ignored) {
                            println("tanzhenxing:error:$splitProject:$ignored")
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

    //将feature项目的androidManifest.xml文件拷贝到Qigsaw/app/build/intermediates/qigsaw/split-outputs/manifests/debug
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
            qigsawProcessManifest.dependsOn copySplitManifest
            //ApplicationVersionName
            String versionName = splitVariant.mergedFlavor.versionName
            if (versionName == null) {
                throw new GradleException("Qigsaw Error:versionName must be set in ${splitProject.name}/build.gradle!")
            }
            //println("onTargetVariantFound:$versionName")
            Set<String> splitAbiFilters = getAbiFilters(splitProject, splitVariant)
            if (!baseAbiFilters.isEmpty() && !baseAbiFilters.containsAll(splitAbiFilters)) {
                throw new GradleException("abiFilters config in project ${splitProject.name} must be less than base project.")
            }
            //copy and sign and unzip split apk
            List<File> splitApks = new ArrayList<>()
            splitVariant.outputs.each {
                splitApks.add(it.outputFile)
            }
            //ApplicationVersionName@ApplicationVersionCode
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

    //versionName_${qigsawSplit.splitInfoVersion}
    static String jointCompleteSplitInfoVersion(Project project, String versionName) {
        return versionName + "_" + QigsawSplitExtensionHelper.getSplitInfoVersion(project)
    }

    //versionName+当前git的最后一个commitid
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

    //获取project中productFlavors下对应的Application的ndk.abiFilters,和defaultConfig.ndk.abiFilters
    static Set<String> getAbiFilters(Project project, def variant) {
        Set<String> mergedAbiFilters = new HashSet<>(0)
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

    //获取tinker的oldApk或者qigsaw的oldApk
    static File getOldApkCompat(Project project) {
        File oldApk = TinkerHelper.getOldApkFile(project)
        if (oldApk == null) {
            oldApk = QigsawSplitExtensionHelper.getOldApkFile(project)
        }
        return oldApk
    }
}
