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
import com.android.tools.build.bundletool.model.Aapt2Command
import com.android.tools.build.bundletool.model.AndroidManifest
import com.iqiyi.qigsaw.buildtool.gradle.internal.entity.SplitInfo
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.FileUtils
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.ManifestReader
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.ApkSigner
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.ZipUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * 用于生成 split_apk
 */
class ProcessSplitApkTask extends DefaultTask {

    ApkSigner apkSigner

    File aapt2File

    @Input
    boolean releaseSplitApk

    @Input
    @Optional
    Set<String> restrictWorkProcessesForSplits

    @Input
    int minApiLevel

    @Input
    String splitVersion

    @Input
    String applicationId

    @Input
    Set<String> splitProjectClassPaths

    @Input
    Set<String> splitProjectDependencies

    @InputFiles
    List<File> splitApks

    @InputDirectory
    File splitManifestDir

    @OutputDirectory
    File splitApksDir

    @OutputDirectory
    File splitInfoDir

    @OutputDirectory
    File unzipSplitApkBaseDir

    @TaskAction
    void processSplitApk() {
        if (splitApks.size() > 1) {
            throw new GradleException("Qigsaw Error: Qigsaw don't support multi-apks.")
        }
        //解压apk
        File unzipSplitApkDir = new File(unzipSplitApkBaseDir, project.name)
        if (unzipSplitApkDir.exists()) {
            FileUtils.deleteDir(unzipSplitApkDir)
        }
        println("ProcessSplitApkTask:unzipSplitApkDir=$unzipSplitApkDir")
        File sourceSplitApk = splitApks[0]
        println("ProcessSplitApkTask:sourceSplitApk=$sourceSplitApk")
        HashMap<String, Integer> compressData = ZipUtils.unzipApk(sourceSplitApk, unzipSplitApkDir)
        //遍历支持的 abi
        Set<String> supportedABIs = new HashSet<>()
        // 拷贝所有 so
        File splitLibsDir = new File(unzipSplitApkDir, "lib")
        if (splitLibsDir.exists()) {
            splitLibsDir.listFiles(new FileFilter() {
                @Override
                boolean accept(File file) {
                    supportedABIs.add(file.name)
                    println("ProcessSplitApkTask:supportedABIs=$file.name")
                    return false
                }
            })
        }
        List<SplitInfo.SplitApkData> apkDataList = new ArrayList<>()
        //二次打包
        Aapt2Command aapt2 = Aapt2Command.createFromExecutablePath(aapt2File.toPath())
        File tmpDir = new File(splitApksDir, "tmp/${project.name}")
        tmpDir.mkdirs()
        //主包支持的文件夹
        supportedABIs.each { String abi ->
            File protoAbiApk = new File(tmpDir, project.name + "-${abi}-proto" + SdkConstants.DOT_ANDROID_PACKAGE)
            File binaryAbiApk = new File(tmpDir, project.name + "-${abi}-binary" + SdkConstants.DOT_ANDROID_PACKAGE)
            //生成新的manifest(只有支持不同cpu架构的)
            File configAndroidManifest = new File(tmpDir, SdkConstants.ANDROID_MANIFEST_XML)
            createSplitConfigApkAndroidManifest(project.name, abi, configAndroidManifest)


            println("ProcessSplitApkTask:protoAbiApk=$protoAbiApk")
            println("ProcessSplitApkTask:binaryAbiApk=:$binaryAbiApk")
            println("ProcessSplitApkTask:configAndroidManifest=$configAndroidManifest")


            Collection<File> resFiles = new ArrayList<>()
            resFiles.add(new File(splitLibsDir, abi))
            resFiles.add(configAndroidManifest)
            ZipUtils.zipFiles(resFiles, unzipSplitApkDir, protoAbiApk, compressData)

            //利用aapt2 工具 将 -proto.apk 利用aapt 工具 写入到 binaryAbiApk中
            aapt2.convertApkProtoToBinary(protoAbiApk.toPath(), binaryAbiApk.toPath())
            File signedAbiApk = new File(splitApksDir, project.name + "-${abi}" + SdkConstants.DOT_ANDROID_PACKAGE)
            if (signedAbiApk.exists()) {
                signedAbiApk.delete()
            }

            println("ProcessSplitApkTask:signedAbiApk=$signedAbiApk")
            //签名
            apkSigner.signApkIfNeed(binaryAbiApk, signedAbiApk)
            //Split 配置
            SplitInfo.SplitApkData configApkData = new SplitInfo.SplitApkData()
            configApkData.abi = abi
            configApkData.url = "assets://qigsaw/${project.name}-${abi + SdkConstants.DOT_ZIP}"
            configApkData.size = signedAbiApk.length()
            configApkData.md5 = FileUtils.getMD5(signedAbiApk)
            apkDataList.add(configApkData)
        }
        //create split master apk
        Collection<File> resFiles = new ArrayList<>()
        //拷贝所有非 so的内容
        File[] files = unzipSplitApkDir.listFiles(new FileFilter() {
            @Override
            boolean accept(File file) {
                //遍历解压包中 找到 lib/ 下所有文件
                return file.name != "lib"
            }
        })
        Collections.addAll(resFiles, files)
        //未包含so的apk
        File unsignedMasterApk = new File(tmpDir, project.name + "-master-unsigned" + SdkConstants.DOT_ANDROID_PACKAGE)
        //生成 featurename-master-unsigned.apk
        ZipUtils.zipFiles(resFiles, unzipSplitApkDir, unsignedMasterApk, compressData)
        //与temp 同级 生成 feature-master.apk
        File signedMasterApk = new File(splitApksDir, project.name + "-master" + SdkConstants.DOT_ANDROID_PACKAGE)
        //签名apk
        apkSigner.signApkIfNeed(unsignedMasterApk, signedMasterApk)
        SplitInfo.SplitApkData masterApkData = new SplitInfo.SplitApkData()
        masterApkData.abi = "master"
        masterApkData.url = "assets://qigsaw/${project.name}-master${SdkConstants.DOT_ZIP}"
        masterApkData.size = signedMasterApk.length()
        masterApkData.md5 = FileUtils.getMD5(signedMasterApk)
        apkDataList.add(masterApkData)
        //create split native-library data list.
        List<SplitInfo.SplitLibData> libDataList = createSplitLibInfo(unzipSplitApkDir)
        //create split-info json file
        File splitInfoFile = new File(splitInfoDir, project.name + SdkConstants.DOT_JSON)
        if (splitInfoFile.exists()) {
            splitInfoFile.delete()
        }
        SplitInfo info = createSplitInfo(apkDataList, libDataList, unzipSplitApkDir)
        //生成split的json配置文件
        FileUtils.createFileForTypeClass(info, splitInfoFile)
        //删除app/build/intermediates/qigsaw/split-outputs/apks/debug/tmp 下面目录
//        FileUtils.deleteDir(tmpDir)
    }

