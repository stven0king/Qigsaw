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

package com.iqiyi.qigsaw.buildtool.gradle.transform

import com.android.SdkConstants
import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.google.common.collect.ImmutableSet
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.ManifestReader
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
//com.iqiyi.android.qigsaw.core.extension.ComponentInfo
//public class ComponentInfo {
//    public static final String native_ACTIVITIES = "com.iqiyi.qigsaw.sample.ccode.NativeSampleActivity";
//    public static final String java_ACTIVITIES = "com.iqiyi.qigsaw.sample.java.JavaSampleActivity";
//    public static final String java_APPLICATION = "com.iqiyi.qigsaw.sample.java.JavaSampleApplication";
//}
//$ContentProviderName_Decorated_$featureName继承SplitContentProvider
//public class JavaContentProvider_Decorated_java extends SplitContentProvider {}
class SplitComponentTransform extends SimpleClassCreatorTransform {

    static final String NAME = "processSplitComponent"

    Project project
    /**
     * /Users/lizhiqiang/Documents/AndroidProject/demo/QigsawV2/Qigsaw/app/build/intermediates/qigsaw/split-outputs/manifests
     * 存放所有feature 工程中的  AndroidManifets.xml 中的信息 ， 便于合并资源，在主工程包中占坑
     */
    File splitManifestParentDir
    /**
     * 所有配置生效的 dynamicfeature 工程
     */
    Set<String> dynamicFeatureNames

    SplitComponentTransform(Project project) {
        this.project = project
    }

    @Override
    String getName() {
        return NAME
    }

    @Override
    Collection<SecondaryFile> getSecondaryFiles() {
        FileCollection collection = project.files(splitManifestParentDir)
        return ImmutableSet.of(SecondaryFile.nonIncremental(collection))
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }
    /**
     * 实际执行入口
     * @param transformInvocation
     * @throws TransformException
     * @throws InterruptedException
     * @throws IOException
     */
    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        super.transform(transformInvocation)
        //清空输出路径下所有内容
        transformInvocation.getOutputProvider().deleteAll()
        File splitManifestDir = new File(splitManifestParentDir, transformInvocation.context.variantName.uncapitalize())
        if (!splitManifestDir.exists()) {
            throw new GradleException("${splitManifestDir.absolutePath} is not existing!")
        }
        //所有feature工程下 声明的四大组件和application 声明的容器
        Map<String, Set> addFieldMap = new HashMap<>()
        //遍历 application 项目中 声明的所有 feature 项目
        dynamicFeatureNames.each { String name ->
            File splitManifest = new File(splitManifestDir, name + SdkConstants.DOT_XML)
            if (!splitManifest.exists()) {
                throw new GradleException("Project ${name} manifest file ${splitManifest.absolutePath} is not found!")
            }
            //遍历所有 AndroidManifest.xml 中的声明数据
            ManifestReader manifestReader = new ManifestReader(splitManifest)
            //AndroidManifest.xml 中声明的所有activity
            Set<String> activities = manifestReader.readActivityNames()
            //AndroidManifest.xml 中声明的所有service
            Set<String> services = manifestReader.readServiceNames()
            //AndroidManifest.xml 中声明的所有Receiver
            Set<String> receivers = manifestReader.readReceiverNames()
            //AndroidManifest.xml 中声明的所有Provider
            Set<String> providers = manifestReader.readProviderNames()
            //AndroidManifest.xml 中声明的所有application
            Set<String> applications = new HashSet<>()
            //feature工程中声明的application名称
            String applicationName = manifestReader.readApplicationName()
            if (applicationName != null && applicationName.length() > 0) {
                applications.add(applicationName)
            }
            //native_APPLICATION

            addFieldMap.put(name + "_APPLICATION", applications)
            addFieldMap.put(name + "_ACTIVITIES", activities)
            addFieldMap.put(name + "_SERVICES", services)
            addFieldMap.put(name + "_RECEIVERS", receivers)
            addFieldMap.put(name + "_PROVIDERS", providers)
        }

        //创建临时类生成目录
        def dest = prepareToCreateClass(transformInvocation)
        //创建类
        createSimpleClass(dest, "com.iqiyi.android.qigsaw.core.extension.ComponentInfo", "java.lang.Object", new SimpleClassCreatorTransform.OnVisitListener() {

            @Override
            void onVisit(ClassWriter cw) {
                injectCommonInfo(dest, cw, addFieldMap)
            }
        })
    }

    /**
     * 注入代码
     * @param dest
     * @param cw
     * @param addFieldMap
     */
    static void injectCommonInfo(def dest, ClassWriter cw, Map<String, Set> addFieldMap) {
        addFieldMap.each { entry ->
            Set value = entry.value
            if (value.size() > 0) {
                String name = entry.getKey()
                if (name.endsWith("APPLICATION")) {
                    //为什么value.getAt(0)).visitEnd?
                    cw.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC,
                            name, "Ljava/lang/String;", null, value.getAt(0)).visitEnd()
                } else if (name.endsWith("PROVIDERS")) {
                    //create proxy provider.
                    for (String providerName : value) {
                        String splitName = name.split("_PROVIDERS")[0]
                        String providerClassName = providerName + "_Decorated_" + splitName
                        //继承SplitContentProvider  创建每个feature的子类
                        createSimpleClass(dest, providerClassName, "com.iqiyi.android.qigsaw.core.splitload.SplitContentProvider", null)
                    }
                } else {
                    //activity逗号分割
                    cw.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC,
                            name, "Ljava/lang/String;", null,
                            (value as String[]).join(",")).visitEnd()
                }
            }
        }
    }
}
