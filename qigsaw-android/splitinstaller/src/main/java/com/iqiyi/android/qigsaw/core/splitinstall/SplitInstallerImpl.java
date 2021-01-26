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

package com.iqiyi.android.qigsaw.core.splitinstall;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.iqiyi.android.qigsaw.core.common.FileUtil;
import com.iqiyi.android.qigsaw.core.common.OEMCompat;
import com.iqiyi.android.qigsaw.core.common.SplitBaseInfoProvider;
import com.iqiyi.android.qigsaw.core.common.SplitConstants;
import com.iqiyi.android.qigsaw.core.common.SplitLog;
import com.iqiyi.android.qigsaw.core.splitreport.SplitInstallError;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfo;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitPathManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dalvik.system.DexClassLoader;

final class SplitInstallerImpl extends SplitInstaller {

    private static final boolean IS_VM_MULTIDEX_CAPABLE = isVMMultiDexCapable(System.getProperty("java.vm.version"));

    private static final String TAG = "SplitInstallerImpl";

    private final Context appContext;

    private final boolean verifySignature;

    SplitInstallerImpl(Context context, boolean verifySignature) {
        this.appContext = context;
        this.verifySignature = verifySignature;
    }

    /**
     * this method executed after download split apk file.
     *
     * split apk file is legal
     * verify signature
     * check split md5
     * generate oat file
     * @param startInstall whether install splits immediately.
     * @param info
     * @return
     * @throws InstallException
     */
    @Override
    public InstallResult install(boolean startInstall, @NonNull SplitInfo info) throws InstallException {
        File splitDir = SplitPathManager.require().getSplitDir(info);
        List<SplitInfo.ApkData> apkDataList;
        SplitInfo.LibData libData;
        String installedMark;
        try {
            apkDataList = info.getApkDataList(appContext);
            libData = info.getPrimaryLibData(appContext);
            installedMark = info.obtainInstalledMark(appContext);
        } catch (IOException e) {
            throw new InstallException(SplitInstallError.INTERNAL_ERROR, e);
        }
        File splitLibDir = null;
        List<String> addedDexPaths = null;
        File optimizedDirectory = null;
        File splitMasterApk = null;
        File markFile = SplitPathManager.require().getSplitMarkFile(info, installedMark);
        for (SplitInfo.ApkData apkData : apkDataList) {
            File splitApk;
            if (info.isBuiltIn() && apkData.getUrl().startsWith(SplitConstants.URL_NATIVE)) {
                splitApk = new File(appContext.getApplicationInfo().nativeLibraryDir, System.mapLibraryName(SplitConstants.SPLIT_PREFIX + info.getSplitName()));
            } else {
                splitApk = new File(splitDir, info.getSplitName() + "-" + apkData.getAbi() + SplitConstants.DOT_APK);
            }
            if (!FileUtil.isLegalFile(splitApk)) {
                throw new InstallException(
                        SplitInstallError.APK_FILE_ILLEGAL,
                        new FileNotFoundException("Split apk " + splitApk.getAbsolutePath() + " is illegal!")
                );
            }
            if (verifySignature) {
                SplitLog.d(TAG, "Need to verify split %s signature!", splitApk.getAbsolutePath());
                verifySignature(splitApk);
            }
            checkSplitMD5(splitApk, apkData.getMd5());
            //extract so file from split apk
            if (!SplitConstants.MASTER.equals(apkData.getAbi())) {
                if (libData != null) {
                    splitLibDir = SplitPathManager.require().getSplitLibDir(info, libData.getAbi());
                    extractLib(splitApk, splitLibDir, libData);
                }
            } else {
                splitMasterApk = splitApk;
                if (info.hasDex()) {
                    //Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}/oat
                    optimizedDirectory = SplitPathManager.require().getSplitOptDir(info);
                    addedDexPaths = new ArrayList<>();
                    addedDexPaths.add(splitApk.getAbsolutePath());
                    // can't support multi dex, No need to consider
                    if (!isVMMultiDexCapable()) {
                        if (info.isMultiDex()) {
                            //Qigsaw / {$ gigsawid} / {$ splitname} / {$ {splitversion}} / code_cache
                            File codeCacheDir = SplitPathManager.require().getSplitCodeCacheDir(info);
                            //dex zip file
                            addedDexPaths.addAll(extractMultiDex(splitApk, codeCacheDir, info));
                        }
                    }
                    String dexPath = TextUtils.join(File.pathSeparator, addedDexPaths);
                    String librarySearchPath = splitLibDir == null ? null : splitLibDir.getAbsolutePath();
                    //trigger oat if need
                    if (!markFile.exists()) {
                        try {
                            new DexClassLoader(dexPath, optimizedDirectory.getAbsolutePath(), librarySearchPath, SplitInstallerImpl.class.getClassLoader());
                        } catch (Throwable error) {
                            throw new InstallException(
                                    SplitInstallError.CLASSLOADER_CREATE_FAILED,
                                    error);
                        }
                    }
                    //check oat file. We found many native crash in libart.so, especially vivo & oppo.
                    //current sdk version >20 and current sdk version < 26
                    if (OEMCompat.shouldCheckOatFileInCurrentSys()) {
                        SplitLog.v(TAG, "Start to check oat file, current api level is " + Build.VERSION.SDK_INT);
                        boolean specialManufacturer = OEMCompat.isSpecialManufacturer();
                        //optimizedDirectory=Qigsaw/{$gigsawid}/{$splitname}/{${splitversion}}/oat
                        File oatFile = OEMCompat.getOatFilePath(splitApk, optimizedDirectory);
                        //check oat file
                        if (FileUtil.isLegalFile(oatFile)) {
                            boolean checkResult = OEMCompat.checkOatFile(oatFile);
                            SplitLog.v(TAG, "Result of oat file %s is " + checkResult, oatFile.getAbsoluteFile());
                            if (!checkResult) {
                                SplitLog.w(TAG, "Failed to check oat file " + oatFile.getAbsolutePath());
                                if (specialManufacturer) {
                                    File lockFile = SplitPathManager.require().getSplitSpecialLockFile(info);
                                    try {
                                        FileUtil.deleteFileSafelyLock(oatFile, lockFile);
                                    } catch (IOException error) {
                                        SplitLog.w(TAG, "Failed to delete corrupted oat file " + oatFile.exists());
                                    }
                                } else {
                                    FileUtil.deleteFileSafely(oatFile);
                                }
                                throw new InstallException(
                                        SplitInstallError.DEX_OAT_FAILED,
                                        new FileNotFoundException("System generate split " + info.getSplitName() + " oat file failed!")
                                );
                            }
                        } else {
                            if (specialManufacturer) {
                                SplitLog.v(TAG, "Oat file %s is not exist in vivo & oppo, system would use interpreter mode.", oatFile.getAbsoluteFile());
                                File specialMarkFile = SplitPathManager.require().getSplitSpecialMarkFile(info, installedMark);
                                if (!markFile.exists() && !specialMarkFile.exists()) {
                                    File lockFile = SplitPathManager.require().getSplitSpecialLockFile(info);
                                    boolean firstInstalled = createInstalledMarkLock(specialMarkFile, lockFile);
                                    return new InstallResult(info.getSplitName(), splitApk, optimizedDirectory, splitLibDir, addedDexPaths, firstInstalled);
                                }
                            }
                        }
                    }
                }
            }
        }
        assert splitMasterApk != null;
        boolean firstInstalled = createInstalledMark(markFile);
        return new InstallResult(info.getSplitName(), splitMasterApk, optimizedDirectory, splitLibDir, addedDexPaths, firstInstalled);
    }