    void createSplitConfigApkAndroidManifest(String splitName, String abi, File androidManifestFile) {
        AndroidManifest androidManifest
        try {
            androidManifest = AndroidManifest.createForConfigSplit(
                    applicationId, splitVersion.split("@")[1].toInteger(), "${splitName}.config.${abi}", splitName, java.util.Optional.of(true))
        } catch (Throwable e) {
            //compat for 4.x
            androidManifest = AndroidManifest.createForConfigSplit(
                    applicationId, java.util.Optional.of(splitVersion.split("@")[1].toInteger()), "${splitName}.config.${abi}", splitName, java.util.Optional.of(true))
        }
        androidManifestFile.withOutputStream {
            it.write(androidManifest.manifestRoot.proto.toByteArray())
        }
    }
    /**
     *
     * @param apkDataList apk数据
     * @param libDataList apk中的lib数据
     * @param unzipSplitApkDir 解压之后的apk数据
     * @return
     */
    SplitInfo createSplitInfo(List<SplitInfo.SplitApkData> apkDataList, List<SplitInfo.SplitLibData> libDataList, File unzipSplitApkDir) {
        Set<String> dependencies = new HashSet<>()
        splitProjectDependencies.each { String name ->
            if (splitProjectClassPaths.contains(name)) {
                dependencies.add(name.split(":")[1])
            }
        }
        File manifest = new File(splitManifestDir, project.name + SdkConstants.DOT_XML)
        if (!manifest.exists()) {
            throw new GradleException("Qigsaw Error: Split manifest ${manifest.absolutePath} is not existing!")
        }
        ManifestReader manifestReader = new ManifestReader(manifest)
        String splitApplicationName = manifestReader.readApplicationName()
        boolean onDemand = manifestReader.readOnDemand()
        boolean builtIn = !onDemand || !releaseSplitApk
        File[] dexFiles = unzipSplitApkDir.listFiles(new FileFilter() {
            @Override
            boolean accept(File file) {
                return file.name.endsWith(".dex") && file.name.startsWith("classes")
            }
        })
        Set<String> splitWorkProcesses = new HashSet<>()
        Set<String> activityProcesses = manifestReader.readActivityProcesses()
        splitWorkProcesses.addAll(activityProcesses)
        Set<String> serviceProcesses = manifestReader.readServiceProcesses()
        splitWorkProcesses.addAll(serviceProcesses)
        Set<String> receiverProcesses = manifestReader.readReceiverProcesses()
        splitWorkProcesses.addAll(receiverProcesses)
        Set<String> providerProcesses = manifestReader.readProviderProcesses()
        splitWorkProcesses.addAll(providerProcesses)

        if (restrictWorkProcessesForSplits != null) {
            if (!restrictWorkProcessesForSplits.contains(project.name)) {
                splitWorkProcesses = null
            }
        } else {
            splitWorkProcesses = null
        }
        if (splitWorkProcesses != null && splitWorkProcesses.empty) {
            splitWorkProcesses = null
        }
        SplitInfo splitInfo = new SplitInfo()
        splitInfo.splitName = project.name
        splitInfo.builtIn = builtIn
        splitInfo.minSdkVersion = minApiLevel
        splitInfo.dexNumber = (dexFiles != null ? dexFiles.length : 0)
        splitInfo.onDemand = onDemand
        splitInfo.version = splitVersion
        splitInfo.applicationName = splitApplicationName == "" ? null : splitApplicationName
        splitInfo.dependencies = dependencies.isEmpty() ? null : dependencies
        splitInfo.workProcesses = splitWorkProcesses
        splitInfo.apkData = apkDataList.isEmpty() ? null : apkDataList
        splitInfo.libData = libDataList.isEmpty() ? null : libDataList
        return splitInfo
    }

