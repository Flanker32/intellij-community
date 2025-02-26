// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.refactoring.changeSignature

import com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.idea.test.IDEA_TEST_DATA_DIR
import org.jetbrains.kotlin.idea.test.KotlinMultiFileTestCase
import org.jetbrains.kotlin.idea.test.extractMarkerOffset
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.types.KotlinType
import org.junit.internal.runners.JUnit38ClassRunner
import org.junit.runner.RunWith

@RunWith(JUnit38ClassRunner::class)
class KotlinMultiModuleChangeSignatureTest : KotlinMultiFileTestCase() {
    companion object {
        internal val BUILT_INS = DefaultBuiltIns.Instance
    }
    
    init {
        isMultiModule = true
    }

    override fun getTestRoot(): String = "/refactoring/changeSignatureMultiModule/"

    override fun getTestDataDirectory() = IDEA_TEST_DATA_DIR

    private fun doTest(filePath: String, configure: KotlinChangeInfo.() -> Unit) {
        doTestCommittingDocuments { rootDir, _ ->
            val psiFile = rootDir.findFileByRelativePath(filePath)!!.toPsiFile(project)!!
            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)!!
            val marker = doc.extractMarkerOffset(project)
            assert(marker != -1)
            val element = KotlinChangeSignatureHandler().findTargetMember(psiFile.findElementAt(marker)!!) as KtElement
            val handler = KotlinChangeSignatureHandler()
            val callableDescriptor = handler.findDescriptor(element, project, editor)!!
            handler.checkDescriptor(callableDescriptor, project, editor, psiFile as KtFile)
            val changeInfo = createChangeInfo(project, editor, callableDescriptor, KotlinChangeSignatureConfiguration.Empty, element)!!
            KotlinChangeSignatureProcessor(project, changeInfo.apply { configure() }, "Change signature").run()
        }
    }

    private fun KotlinChangeInfo.addParameter(name: String, type: KotlinType, defaultValue: String) {
        addParameter(
            KotlinParameterInfo(
                originalBaseFunctionDescriptor,
                -1,
                name,
                KotlinTypeInfo(false, type),
                defaultValueForCall = KtPsiFactory(project).createExpression(defaultValue)
            )
        )
    }

    fun testHeadersAndImplsByHeaderFun() = doTest("Common/src/test/test.kt") {
        newName = "baz"
    }

    fun testHeadersAndImplsByImplFun() = doTest("JS/src/test/test.kt") {
        newName = "baz"
    }

    fun testHeadersAndImplsByHeaderClassMemberFun() = doTest("Common/src/test/test.kt") {
        newName = "baz"
    }

    fun testHeadersAndImplsByImplClassMemberFun() = doTest("JS/src/test/test.kt") {
        newName = "baz"
    }

    fun testHeaderPrimaryConstructorNoParams() = doTest("Common/src/test/test.kt") {
        addParameter("n", BUILT_INS.intType, "1")
    }

    fun testHeaderPrimaryConstructor() = doTest("Common/src/test/test.kt") {
        addParameter("b", BUILT_INS.booleanType, "false")
    }

    fun testHeaderSecondaryConstructor() = doTest("Common/src/test/test.kt") {
        addParameter("b", BUILT_INS.booleanType, "false")
    }

    fun testImplPrimaryConstructorNoParams() = doTest("JVM/src/test/test.kt") {
        addParameter("n", BUILT_INS.intType, "1")
    }

    fun testImplPrimaryConstructor() = doTest("JVM/src/test/test.kt") {
        addParameter("b", BUILT_INS.booleanType, "false")
    }

    fun testImplSecondaryConstructor() = doTest("JS/src/test/test.kt") {
        addParameter("b", BUILT_INS.booleanType, "false")
    }

    fun testSuspendImpls() = doTest("Common/src/test/test.kt") {
        addParameter("n", BUILT_INS.intType, "0")
    }
}
