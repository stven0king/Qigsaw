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
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import org.apache.commons.io.FileUtils
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import java.security.MessageDigest

abstract class SimpleClassCreatorTransform extends Transform {

    static String getStringMD5(String str) {
        return MessageDigest.getInstance("MD5").digest(str.bytes).encodeHex().toString()
    }

    /**
     * TransformOutputProvider
     * 之Transform的输出，通过它可以获取到输出路径等信息
     *
     * @param transformInvocation
     * @return
     */
    String prepareToCreateClass(TransformInvocation transformInvocation) {
        transformInvocation.inputs.each { TransformInput input ->
            input.jarInputs.each { JarInput jarInput ->
                //jar包名称
                def jarName = jarInput.name
                //jar包的 验证摘要
                def md5 = getStringMD5(jarInput.file.getAbsolutePath())
                File dest = transformInvocation.outputProvider.getContentLocation(jarName + md5,
                        jarInput.contentTypes, jarInput.scopes, Format.JAR)
                FileUtils.copyFile(jarInput.file, dest)
            }

            input.directoryInputs.each { DirectoryInput directoryInput ->
                File dest = transformInvocation.outputProvider.getContentLocation(directoryInput.name,
                        directoryInput.contentTypes, directoryInput.scopes,
                        Format.DIRECTORY)
                //将 input 的目录复制到 output 指定目录
                FileUtils.copyDirectory(directoryInput.file, dest)
            }
        }
        return transformInvocation.outputProvider.getContentLocation("main",
                getOutputTypes(), getScopes(),
                Format.DIRECTORY)
    }

    /**
     * 创建临时类
     * @param dest 类存放目录
     * @param className 类名
     * @param superName 父类
     * @param listener 监听器
     *
     */
    static void createSimpleClass(def dest, String className, String superName, OnVisitListener listener) {
        //println("createSimpleClass:$dest:$className")
        ClassWriter cw = new ClassWriter(0)
        //根据包名转换成路径
        String folderName = className.replace(".", File.separator)
        //类文件
        File classFile = new File(dest + File.separator + folderName + ".class")
        if (!classFile.getParentFile().exists()) {
            classFile.getParentFile().mkdirs()
        }
        String superNameSpec = superName.replace(".", "/")
        cw.visit(Opcodes.V1_7,
                Opcodes.ACC_PUBLIC,
                className.replace(".", "/"), null,
                superNameSpec,
                null)
        //访问父类的 构建方法
        MethodVisitor mw = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V",
                null, null)
        mw.visitVarInsn(Opcodes.ALOAD, 0)
        mw.visitMethodInsn(Opcodes.INVOKESPECIAL, superNameSpec, "<init>",
                "()V")
        mw.visitInsn(Opcodes.RETURN)
        mw.visitMaxs(1, 1)
        mw.visitEnd()
        if (listener != null) {
            listener.onVisit(cw)
        }
        classFile
                .withOutputStream { os ->
                    os.write(cw.toByteArray())
                }
        cw.visitEnd()
    }

    interface OnVisitListener {

        void onVisit(ClassWriter cw)

    }


}