    @Override
    protected void verifySignature(File splitApk) throws InstallException {
        if (!SignatureValidator.validateSplit(appContext, splitApk)) {
            deleteCorruptedFiles(Collections.singletonList(splitApk));
            throw new InstallException(
                    SplitInstallError.SIGNATURE_MISMATCH,
                    new SignatureException("Failed to check split apk " + splitApk.getAbsolutePath() + " signature!")
            );
        }
    }

    @Override
    protected void checkSplitMD5(File splitApk, String splitApkMd5) throws InstallException {
        String curMd5 = FileUtil.getMD5(splitApk);
        if (!splitApkMd5.equals(curMd5)) {
            deleteCorruptedFiles(Collections.singletonList(splitApk));
            throw new InstallException(SplitInstallError.MD5_ERROR, new IOException("Failed to check split apk md5, expect " + splitApkMd5 + " but " + curMd5));
        }
    }

    /**
     *
     * @param splitApk     file of split apk.
     * @param codeCacheDir directory of split dex files:
     * @param splitInfo    {@link SplitInfo}
     * @return
     * @throws InstallException
     */
    @Override
    protected List<String> extractMultiDex(File splitApk, File codeCacheDir, @NonNull SplitInfo splitInfo) throws InstallException {
        SplitLog.w(TAG,
                "VM do not support multi-dex, but split %s has multi dex files, so we need install other dex files manually",
                splitApk.getName());
        //splitName@splitUpdateVersion@splitVersion
        String prefsKeyPrefix = splitInfo.getSplitName() + "@" + SplitBaseInfoProvider.getVersionName() + "@" + splitInfo.getSplitVersion();
        try {
            SplitMultiDexExtractor extractor = new SplitMultiDexExtractor(splitApk, codeCacheDir);
            try {
                List<? extends File> dexFiles = extractor.load(appContext, prefsKeyPrefix, false);
                List<String> dexPaths = new ArrayList<>(dexFiles.size());
                for (File dexFile : dexFiles) {
                    dexPaths.add(dexFile.getAbsolutePath());
                }
                SplitLog.w(TAG, "Succeed to load or extract dex files", dexFiles.toString());
                return dexPaths;
            } catch (IOException e) {
                SplitLog.w(TAG, "Failed to load or extract dex files", e);
                throw new InstallException(SplitInstallError.DEX_EXTRACT_FAILED, e);
            } finally {
                FileUtil.closeQuietly(extractor);
            }
        } catch (IOException ioError) {
            throw new InstallException(SplitInstallError.DEX_EXTRACT_FAILED, ioError);
        }
    }

