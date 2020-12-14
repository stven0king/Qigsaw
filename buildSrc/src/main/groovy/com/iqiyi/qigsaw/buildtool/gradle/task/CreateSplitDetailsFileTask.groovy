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
import com.iqiyi.qigsaw.buildtool.gradle.extension.QigsawSplitExtensionHelper
import com.iqiyi.qigsaw.buildtool.gradle.internal.entity.SplitDetails
import com.iqiyi.qigsaw.buildtool.gradle.internal.entity.SplitInfo
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.FileUtils
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.SplitLogger
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.TypeClassFileParser
import com.iqiyi.qigsaw.buildtool.gradle.upload.SplitApkUploadException
import com.iqiyi.qigsaw.buildtool.gradle.upload.SplitApkUploader
import com.iqiyi.qigsaw.buildtool.gradle.upload.SplitApkUploaderInstance
import org.codehaus.plexus.util.dag.DAG
import org.codehaus.plexus.util.dag.TopologicalSorter
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * 将各个Feature Moudle生成的插件apk转换为zip拷贝到assets目录下，
 * 如果插件apk不需要内置则可以上传到服务端
 *
 * 将所有feature包 和 配置信息  拷贝到 app/build/intermediates/merged_assets/debug/out/qigsaw/
 *
 *
 * @input 设置输入属性
 * @Optional 可选配置
 *
 */
class CreateSplitDetailsFileTask extends ProcessOldOutputsBaseTask {
    /**
     * 应用版本号_git提交号
     */
    @Input
    String qigsawId
    /**
     * defaultConfig{
     *    versionName
     * }
     */
    @Input
    String baseVersionName
    /**
     * defaultConfig{ versionName} + "_" + qigsawSplit{splitInfoVersion}
     */
    @Input
    String completeSplitInfoVersion
    /**
     * 遍历 productFlavors 下的 abi 声明
     */
    @Input
    Set<String> abiFilters
    /**
     * android{
     *      dynamicFeatures [声明的所有工程（名称）]
     * }
     */
    @Input
    Set<String> dynamicFeaturesNames

    @Input
    @Optional
    Set<String> splitEntryFragments
    /**
     * app/build/intermediates/qigsaw/split-outputs/apks/
     * 分割apk的缓存目录
     */
    @InputDirectory
    File splitApksDir
    /**
     * build/intermediates/qigsaw/split-outputs/split-info/debug(release)
     */
    @InputDirectory
    File splitInfoDir
    /**
     * app/build/intermediates/transforms/mergeJniLibs/debug
     */
    @InputDirectory
    File mergedJniLibsBaseDir
    /**
     * app/build/intermediates/qigsaw/split-details/debug/
     * qigsaw_defaultConfig{ versionName} + "_" + qigsawSplit{splitInfoVersion}.json
     */
    @OutputFile
    File splitDetailsFile
    /**
     * app/build/intermediates/qigsaw/split-details/{name}/_update_record_.json
     */
    @OutputFile
    File updateRecordFile
    /**
     * app/build/intermediates/qigsaw/split-details/{name}/base.app.cpu.abilist.properties
     */
    @OutputFile
    File baseAppCpuAbiListFile
    /**
     *app/build/intermediates/merged_assets/debug/out/qigsaw
     */
    @OutputDirectory
    File qigsawMergedAssetsDir

    CreateSplitDetailsFileTask() {
        this.splitEntryFragments = QigsawSplitExtensionHelper.getSplitEntryFragments(project)
    }

