package com.iqiyi.qigsaw.buildtool.gradle.task

import com.android.SdkConstants
import com.android.builder.model.SigningConfig
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.CommandUtils
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.FileUtils
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.ApkSigner
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.SplitLogger
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.ZipUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * 检查支持的abi 配置
 * 剔除掉不支持的 so
 * 重新打包到
 * app/build/outputs/apk/{debug/release}
 */
class SplitBaseApkForABIsTask extends DefaultTask {

    static final List<String> SUPPORTED_ABIS = ["armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64"]

    def baseVariant
    //apk 签名工具
    ApkSigner apkSigner

    @Input
    boolean use7z

    @Input
    Set<String> dynamicFeaturesNames
    /**
     * app/build/intermediates/qigsaw/split-details/{name}/base.app.cpu.abilist.properties
     */
    @InputFile
    File baseAppCpuAbiListFile
    /**
     * app/build/outputs/apk/debug/app-debug.apk
     * app/build/outputs/apk/release/app-release.apk
     */
    @InputFiles
    List<File> baseApkFiles
    /**
     * app/build/outputs/apk/debug
     */
    @OutputDirectory
    File packageAppDir

    @OutputDirectory
    File baseApksDir
    //build/intermediates/qigsaw/base-outputs/unzip/debug/
    @OutputDirectory
    File unzipBaseApkDir

    @TaskAction
    void splitBaseApk() {
        if (baseApkFiles.size() > 1) {
            throw new GradleException("Qigsaw Error: Qigsaw don't support multi-apks.")
        }
        if (unzipBaseApkDir.exists()) {
            FileUtils.deleteDir(unzipBaseApkDir)
        }
        if (baseApksDir.exists()) {
            FileUtils.deleteDir(baseApksDir)
        }
        //apk文件
        File baseApk = baseApkFiles[0]
        Properties properties = new Properties()
        if (!baseAppCpuAbiListFile.exists()) {
            throw new GradleException("Unable to find file ${baseAppCpuAbiListFile.absolutePath}")
        }
        //将app/build/intermediates/qigsaw/split-details/{name}/base.app.cpu.abilist.properties
        //数据 转存到 properties 中
        baseAppCpuAbiListFile.withInputStream {
            properties.load(it)
        }
        //读取 abi list 中的内容
        String abiListText = properties."abiList"
        List<String> abiList = abiListText != null ? abiListText.split(",") : null
        if (abiList == null || abiList.isEmpty()) {
            SplitLogger.e("Base apk ${baseApk.absolutePath} has no native-library abi folder, multiple apks don't need.")
            return
        }
        if (abiList.size() == 1) {
            SplitLogger.e("Base apk ${baseApk.absolutePath} has only one native-library abi folder, multiple apks don't need.")
            return
        }
        if (use7z) {
            abiList.add(abiList.join("-"))
        }
        SigningConfig signingConfig = null
        try {
            signingConfig = apkSigner.getSigningConfig()
        } catch (Throwable ignored) {

        }
        boolean isSigningNeed = signingConfig != null && signingConfig.isSigningReady()
        abiList.each { String abi ->
            //每一种abi 架构1个文件
            File unzipBaseApkDirForAbi = new File(unzipBaseApkDir, abi)
            if (unzipBaseApkDirForAbi.exists()) {
                //存在旧的 清空
                FileUtils.deleteDir(unzipBaseApkDirForAbi)
            }
            unzipBaseApkDirForAbi.mkdirs()
            //解压当前 apk 到 build/intermediates/qigsaw/base-outputs/unzip/debug/
            HashMap<String, Integer> compress = ZipUtils.unzipApk(baseApk, unzipBaseApkDirForAbi)
            //abi 在可支持列表中
            if (SUPPORTED_ABIS.contains(abi)) {
                //build/intermediates/qigsaw/base-outputs/unzip/debug/assets/base.app.cpu.abilist.properties
                File baseAppCpuAbiListFileForAbi = new File(unzipBaseApkDirForAbi, "assets/${baseAppCpuAbiListFile.name}")
                //写入支持的 abi架构
                baseAppCpuAbiListFileForAbi.write("abiList=${abi}")
                //遍历当前架构下的 lib/ 所有文件
                File[] libDirs = new File(unzipBaseApkDirForAbi, "lib").listFiles()
                libDirs.each { File abiDir ->
                    if (abiDir.name != abi) {
                        //排除掉用当前 abi 架构不同的所有so
                        FileUtils.deleteDir(abiDir)
                    }
                }
                dynamicFeaturesNames.each { String splitName ->
                    //build/intermediates/qigsaw/base-outputs/unzip/debug/(abi)/assets/qigsaw
                    File baseApkQigsawAssetsDir = new File(unzipBaseApkDirForAbi, "assets/qigsaw")
                    File[] splitApkFiles = baseApkQigsawAssetsDir.listFiles(new FileFilter() {
                        @Override
                        boolean accept(File file) {
                            //如果目录下的文件 已.zip结尾 则添加
                            return file.name.endsWith(SdkConstants.DOT_ZIP)
                        }
                    })
                    if (splitApkFiles != null) {
                        splitApkFiles.each { File file ->
                            //与声明的featureName 相同 且 不包含当前 abi名称 且 不以 -master 开头
                            if (file.name.startsWith(splitName) && !file.name.contains(abi) && !file.name.startsWith("${splitName}-master")) {
                                //删除该文件
                                file.delete()
                            }
                        }
                    }
                }
            }
            File unsignedBaseApk = new File(baseApksDir, "${project.name}-${baseVariant.name.uncapitalize()}-${abi}-${use7z ? "7z" : "non7z"}-unsigned${SdkConstants.DOT_ANDROID_PACKAGE}")
            if (!unsignedBaseApk.parentFile.exists()) {
                unsignedBaseApk.parentFile.mkdirs()
            }
            if (use7z) {
                //7z 压缩
                run7zCmd("7za", "a", "-tzip", unsignedBaseApk.absolutePath, unzipBaseApkDirForAbi.absolutePath + File.separator + "*", "-mx9")
            } else {
                //zip 压缩
                ZipUtils.zipFiles(Arrays.asList(unzipBaseApkDirForAbi.listFiles()), unzipBaseApkDirForAbi, unsignedBaseApk, compress)
            }
            //需要签名
            if (isSigningNeed) {
                //签名apk
                File signedBaseApk = new File(baseApksDir, "${project.name}-${baseVariant.name.uncapitalize()}-${abi}-${use7z ? "7z" : "non7z"}-signed${SdkConstants.DOT_ANDROID_PACKAGE}")
                apkSigner.signApkIfNeed(unsignedBaseApk, signedBaseApk)
                File destBaseApk = new File(packageAppDir, signedBaseApk.name)
                if (destBaseApk.exists()) {
                    destBaseApk.delete()
                }
                //拷贝到app/build/outputs/apk/debug下面
                FileUtils.copyFile(signedBaseApk, destBaseApk)
            } else {
                File destBaseApk = new File(packageAppDir, unsignedBaseApk.name)
                if (destBaseApk.exists()) {
                    destBaseApk.delete()
                }
                //拷贝到app/build/outputs/apk/release下面
                FileUtils.copyFile(unsignedBaseApk, destBaseApk)
            }
        }
    }

    static void run7zCmd(String... cmd) {
        try {
            String cmdResult = CommandUtils.runCmd(cmd)
            SplitLogger.w("Run command successfully, result: " + cmdResult)
        } catch (Throwable e) {
            throw new GradleException("'7za' command is not found, have you install 7zip?", e)
        }
    }
}
