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

package com.iqiyi.android.qigsaw.core.splitrequest.splitinfo;

import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.text.TextUtils;

import com.iqiyi.android.qigsaw.core.common.FileUtil;
import com.iqiyi.android.qigsaw.core.common.SplitConstants;
import com.iqiyi.android.qigsaw.core.common.SplitLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class SplitInfoManagerImpl implements SplitInfoManager {

    private static final String TAG = "SplitInfoManagerImpl";

    private AtomicReference<SplitDetails> splitDetailsRef = new AtomicReference<>();

    private SplitInfoVersionManager versionManager;

    void attach(SplitInfoVersionManager versionManager) {
        this.versionManager = versionManager;
    }

    private SplitInfoVersionManager getSplitInfoVersionManager() {
        return versionManager;
    }

    private SplitDetails getSplitDetails() {
        return splitDetailsRef.get();
    }

    @Override
    @Nullable
    public String getBaseAppVersionName(Context context) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            return details.getAppVersionName();
        }
        return null;
    }

    @Override
    @Nullable
    public String getQigsawId(Context context) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            return details.getQigsawId();
        }
        return null;
    }

    @Override
    @Nullable
    public List<String> getUpdateSplits(Context context) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            return details.getUpdateSplits();
        }
        return null;
    }

    @Override
    public List<String> getSplitEntryFragments(Context context) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            return details.getSplitEntryFragments();
        }
        return null;
    }

    @Override
    public SplitInfo getSplitInfo(Context context, String splitName) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            Collection<SplitInfo> splits = details.getSplitInfoListing().getSplitInfoMap().values();
            for (SplitInfo split : splits) {
                if (split.getSplitName().equals(splitName)) {
                    return split;
                }
            }
        }
        return null;
    }

    @Override
    public List<SplitInfo> getSplitInfos(Context context, Collection<String> splitNames) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            Collection<SplitInfo> splits = details.getSplitInfoListing().getSplitInfoMap().values();
            List<SplitInfo> splitInfos = new ArrayList<>(splitNames.size());
            for (SplitInfo split : splits) {
                if (splitNames.contains(split.getSplitName())) {
                    splitInfos.add(split);
                }
            }
            return splitInfos;
        }
        return null;
    }

    /**
     * 获取json配置中所有的split信息
     * @param context get all split info
     * @return
     */
    @Override
    public Collection<SplitInfo> getAllSplitInfo(Context context) {
        SplitDetails details = getOrCreateSplitDetails(context);
        if (details != null) {
            return details.getSplitInfoListing().getSplitInfoMap().values();
        }
        return null;
    }

    /**
     * 更具json文件路径创建SplitDetails实例
     * @param newSplitInfoPath file path of new split info
     * @return
     */
    @Override
    @Nullable
    public SplitDetails createSplitDetailsForJsonFile(@NonNull String newSplitInfoPath) {
        File newSplitInfoFile = new File(newSplitInfoPath);
        if (newSplitInfoFile.exists()) {
            return createSplitDetailsForNewVersion(newSplitInfoFile);
        }
        return null;
    }

    @Override
    public String getCurrentSplitInfoVersion() {
        SplitInfoVersionManager versionManager = getSplitInfoVersionManager();
        return versionManager.getCurrentVersion();
    }

    @Override
    public boolean updateSplitInfoVersion(Context context, String newSplitInfoVersion, File newSplitInfoFile) {
        SplitInfoVersionManager versionManager = getSplitInfoVersionManager();
        return versionManager.updateVersion(context, newSplitInfoVersion, newSplitInfoFile);
    }

    /**
     * 用asset下默认的Qigsaw配置文件生成SplitDetails对象
     * @param context
     * @param defaultVersion
     * @return
     */
    private SplitDetails createSplitDetailsForDefaultVersion(Context context, String defaultVersion) {
        try {
            String defaultSplitInfoFileName = SplitConstants.QIGSAW + "/" + SplitConstants.QIGSAW_PREFIX + defaultVersion + SplitConstants.DOT_JSON;
            SplitLog.i(TAG, "Default split file name: " + defaultSplitInfoFileName);
            long currentTime = System.currentTimeMillis();
            SplitDetails details = parseSplitContentsForDefaultVersion(context, defaultSplitInfoFileName);
            SplitLog.i(TAG, "Cost %d mil-second to parse default split info", (System.currentTimeMillis() - currentTime));
            return details;
        } catch (Throwable e) {
            SplitLog.printErrStackTrace(TAG, e, "Failed to create default split info!");
        }
        return null;
    }

    private SplitDetails createSplitDetailsForNewVersion(File newSplitInfoFile) {
        try {
            SplitLog.i(TAG, "Updated split file path: " + newSplitInfoFile.getAbsolutePath());
            long currentTime = System.currentTimeMillis();
            SplitDetails details = parseSplitContentsForNewVersion(newSplitInfoFile);
            SplitLog.i(TAG, "Cost %d mil-second to parse updated split info", (System.currentTimeMillis() - currentTime));
            return details;
        } catch (Throwable e) {
            SplitLog.printErrStackTrace(TAG, e, "Failed to create updated split info!");
        }
        return null;
    }

    /**
     * 获取新版本的Qigsaw json文件对象，如果没有那么获取默认的asset目录下的json文件对象
     * @param context
     * @return
     */
    private synchronized SplitDetails getOrCreateSplitDetails(Context context) {
        SplitInfoVersionManager versionManager = getSplitInfoVersionManager();
        SplitDetails details = getSplitDetails();
        if (details == null) {
            String currentVersion = versionManager.getCurrentVersion();
            String defaultVersion = versionManager.getDefaultVersion();
            SplitLog.i(TAG, "currentVersion : %s defaultVersion : %s", currentVersion, defaultVersion);
            if (defaultVersion.equals(currentVersion)) {
                details = createSplitDetailsForDefaultVersion(context, defaultVersion);
            } else {
                File updatedSplitInfoFile = new File(versionManager.getRootDir(), SplitConstants.QIGSAW_PREFIX + currentVersion + SplitConstants.DOT_JSON);
                details = createSplitDetailsForNewVersion(updatedSplitInfoFile);
            }
            if (details != null) {
                if (TextUtils.isEmpty(details.getQigsawId())) {
                    return null;
                }
            }
            splitDetailsRef.compareAndSet(null, details);
        }
        return details;
    }

    /**
     * 解析出asset下默认的json文件内容
     * @param context
     * @param fileName
     * @return
     * @throws IOException
     * @throws JSONException
     */
    private static SplitDetails parseSplitContentsForDefaultVersion(Context context, String fileName)
            throws IOException, JSONException {
        String content = readInputStreamContent(createInputStreamFromAssets(context, fileName));
        return parseSplitsContent(content);
    }

    private SplitDetails parseSplitContentsForNewVersion(File newSplitInfoFile)
            throws IOException, JSONException {
        if (newSplitInfoFile != null && newSplitInfoFile.exists()) {
            return parseSplitsContent(readInputStreamContent(new FileInputStream(newSplitInfoFile)));
        }
        return null;
    }

    private static InputStream createInputStreamFromAssets(Context context, String fileName) {
        //using default
        InputStream is = null;
        Resources resources = context.getResources();
        if (resources != null) {
            try {
                is = resources.getAssets().open(fileName);
            } catch (IOException e) {
                //ignored
            }
        }
        return is;
    }

    private static String readInputStreamContent(InputStream is) throws IOException {
        if (is == null) {
            return null;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder stringBuffer = new StringBuilder();
        String str;
        while ((str = br.readLine()) != null) {
            stringBuffer.append(str);
        }
        FileUtil.closeQuietly(is);
        FileUtil.closeQuietly(br);
        return stringBuffer.toString();
    }

    /**
     * 解析 qigsaw_1.0_1.0.0.json文件
     * @param content
     * @return
     * @throws JSONException
     */
    private static SplitDetails parseSplitsContent(String content) throws JSONException {
        if (content == null) {
            return null;
        }
        LinkedHashMap<String, SplitInfo> splitInfoMap = new LinkedHashMap<>();
        JSONObject contentObj = new JSONObject(content);
        String qigsawId = contentObj.optString("qigsawId");
        String appVersionName = contentObj.optString("appVersionName");
        JSONArray updateSplitsArray = contentObj.optJSONArray("updateSplits");
        List<String> updateSplits = null;
        if (updateSplitsArray != null && updateSplitsArray.length() > 0) {
            updateSplits = new ArrayList<>(updateSplitsArray.length());
            for (int i = 0; i < updateSplitsArray.length(); i++) {
                String str = updateSplitsArray.getString(i);
                updateSplits.add(str);
            }
        }
        JSONArray splitEntryFragmentsArray = contentObj.optJSONArray("splitEntryFragments");
        List<String> splitEntryFragments = null;
        if (splitEntryFragmentsArray != null && splitEntryFragmentsArray.length() > 0) {
            splitEntryFragments = new ArrayList<>(splitEntryFragmentsArray.length());
            for (int i = 0; i < splitEntryFragmentsArray.length(); i++) {
                String str = splitEntryFragmentsArray.getString(i);
                splitEntryFragments.add(str);
            }
        }
        JSONArray array = contentObj.optJSONArray("splits");
        if (array == null) {
            throw new RuntimeException("No splits found in split-details file!");
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject itemObj = array.getJSONObject(i);
            boolean builtIn = itemObj.optBoolean("builtIn");
            String splitName = itemObj.optString("splitName");
            String version = itemObj.optString("version");
            int minSdkVersion = itemObj.optInt("minSdkVersion");
            int dexNumber = itemObj.optInt("dexNumber");
            JSONArray processes = itemObj.optJSONArray("workProcesses");
            List<String> workProcesses = null;
            if (processes != null && processes.length() > 0) {
                workProcesses = new ArrayList<>(processes.length());
                for (int k = 0; k < processes.length(); k++) {
                    workProcesses.add(processes.optString(k));
                }
            }
            JSONArray dependenciesArray = itemObj.optJSONArray("dependencies");
            List<String> dependencies = null;
            if (dependenciesArray != null && dependenciesArray.length() > 0) {
                dependencies = new ArrayList<>(dependenciesArray.length());
                for (int m = 0; m < dependenciesArray.length(); m++) {
                    dependencies.add(dependenciesArray.optString(m));
                }
            }
            JSONArray apkDataArray = itemObj.optJSONArray("apkData");
            if (apkDataArray == null || apkDataArray.length() == 0) {
                throw new RuntimeException("No apkData found in split-details file!");
            }
            List<SplitInfo.ApkData> apkDataList = new ArrayList<>(apkDataArray.length());
            for (int n = 0; n < apkDataArray.length(); n++) {
                JSONObject apkDataObj = apkDataArray.optJSONObject(n);
                String abi = apkDataObj.optString("abi");
                String url = apkDataObj.optString("url");
                String md5 = apkDataObj.optString("md5");
                long size = apkDataObj.optLong("size");
                apkDataList.add(new SplitInfo.ApkData(abi, url, md5, size));
            }
            JSONArray libDataArray = itemObj.optJSONArray("libData");
            List<SplitInfo.LibData> libDataList = null;
            if (libDataArray != null && libDataArray.length() > 0) {
                libDataList = new ArrayList<>(libDataArray.length());
                for (int j = 0; j < libDataArray.length(); j++) {
                    JSONObject libDataObj = libDataArray.optJSONObject(j);
                    String cpuAbi = libDataObj.optString("abi");
                    JSONArray jniLibsArray = libDataObj.optJSONArray("jniLibs");
                    List<SplitInfo.LibData.Lib> jniLibs = new ArrayList<>();
                    if (jniLibsArray != null && jniLibsArray.length() > 0) {
                        for (int k = 0; k < jniLibsArray.length(); k++) {
                            JSONObject libObj = jniLibsArray.optJSONObject(k);
                            String name = libObj.optString("name");
                            String soMd5 = libObj.optString("md5");
                            long soSize = libObj.optLong("size");
                            SplitInfo.LibData.Lib lib = new SplitInfo.LibData.Lib(name, soMd5, soSize);
                            jniLibs.add(lib);
                        }
                    }
                    SplitInfo.LibData libInfo = new SplitInfo.LibData(cpuAbi, jniLibs);
                    libDataList.add(libInfo);
                }
            }
            SplitInfo splitInfo = new SplitInfo(
                    splitName, appVersionName, version,
                    builtIn, minSdkVersion, dexNumber,
                    workProcesses, dependencies, apkDataList,
                    libDataList
            );
            splitInfoMap.put(splitName, splitInfo);
        }
        SplitInfoListing splitInfoListing = new SplitInfoListing(splitInfoMap);
        return new SplitDetails(qigsawId, appVersionName, updateSplits, splitEntryFragments, splitInfoListing);
    }
}