    @TaskAction
    void doCreation() {
        if (splitDetailsFile.exists()) {
            splitDetailsFile.delete()
        }
        println("CreateSplitDetailsFileTask:$splitDetailsFile")
        if (updateRecordFile.exists()) {
            updateRecordFile.delete()
        }
        println("CreateSplitDetailsFileTask:$updateRecordFile")
        if (baseAppCpuAbiListFile.exists()) {
            baseAppCpuAbiListFile.delete()
        }
        println("CreateSplitDetailsFileTask:$baseAppCpuAbiListFile")
        if (qigsawMergedAssetsDir.exists()) {
            FileUtils.deleteDir(qigsawMergedAssetsDir)
        }
        //创建目录
        qigsawMergedAssetsDir.mkdirs()
        // 所有的Feature 信息
        List<SplitInfo> splitInfoList = new ArrayList<>()
        dynamicFeaturesNames.each {
            //遍历 app/build/intermediates/qigsaw/split-outputs/split-info/debug/ 下所有 .json 配置文件
            File splitInfoFile = new File(splitInfoDir, it + SdkConstants.DOT_JSON)
            if (!splitInfoFile.exists()) {
                throw new GradleException("Qigsaw Error: split-info file ${splitInfoFile.absolutePath} is not existing!")
            }
            //存储到信息到对象中 （所有Feature 的所有配置）
            SplitInfo splitInfo = TypeClassFileParser.parseFile(splitInfoFile, SplitInfo)
            splitInfoList.add(splitInfo)
        }
        splitInfoList.each {
            it.dependencies = fixSplitDependencies(it.splitName, splitInfoList)
        }
        //app/build/intermediates/qigsaw/old-apk/target-files/{debug/release}/assets/qigsaw/qigsaw_1.0.0_1.0.0.json
        File oldSplitDetailsFile = getOldSplitDetailsFile()
        //feature 信息（是否有变更）
        SplitDetails details = createSplitDetails(splitInfoList, oldSplitDetailsFile)
        //格式化数据内容
        details.splits = rearrangeSplits(details.splits)
        //将对象信息 写入文件中 （app/build/intermediates/qigsaw/split-details/{name}/qigsaw_v_v.json）记录变更信息
        FileUtils.createFileForTypeClass(details, splitDetailsFile)
        //将对象信息 写入文件中 (app/build/intermediates/qigsaw/split-details/{name}/_update_record_.json) 记录变更的数量
        FileUtils.createFileForTypeClass(details.updateRecord, updateRecordFile)
        //拷贝数据到app/build/intermediates/merged_assets/debug/out/qigsaw 下
        moveOutputsToMergedAssetsDir(oldSplitDetailsFile, details)
    }

