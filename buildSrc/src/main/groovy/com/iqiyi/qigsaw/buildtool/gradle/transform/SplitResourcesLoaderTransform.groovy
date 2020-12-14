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

import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.Format
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.SecondaryFile
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.ide.common.internal.WaitableExecutor
import com.google.common.collect.ImmutableSet
import com.iqiyi.qigsaw.buildtool.gradle.extension.QigsawSplitExtensionHelper
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.AGPCompat
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.ManifestReader
import org.gradle.api.Project

import org.apache.commons.io.FileUtils
import org.gradle.api.Task
import org.gradle.api.file.FileCollection

/**
 * 向dynamic-feature构建apk的过程中
 * 向其继承自Activity,Servive,Receiver的类getResources()方法中注入一段代码
 * SplitInstallHelper.loadResources(this, super.getResources());
 *
 */
class SplitResourcesLoaderTransform extends Transform {

    final static String NAME = "splitResourcesLoader"

    Project project
    /**
     * 是否是壳工程
     */
    boolean isBaseModule

    WaitableExecutor waitableExecutor

    SplitResourcesLoaderTransform(Project project, boolean isBaseModule) {
        this.project = project
        this.waitableExecutor = WaitableExecutor.useGlobalSharedThreadPool()
        this.isBaseModule = isBaseModule
    }

    SplitResourcesLoaderTransform(Project project) {
        this(project, false)
    }

    /**
     * gradle task name
     * @return
     */
    @Override
    String getName() {
        return NAME
    }

    /**
     * 针对class
     * @return
     */
    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    /**
     * 指定范围
     * 整个工程
     * @return
     */
    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    /**
     * 方法指明是否支持增量编译
     * @return
     */
    @Override
    boolean isIncremental() {
        return false
    }
    /**
     * Returns a list of additional file(s) that this Transform needs to run
     * @return
     */
    @Override
    Collection<SecondaryFile> getSecondaryFiles() {
        if (isBaseModule) {
            return super.getSecondaryFiles()
        }
        //合并Application的manifests
        FileCollection collection = project.files('build/intermediates/merged_manifests')
        return ImmutableSet.of(SecondaryFile.nonIncremental(collection))
    }

    @Override
    Map<String, Object> getParameterInputs() {
        if (isBaseModule) {
            Map<String, Set<String>> baseContainerActivitiesMap = new HashMap<>()
            //出入插件化埋点activity
            baseContainerActivitiesMap.put("base_container_activities", QigsawSplitExtensionHelper.getBaseContainerActivities(project))
            return baseContainerActivitiesMap
        }
        return super.getParameterInputs()
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        super.transform(transformInvocation)
        long startTime = System.currentTimeMillis()
        //清空缓存目录
        transformInvocation.getOutputProvider().deleteAll()
        SplitResourcesLoaderInjector resourcesLoaderInjector = null
        if (isBaseModule) {
            Map<String, List<String>> baseContainerActivitiesMap = getParameterInputs()
            Set<String> baseContainerActivities = baseContainerActivitiesMap.get("base_container_activities")
            if (baseContainerActivities != null && !baseContainerActivities.isEmpty()) {
                resourcesLoaderInjector = new SplitResourcesLoaderInjector(waitableExecutor, baseContainerActivities)
            }
        } else {
            //读取AndroidManifest.xml 中声明的所有 activity  service revicer 读取出来
            Task processManifest = AGPCompat.getProcessManifestTask(project, transformInvocation.context.variantName.capitalize())
            File mergedManifest = AGPCompat.getMergedManifestFileCompat(processManifest)
            //println("SplitResourcesLoaderTransform:mergedManifest:$mergedManifest")
            ManifestReader manifestReader = new ManifestReader(mergedManifest)
            Set<String> activities = manifestReader.readActivityNames()
            Set<String> services = manifestReader.readServiceNames()
            Set<String> receivers = manifestReader.readReceiverNames()
            //将声明中的所有组件 转换成注入器
            resourcesLoaderInjector = new SplitResourcesLoaderInjector(waitableExecutor, activities, services, receivers)
        }
        transformInvocation.inputs.each {
            //所有的源码路径
            Collection<DirectoryInput> directoryInputs = it.directoryInputs

            if (directoryInputs != null) {
                directoryInputs.each {
                    File outputDir = transformInvocation.outputProvider.getContentLocation(it.file.absolutePath, it.contentTypes, it.scopes, Format.DIRECTORY)
                    FileUtils.copyDirectory(it.file, outputDir)
                    if (resourcesLoaderInjector != null) {
                        resourcesLoaderInjector.injectDir(outputDir)
                    }
                }
            }

            //所有的jar路径
            Collection<JarInput> jarInputs = it.jarInputs
            if (jarInputs != null) {
                jarInputs.each {
                    File outputJar = transformInvocation.outputProvider.getContentLocation(it.file.absolutePath, it.contentTypes, it.scopes, Format.JAR)
                    FileUtils.copyFile(it.file, outputJar)
                    if (resourcesLoaderInjector != null) {
                        resourcesLoaderInjector.injectJar(outputJar)
                    }
                }
            }
        }
        waitableExecutor.waitForTasksWithQuickFail(true)
        System.out.println("SplitComponentTransform cost " + (System.currentTimeMillis() - startTime) + " ms")
    }
}