    /**
     * extract so file from split apk
     * @param splitApk file of split apk.
     * @param libDir   directory of split so files.
     * @param libData  {@link SplitInfo.LibData}
     * @throws InstallException
     */
    @Override
    protected void extractLib(File splitApk, File libDir, @NonNull SplitInfo.LibData libData) throws InstallException {
        try {
            SplitLibExtractor extractor = new SplitLibExtractor(splitApk, libDir);
            try {
                List<File> libFiles = extractor.load(libData, false);
                SplitLog.i(TAG, "Succeed to extract libs:  %s", libFiles.toString());
            } catch (IOException e) {
                SplitLog.w(TAG, "Failed to load or extract lib files", e);
                throw new InstallException(SplitInstallError.LIB_EXTRACT_FAILED, e);
            } finally {
                FileUtil.closeQuietly(extractor);
            }
        } catch (IOException ioError) {
            throw new InstallException(SplitInstallError.LIB_EXTRACT_FAILED, ioError);
        }
    }

    @Override
    protected boolean createInstalledMark(File markFile) throws InstallException {
        if (!markFile.exists()) {
            try {
                FileUtil.createFileSafely(markFile);
                return true;
            } catch (IOException e) {
                throw new InstallException(SplitInstallError.MARK_CREATE_FAILED, e);
            }
        }
        return false;
    }

    @Override
    protected boolean createInstalledMarkLock(File markFile, File lockFile) throws InstallException {
        if (!markFile.exists()) {
            try {
                FileUtil.createFileSafelyLock(markFile, lockFile);
                return true;
            } catch (IOException e) {
                throw new InstallException(SplitInstallError.MARK_CREATE_FAILED, e);
            }
        }
        return false;
    }

    /**
     * Estimate whether current platform supports multi dex.
     *
     * @return {@code true} if supports multi dex, otherwise {@code false}
     */
    private boolean isVMMultiDexCapable() {
        return IS_VM_MULTIDEX_CAPABLE;
    }

    /**
     * Delete corrupted files if split apk installing failed.
     *
     * @param files list of corrupted files
     */
    private void deleteCorruptedFiles(List<File> files) {
        for (File file : files) {
            FileUtil.deleteFileSafely(file);
        }
    }

    private static boolean isVMMultiDexCapable(String versionString) {
        boolean isMultiDexCapable = false;
        if (versionString != null) {
            Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)(\\.\\d+)?").matcher(versionString);
            if (matcher.matches()) {
                try {
                    int major = Integer.parseInt(matcher.group(1));
                    int minor = Integer.parseInt(matcher.group(2));
                    isMultiDexCapable = major > 2 || major == 2 && minor >= 1;
                } catch (NumberFormatException var5) {
                    //ignored
                }
            }
        }
        SplitLog.i("Split:MultiDex", "VM with version " + versionString + (isMultiDexCapable ? " has multidex support" : " does not have multidex support"));
        return isMultiDexCapable;
    }
}