    /**
     * 将 app/build/intermediates/qigsaw/split-outputs/apks/debug/*.apk
     * 或者
     * app/build/intermediates/qigsaw/old-apk/target-files/debug/assets/qigsaw/*.zip
     * 拷贝到
     * app/build/intermediates/merged_assets/debug/out/qigsaw/*.zip
     *
     *
     * @param oldSplitDetailsFile  app/build/intermediates/qigsaw/old-apk/target-files/{debug/release}/assets/qigsaw/qigsaw_1.0.0_1.0.0.json
     * @param splitDetails Feature 的信息
     * 配置描述文件输出地址
     * app/build/intermediates/merged_assets/debug/out/qigsaw/qigsaw_v_v.json
     * feature包输出地址
     * app/build/intermediates/merged_assets/debug/out/qigsaw/
     */
    void moveOutputsToMergedAssetsDir(File oldSplitDetailsFile, SplitDetails splitDetails) {
        //app/build/intermediates/merged_assets/debug/out/qigsaw/qigsaw_v_v.json
        File destSplitDetailsFile = new File(qigsawMergedAssetsDir, "qigsaw_${completeSplitInfoVersion + SdkConstants.DOT_JSON}")
        if (splitDetails.updateRecord.updateMode == SplitDetails.UpdateRecord.VERSION_NO_CHANGED) {
            //如果Feature 未发生变动 拷贝app/build/intermediates/qigsaw/old-apk/target-files/{debug/release}/assets/qigsaw/qigsaw_1.0.0_1.0.0.json
            FileUtils.copyFile(oldSplitDetailsFile, destSplitDetailsFile)
        } else {
            //如果Feature 发生变动 拷贝 app/build/intermediates/qigsaw/split-details/{debug/release}/qigsaw_1.0.0_1.0.0.json
            FileUtils.copyFile(splitDetailsFile, destSplitDetailsFile)
        }
        //所有支持abi 的so名称
        Set<String> mergedAbiFilters = getMergedAbiFilters()
        //向app/build/intermediates/qigsaw/split-details/debug/base.app.cpu.abilist.properties 写入支持的 abi
        baseAppCpuAbiListFile.write("abiList=${mergedAbiFilters.join(",")}")
        //拷贝上一个配置文件到 app/build/intermediates/merged_assets/debug/out/  下面
        FileUtils.copyFile(baseAppCpuAbiListFile, new File(qigsawMergedAssetsDir.parentFile, baseAppCpuAbiListFile.name))

        splitDetails.splits.each { SplitInfo info ->
            if (info.builtIn) {
                //feature 版本发生变更 遍历.json 中 apkData 数组
                info.apkData.each {
                    //app/build/intermediates/merged_assets/debug/out/qigsaw/ 创建zip文件  featureName-apkData.abi .zip
                    File destSplitApk = new File(qigsawMergedAssetsDir, "${info.splitName}-${it.abi + SdkConstants.DOT_ZIP}")
                    //updateMode 发生或配置  （经过之前的检查）
                    if (splitDetails.updateRecord.updateMode != SplitDetails.UpdateRecord.DEFAULT) {
                        File sourceSplitApk
                        if (splitDetails.updateRecord.updateSplits != null && splitDetails.updateRecord.updateSplits.contains(info.splitName)) {
                            //Built-in apk version has been changed.  feature 发生变更
                            //读取目标 apk  app/build/intermediates/qigsaw/split-outputs/apks/native-arm64-v8a.apk
                            sourceSplitApk = new File(splitApksDir, "${info.splitName}-${it.abi + SdkConstants.DOT_ANDROID_PACKAGE}")
                        } else {
                            //app/build/intermediates/qigsaw/old-apk/target-files/{debug/release}/assets/qigsaw/ 下的 .zip
                            sourceSplitApk = getOldSplitApk(info.splitName, it.abi)
                        }
                        if (!sourceSplitApk.exists()) {
                            throw new GradleException("Split apk ${sourceSplitApk.absolutePath} is not found, mode changed!")
                        }
                        //将app/build/intermediates/qigsaw/split-outputs/apks/debug (apk/zip)
                        //拷贝到  app/build/intermediates/merged_assets/debug/out/qigsaw/debug  (zip)
                        FileUtils.copyFile(sourceSplitApk, destSplitApk)
                    } else {
                        //未执行版本变更配置的
                        File sourceSplitApk = new File(splitApksDir, "${info.splitName}-${it.abi + SdkConstants.DOT_ANDROID_PACKAGE}")
                        if (!sourceSplitApk.exists()) {
                            throw new GradleException("Split apk ${sourceSplitApk.absolutePath} is not found, mode defalut!")
                        }
                        // 将 app/build/intermediates/qigsaw/split-outputs/apks/debug/*.apk
                        // 拷贝到 app/build/intermediates/merged_assets/debug/out/qigsaw/ *.zip
                        FileUtils.copyFile(sourceSplitApk, destSplitApk)
                    }
                }
            }
        }
    }

