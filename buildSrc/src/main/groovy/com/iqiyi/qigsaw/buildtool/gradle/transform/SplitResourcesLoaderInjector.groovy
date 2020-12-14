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
import com.android.ide.common.internal.WaitableExecutor
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.FileUtils
import com.iqiyi.qigsaw.buildtool.gradle.internal.tool.SplitLogger

import java.nio.file.*
import java.util.regex.Matcher

class SplitResourcesLoaderInjector {

    WaitableExecutor waitableExecutor
    /**
     * 预埋的 Activity
     */
    Set<String> activities

    Set<String> services

    Set<String> receivers

    SplitActivityWeaver activityWeaver

    SplitServiceWeaver serviceWeaver

    SplitReceiverWeaver receiverWeaver

    SplitResourcesLoaderInjector(WaitableExecutor waitableExecutor, Set<String> activities) {
        this(waitableExecutor, activities, null, null)
    }

    SplitResourcesLoaderInjector(WaitableExecutor waitableExecutor, Set<String> activities, Set<String> services, Set<String> receivers) {
        this.waitableExecutor = waitableExecutor
        this.activities = activities
        this.services = services
        this.receivers = receivers
        this.activityWeaver = new SplitActivityWeaver()
        this.serviceWeaver = new SplitServiceWeaver()
        this.receiverWeaver = new SplitReceiverWeaver()
    }
    /**
     * 注入内容
     *
     * (loadResource)
     * @param outputDir
     */
    void injectDir(File outputDir) {
        //遍历路径下文件
        Files.walk(outputDir.toPath(), Integer.MAX_VALUE).filter {
            //判断是否是一个文件
            Files.isRegularFile(it)
        }.each { Path path ->
            File file = path.toFile()
            if (file.name.endsWith(SdkConstants.DOT_JAR)) {
                //以 .jar 结尾
                injectJar(file)
            } else if (file.name.endsWith(SdkConstants.DOT_CLASS)) {
                //以 .class
                this.waitableExecutor.execute {
                    //线程池运行
                    String className = file.absolutePath.substring(outputDir.absolutePath.length() + 1, file.absolutePath.length() - SdkConstants.DOT_CLASS.length())
                            .replaceAll(Matcher.quoteReplacement(File.separator), '.')
                    byte[] bytes = injectClass(path, className)
                    if (bytes != null) {
                        //修改后回写
                        Files.write(path, bytes, StandardOpenOption.WRITE)
                    }
                }
            }
        }
    }

    /**
     * 注入jar包内容
     * @param jar
     */
    void injectJar(File jar) {
        this.waitableExecutor.execute {
            Map<String, String> zipProperties = ['create': 'false']
            //定位jar包
            URI zipDisk = URI.create("jar:${jar.toURI().toString()}")
            FileSystem zipFs = null
            try {
                zipFs = FileSystems.newFileSystem(zipDisk, zipProperties)
                Path root = zipFs.rootDirectories.iterator().next()
                //遍历当前jar包目录下所有文件 （jar下的所有内容）
                Files.walk(root, Integer.MAX_VALUE).filter {
                    Files.isRegularFile(it)
                }.each { Path path ->
                    String pathString = path.toString().substring(1).replace("\\", "/")
                    if (!pathString.endsWith(SdkConstants.DOT_CLASS)) {
                        return
                    }
                    String className = pathString.replaceAll("/", '.').replace(SdkConstants.DOT_CLASS, "")
                    //println("SplitResourcesLoaderInjector:$className")
                    byte[] bytes = injectClass(path, className)
                    if (bytes != null) {
                        Files.write(path, bytes, StandardOpenOption.WRITE)
                    }
                }
            } catch (e) {
                e.printStackTrace()
            } finally {
                FileUtils.closeQuietly(zipFs)
            }
        }
    }
    /**
     * 注入class 内容
     * 注入jar 最终也是 注入到 class中
     * @param path
     * @param className
     * @return
     */
    byte[] injectClass(Path path, String className) {
        byte[] ret = null
        if (isActivity(className)) {
            SplitLogger.w("Inject activity " + className)
            SplitLogger.w("Inject activity path " + path)
            ret = new SplitActivityWeaver().weave(path.newInputStream())
        } else if (isService(className)) {
            SplitLogger.w("Inject service " + className)
            ret = serviceWeaver.weave(path.newInputStream())
        } else if (isReceiver(className)) {
            SplitLogger.w("Inject receiver " + className)
            ret = receiverWeaver.weave(path.newInputStream())
        }
        return ret
    }

    boolean isActivity(String className) {
        boolean isActivity = false
        if (activities != null && !activities.isEmpty()) {
            return activities.contains(className)
        }
        return isActivity
    }

    boolean isService(String className) {
        boolean isService = false
        if (services != null && !services.isEmpty()) {
            return services.contains(className)
        }
        return isService
    }

    boolean isReceiver(String className) {
        boolean isReceiver = false
        if (receivers != null && !receivers.isEmpty()) {
            return receivers.contains(className)
        }
        return isReceiver
    }
}