    //遍历解压之后的apk文件，找到lib目录生成libData（SplitLibData）
    //"libData": [
    //{
    //    "abi": "x86",
    //    "jniLibs": [
    //        {
    //            "name": "libhello-jni.so",
    //            "md5": "b41ba6efa19ec7367b0440dbbea266f5",
    //            "size": 5564
    //        }
    //]
    //},
    //{
    //    "abi": "arm64-v8a",
    //    "jniLibs": [
    //        {
    //            "name": "libhello-jni.so",
    //            "md5": "2938d8b40825e82715422dbdba479e4f",
    //            "size": 5896
    //        }
    //]
    //}
    //]
    static List<SplitInfo.SplitLibData> createSplitLibInfo(File unzipSplitApkDir) {
        List<SplitInfo.SplitLibData> nativeLibraries = new ArrayList<>(0)
        File[] files = unzipSplitApkDir.listFiles()
        File libDir = null
        for (File file : files) {
            if (file.isDirectory() && "lib" == file.name) {
                libDir = file
                break
            }
        }
        if (libDir == null) {
            return nativeLibraries
        }
        File[] abiDirs = libDir.listFiles()
        for (File abiDir : abiDirs) {
            String abiName = abiDir.name
            File[] soFiles = abiDir.listFiles()
            SplitInfo.SplitLibData libInfo = new SplitInfo.SplitLibData()
            libInfo.abi = abiName
            List<SplitInfo.SplitLibData.Lib> jniLibs = new ArrayList<>()
            for (File soFile : soFiles) {
                if (soFile.name.endsWith(SdkConstants.DOT_NATIVE_LIBS)) {
                    String md5 = FileUtils.getMD5(soFile)
                    SplitInfo.SplitLibData.Lib lib = new SplitInfo.SplitLibData.Lib()
                    lib.name = soFile.name
                    lib.md5 = md5
                    lib.size = soFile.length()
                    jniLibs.add(lib)
                }
            }
            libInfo.jniLibs = jniLibs
            nativeLibraries.add(libInfo)
        }
        return nativeLibraries
    }

}