    /**
     * 获取到 build.gradle  productFlavors 下的 abi
     * 匹配的所有 so 名称
     * @return
     */
    Set<String> getMergedAbiFilters() {
        File mergedJniLibsDir = getMergedJniLibsDirCompat()
        Set<String> realABIs = new HashSet<>()
        //如果目录存在
        if (mergedJniLibsDir.exists()) {
            mergedJniLibsDir.listFiles(new FileFilter() {
                @Override
                boolean accept(File file) {
                    //build.gradle 声明的 abilist 为null
                    if (abiFilters.isEmpty()) {
                        //添加so名称
                        realABIs.add(file.name)
                    } else {
                        //build.gradle 声明的 abilist 不为null
                        if (abiFilters.contains(file.name)) {
                            //值添加声明的 so名称
                            realABIs.add(file.name)
                        }
                    }
                    return false
                }
            })
        }
        return realABIs
    }
    /**
     * 返回 所有Feature 下 lib 内容的目录
     * 默认地址： app/build/intermediates/transforms/mergeJniLibs/debug/lib
     * 已有版本  app/build/intermediates/transforms/mergeJniLibs/debug/ver(int)/lib
     * @return
     */
    File getMergedJniLibsDirCompat() {
        File mergedJniLibsDir
        File __content__ = new File(mergedJniLibsBaseDir, "__content__.json")
        if (__content__.exists()) {
            List result = TypeClassFileParser.parseFile(__content__, List.class)
            mergedJniLibsDir = new File(mergedJniLibsBaseDir, "${(int) (result.get(0).index)}/lib")
        } else {
            mergedJniLibsDir = new File(mergedJniLibsBaseDir, "lib")
        }
        return mergedJniLibsDir
    }
    /**
     * 根据配置文件 生成 子feature 配置
     * @param splitInfoList 所有的Feature 信息
     * @param oldSplitDetailsFile  apk 打包时的 版本号 和split 信息
     * @return
     * 如果不需要更新  返回 SplitDetails  其中 oldSplitDetails.updateRecord.updateMode = SplitDetails.UpdateRecord.VERSION_NO_CHANGED
     * 如果性需要更新  先上传 有变更的Feature.apk 然后 返回SplitDetails 其中 splitDetails.updateRecord.updateMode = SplitDetails.UpdateRecord.VERSION_CHANGED
     */
    SplitDetails createSplitDetails(List<SplitInfo> splitInfoList, File oldSplitDetailsFile) {
        String qigsawId = this.qigsawId
        //需要更新的Feature记录
        SplitDetails.UpdateRecord updateRecord = new SplitDetails.UpdateRecord()
        if (oldSplitDetailsFile != null && oldSplitDetailsFile.exists()) {
            //文件数据转对象
            SplitDetails oldSplitDetails = TypeClassFileParser.parseFile(oldSplitDetailsFile, SplitDetails)

            if (hasSplitVersionChanged(oldSplitDetails.splits, splitInfoList)) {
                //当前构建版本和上一次发布版本 Feature有版本更新
                qigsawId = oldSplitDetails.qigsawId
                //所有需要更新的Feature (name)  有版本变更的 builtIn = false
                List<String> updatedSplits = processAndAnalyzeUpdatedSplits(oldSplitDetails.splits, splitInfoList)
                updateRecord.updateMode = SplitDetails.UpdateRecord.VERSION_CHANGED
                updateRecord.updateSplits = updatedSplits
                SplitLogger.w("Splits ${updatedSplits} need to be updated!")
            } else {
                updateRecord.updateMode = SplitDetails.UpdateRecord.VERSION_NO_CHANGED
                SplitLogger.w("No splits need to be updated, just using old Apks!")
                oldSplitDetails.updateRecord = updateRecord
                return oldSplitDetails
            }
        }
        //需要更新 则将最新的feature 上传
        splitInfoList.each { SplitInfo info ->
            uploadSplitApkIfNeed(info)
        }
        SplitDetails splitDetails = new SplitDetails()
        splitDetails.updateRecord = updateRecord
        splitDetails.qigsawId = qigsawId
        splitDetails.appVersionName = baseVersionName
        splitDetails.updateSplits = updateRecord.updateSplits
        splitDetails.splitEntryFragments = splitEntryFragments
        splitDetails.splits = splitInfoList
        return splitDetails
    }
    /**
     * 插件上传需要 feature 模块配置 dist:onDemand="true" 与 releaseSplitApk = true 不然不会触发上传逻辑
     *  有版本变更的 Feature 通过上传接口
     *  上传 有变更的 splitName.apk 到指定url
     *  @param info
     */
    void uploadSplitApkIfNeed(SplitInfo info) {
        if (!info.builtIn) {
            //上传工具
            SplitApkUploader uploader = SplitApkUploaderInstance.get()
            if (uploader != null) {
                for (SplitInfo.SplitApkData data : info.apkData) {
                    if (!data.url.startsWith("http")) {
                        File apkFile = new File(splitApksDir, info.splitName + "-${data.abi + SdkConstants.DOT_ANDROID_PACKAGE}")
                        if (!apkFile.exists()) {
                            throw new GradleException("Split apk ${apkFile.absolutePath} is not existing!")
                        }
                        //上传文件
                        String uploadedUrl = uploader.uploadSync(project, apkFile, info.splitName)
                        if (uploadedUrl != null && uploadedUrl.startsWith("http")) {
                            data.url = uploadedUrl
                            SplitLogger.w("Split apk ${apkFile.absolutePath} upload successfully, url: ${uploadedUrl}")
                        } else {
                            throw new SplitApkUploadException("Split apk ${apkFile.absolutePath} upload failed, url: ${uploadedUrl}")
                        }
                    } else {
                        SplitLogger.w("Split ${info.splitName} has been uploaded: ${data.url}")
                    }
                }
            } else {
                SplitLogger.e("SplitApkUploader has not been implemented, just make ${info.splitName} built-in")
                info.builtIn = true
            }
        }
    }
    /**
     * 对 feature 信息 重新排序
     * @param splitInfoList
     * @return
     */
    static List<SplitInfo> rearrangeSplits(List<SplitInfo> splitInfoList) {
        DAG dag = new DAG()
        for (SplitInfo info : splitInfoList) {
            if (info.dependencies != null) {
                for (String dependency : info.dependencies) {
                    dag.addEdge(info.splitName, dependency)
                }
            }
        }
        List<String> sorted = TopologicalSorter.sort(dag)
        SplitLogger.w("> topological sort result: " + sorted)
        List<SplitInfo> ret = new ArrayList<>()
        sorted.each { String name ->
            SplitInfo temp = null
            splitInfoList.each { SplitInfo info ->
                if (info.splitName == name) {
                    temp = info
                    return
                }
            }
            ret.add(temp)
            splitInfoList.remove(temp)
        }
        ret.addAll(splitInfoList)
        return ret
    }
    /**
     *
     * @param name splitName
     * @param splitInfoList 信息对象
     * @return 修改split 依赖关系
     */
    static Set<String> fixSplitDependencies(String name, List<SplitInfo> splitInfoList) {
        Set<String> dependencies = null
        splitInfoList.each { SplitInfo info ->
            //与feature name 相同
            if (info.splitName == name) {
                dependencies = info.dependencies
                return
            }
        }
        if (dependencies == null) {
            return null
        }
        Set<String> fixedDependencies = new HashSet<>()
        fixedDependencies.addAll(dependencies)
        dependencies.each {
            Set<String> ret = fixSplitDependencies(it, splitInfoList)
            if (ret != null) {
                fixedDependencies.addAll(ret)
            }
        }
        return fixedDependencies
    }
    /**
     * split版本 是否发生变化
     *
     * @param oldSplits  已发布版本  Feature 信息
     * @param newSplits  当前构建版本 Feature 信息
     * @return
     * splitName 相同 且 version（字符） 不相同
     * 则 split版本有 变更
     */
    static boolean hasSplitVersionChanged(List<SplitInfo> oldSplits, List<SplitInfo> newSplits) {
        boolean versionChanged = false
        if (oldSplits != null) {
            newSplits.each { SplitInfo newInfo ->
                oldSplits.each { SplitInfo oldInfo ->
                    if (newInfo.splitName == oldInfo.splitName) {
                        if (newInfo.version != oldInfo.version) {
                            versionChanged = true
                        }
                    }
                }
            }
        }
        return versionChanged
    }


