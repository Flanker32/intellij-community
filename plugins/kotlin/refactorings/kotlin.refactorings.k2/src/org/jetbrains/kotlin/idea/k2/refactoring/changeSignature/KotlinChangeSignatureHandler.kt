// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring.changeSignature

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.analysis.api.KtAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.lifetime.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbolOrigin
import org.jetbrains.kotlin.idea.base.psi.KotlinPsiHeuristics
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.ui.KotlinChangePropertySignatureDialog
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.ui.KotlinChangeSignatureDialog
import org.jetbrains.kotlin.idea.refactoring.changeSignature.KotlinChangeSignatureHandlerBase
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.util.OperatorNameConventions

object KotlinChangeSignatureHandler : KotlinChangeSignatureHandlerBase<KtCallableDeclaration>() {
  override fun asInvokeOperator(call: KtCallElement?): PsiElement? {
      val psiElement = call?.mainReference?.resolve() ?: return null
      if (psiElement is KtNamedFunction &&
          KotlinPsiHeuristics.isPossibleOperator(psiElement) &&
          psiElement.name == OperatorNameConventions.INVOKE.asString()
      ) {
          return psiElement
      }
      return null
  }

  override fun referencedClassOrCallable(calleeExpr: KtReferenceExpression): PsiElement? {
    return calleeExpr.mainReference.resolve()
  }

  override fun findDescriptor(element: KtElement,
                              project: Project,
                              editor: Editor?): KtCallableDeclaration? {
    return when (element) {
      is KtParameter -> if (element.hasValOrVar()) element else null
      is KtCallableDeclaration -> element
      is KtClass -> element.primaryConstructor
      else -> null
    }
  }

  override fun isVarargFunction(function: KtCallableDeclaration): Boolean {
    return function is KtNamedFunction && function.valueParameters.any { it.isVarArg }
  }

  @OptIn(KtAllowAnalysisOnEdt::class)
  override fun isSynthetic(function: KtCallableDeclaration, context: KtElement): Boolean {
    return allowAnalysisOnEdt {  analyze(context) { function.getSymbol().origin == KtSymbolOrigin.SOURCE_MEMBER_GENERATED } }
  }

  @OptIn(KtAllowAnalysisOnEdt::class)
  override fun isLibrary(function: KtCallableDeclaration, context: KtElement): Boolean {
    return allowAnalysisOnEdt { analyze(context) { function.getSymbol().origin == KtSymbolOrigin.LIBRARY } }
  }

  override fun isJavaCallable(function: KtCallableDeclaration): Boolean {
    return false
  }

  override fun isDynamic(function: KtCallableDeclaration): Boolean {
    return false //todo
  }

  override fun getDeclaration(t: KtCallableDeclaration, project: Project): PsiElement {
    return t
  }

  override fun getDeclarationName(t: KtCallableDeclaration): String {
    return (t as PsiNamedElement).name!!
  }

  override fun runChangeSignature(project: Project,
                                  editor: Editor?,
                                  callableDescriptor: KtCallableDeclaration,
                                  context: PsiElement) {
      when (callableDescriptor) {
          is KtFunction -> KotlinChangeSignatureDialog(project, editor, KotlinMethodDescriptor(callableDescriptor), context, null).show()
          is KtProperty -> KotlinChangePropertySignatureDialog(project, KotlinMethodDescriptor(callableDescriptor)).show()
      }

  }
}