    /**
     * 本次构建的 Feature版本和 上次发布的 Feature 进行比对
     * 如果Feature的version 不同 则 更改当前Feature 的buildIn(baseapk是否集成) 的值为false
     * @param oldSplits 上一次版本中的 Feature内容
     * @param splits 本次构建的 Feature内容
     * @return
     * 返回需要更新的 Featuer name 集合
     *
     */
    static List<String> processAndAnalyzeUpdatedSplits(List<SplitInfo> oldSplits, List<SplitInfo> splits) {
        List<String> updateSplits = new ArrayList<>(0)
        List<SplitInfo> newSplits = new ArrayList<>()
        splits.each { info ->
            oldSplits.each { oldInfo ->
                if (info.splitName == oldInfo.splitName) {
                    if (info.version == oldInfo.version) {
                        //如果版本相同 采用已发布版本
                        newSplits.add(oldInfo)
                        SplitLogger.w("Split ${info.splitName} version ${info.version} is not changed, using old info!")
                    } else {
                        //如果版本不同 修改 builtIn 属性为false
                        SplitInfo newInfo = info.clone()
                        newInfo.builtIn = false
                        newInfo.onDemand = true
                        newSplits.add(newInfo)
                        updateSplits.add(info.splitName)
                        SplitLogger.w("Split ${info.splitName} version ${info.version} is changed, it need to be updated!")
                    }
                }
            }
        }
        splits.clear()
        splits.addAll(newSplits)
        return updateSplits
    }

}
