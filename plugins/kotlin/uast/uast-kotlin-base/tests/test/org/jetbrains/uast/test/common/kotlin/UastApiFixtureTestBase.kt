// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.uast.test.common.kotlin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import junit.framework.TestCase
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.uast.*
import com.intellij.platform.uast.testFramework.env.findElementByTextFromPsi
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiTypes
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.uast.kotlin.BaseKotlinUastResolveProviderService
import org.jetbrains.uast.util.isConstructorCall
import org.jetbrains.uast.visitor.AbstractUastVisitor

interface UastApiFixtureTestBase : UastPluginSelection {
    fun checkAssigningArrayElementType(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "MyClass.kt", """ 
            fun foo() {
                val arr = arrayOfNulls<List<*>>(10)
                arr[0] = emptyList<Any>()
                
                val lst = mutableListOf<List<*>>()
                lst[0] = emptyList<Any>()
            }
        """
        )

        val uFile = myFixture.file.toUElement()!!

        TestCase.assertEquals(
            "java.util.List<?>",
            uFile.findElementByTextFromPsi<UExpression>("arr[0]").getExpressionType()?.canonicalText
        )
        TestCase.assertEquals(
            "java.util.List<?>",
            uFile.findElementByTextFromPsi<UExpression>("lst[0]").getExpressionType()?.canonicalText
        )
    }

    fun checkArgumentForParameter_smartcast(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                open class A
                class B : A()

                private fun processB(b: B): Int = 2

                fun test(a: A) {
                    if (a is B) {
                        process<caret>B(a)
                    }
                }
            """.trimIndent()
        )

        val uCallExpression = myFixture.file.findElementAt(myFixture.caretOffset).toUElement().getUCallExpression()
            .orFail("cant convert to UCallExpression")
        val arg = uCallExpression.getArgumentForParameter(0)
        TestCase.assertNotNull(arg)
        TestCase.assertTrue(arg is USimpleNameReferenceExpression)
        TestCase.assertEquals("a", (arg as? USimpleNameReferenceExpression)?.resolvedName)
    }

    fun checkDivByZero(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "MyClass.kt", """
            val p = 1 / 0
        """
        )

        val uFile = myFixture.file.toUElement()!!
        val p = uFile.findElementByTextFromPsi<UVariable>("p", strict = false)
        TestCase.assertNotNull("can't convert property p", p)
        TestCase.assertNotNull("can't find property initializer", p.uastInitializer)
        TestCase.assertNull("Should not see ArithmeticException", p.uastInitializer?.evaluate())
    }

    fun checkDetailsOfDeprecatedHidden(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            // Example from KTIJ-18039
            "MyClass.kt", """
            @Deprecated(level = DeprecationLevel.WARNING, message="subject to change")
            fun test1() { }
            @Deprecated(level = DeprecationLevel.HIDDEN, message="no longer supported")
            fun test2() = Test(22)
            
            class Test(private val parameter: Int)  {
                @Deprecated(message = "Binary compatibility", level = DeprecationLevel.HIDDEN)
                constructor() : this(42)
            }
        """
        )

        val uFile = myFixture.file.toUElement()!!

        val test1 = uFile.findElementByTextFromPsi<UMethod>("test1", strict = false)
        TestCase.assertNotNull("can't convert function test1", test1)
        // KTIJ-18716
        TestCase.assertTrue("Warning level, hasAnnotation", test1.javaPsi.hasAnnotation("kotlin.Deprecated"))
        // KTIJ-18039
        TestCase.assertTrue("Warning level, isDeprecated", test1.javaPsi.isDeprecated)
        // KTIJ-18720
        TestCase.assertTrue("Warning level, public", test1.javaPsi.hasModifierProperty(PsiModifier.PUBLIC))
        // KTIJ-23807
        TestCase.assertTrue("Warning level, nullability", test1.javaPsi.annotations.none { it.isNullnessAnnotation })

        val test2 = uFile.findElementByTextFromPsi<UMethod>("test2", strict = false)
        TestCase.assertNotNull("can't convert function test2", test2)
        // KTIJ-18716
        TestCase.assertTrue("Hidden level, hasAnnotation", test2.javaPsi.hasAnnotation("kotlin.Deprecated"))
        // KTIJ-18039
        TestCase.assertTrue("Hidden level, isDeprecated", test2.javaPsi.isDeprecated)
        // KTIJ-18720
        TestCase.assertTrue("Hidden level, public", test2.javaPsi.hasModifierProperty(PsiModifier.PUBLIC))
        // KTIJ-23807
        TestCase.assertNotNull("Hidden level, nullability", test2.javaPsi.annotations.singleOrNull { it.isNullnessAnnotation })

        val testClass = uFile.findElementByTextFromPsi<UClass>("Test", strict = false)
        TestCase.assertNotNull("can't convert class Test", testClass)
        testClass.methods.forEach { mtd ->
            if (mtd.sourcePsi is KtConstructor<*>) {
                // KTIJ-20200
                TestCase.assertTrue("$mtd should be marked as a constructor", mtd.isConstructor)
            }
        }
    }

    fun checkTypesOfDeprecatedHidden(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                interface State<out T> {
                    val value: T
                }

                @Deprecated(level = DeprecationLevel.HIDDEN, message="no longer supported")
                fun before(
                    i : Int?,
                    s : String?,
                    vararg vs : Any,
                ): State<String> {
                    return object : State<String> {
                        override val value: String = i?.toString() ?: s ?: "42"
                    }
                }
                
                fun after(
                    i : Int?,
                    s : String?,
                    vararg vs : Any,
                ): State<String> {
                    return object : State<String> {
                        override val value: String = i?.toString() ?: s ?: "42"
                    }
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!

        val before = uFile.findElementByTextFromPsi<UMethod>("before", strict = false)
            .orFail("cant convert to UMethod: before")
        val after = uFile.findElementByTextFromPsi<UMethod>("after", strict = false)
            .orFail("cant convert to UMethod: after")

        compareDeprecatedHidden(before, after, Nullable::class.java.name)
    }

    fun checkTypesOfDeprecatedHiddenSuspend(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                interface MyInterface

                interface GattClientScope {
                    @Deprecated(level = DeprecationLevel.HIDDEN, message="no longer supported")
                    suspend fun awaitBefore(block: () -> Unit)
                    suspend fun awaitAfter(block: () -> Unit)

                    @Deprecated(level = DeprecationLevel.HIDDEN, message="no longer supported")
                    suspend fun readCharacteristicBefore(p: MyInterface): Result<ByteArray>
                    suspend fun readCharacteristicAfter(p: MyInterface): Result<ByteArray>
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!

        val awaitBefore = uFile.findElementByTextFromPsi<UMethod>("awaitBefore", strict = false)
            .orFail("cant convert to UMethod: awaitBefore")
        val awaitAfter = uFile.findElementByTextFromPsi<UMethod>("awaitAfter", strict = false)
            .orFail("cant convert to UMethod: awaitAfter")

        compareDeprecatedHidden(awaitBefore, awaitAfter, NotNull::class.java.name)

        val readBefore = uFile.findElementByTextFromPsi<UMethod>("readCharacteristicBefore", strict = false)
            .orFail("cant convert to UMethod: readCharacteristicBefore")
        val readAfter = uFile.findElementByTextFromPsi<UMethod>("readCharacteristicAfter", strict = false)
            .orFail("cant convert to UMethod: readCharacteristicAfter")

        compareDeprecatedHidden(readBefore, readAfter, NotNull::class.java.name)
    }

    fun checkTypesOfDeprecatedHiddenProperty_noAccessor(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                class Test {
                    @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                    var pOld_noAccessor: String = "42"
                    var pNew_noAccessor: String = "42"
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        val test = uFile.findElementByTextFromPsi<UClass>("Test", strict = false)
        compareDeprecatedHiddenProperty(test, NotNull::class.java.name)
    }

    fun checkTypesOfDeprecatedHiddenProperty_getter(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                class Test {
                    @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                    var pOld_getter: String? = null
                        get() = field ?: "null?"
                    var pNew_getter: String? = null
                        get() = field ?: "null?"
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        val test = uFile.findElementByTextFromPsi<UClass>("Test", strict = false)
        compareDeprecatedHiddenProperty(test, Nullable::class.java.name)
    }

    fun checkTypesOfDeprecatedHiddenProperty_setter(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                class Test {
                    @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                    var pOld_setter: String? = null
                        set(value) {
                            if (field == null) {
                                field = value
                            }
                        }
                    var pNew_setter: String? = null
                        set(value) {
                            if (field == null) {
                                field = value
                            }
                        }
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        val test = uFile.findElementByTextFromPsi<UClass>("Test", strict = false)
        compareDeprecatedHiddenProperty(test, Nullable::class.java.name)
    }

    fun checkTypesOfDeprecatedHiddenProperty_accessors(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                class Test {
                    @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                    var pOld_accessors: String? = null
                        get() = field ?: "null?"
                        set(value) {
                            if (field == null) {
                                field = value
                            }
                        }
                    var pNew_accessors: String? = null
                        get() = field ?: "null?"
                        set(value) {
                            if (field == null) {
                                field = value
                            }
                        }
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        val test = uFile.findElementByTextFromPsi<UClass>("Test", strict = false)
        compareDeprecatedHiddenProperty(test, Nullable::class.java.name)
    }

    private fun compareDeprecatedHiddenProperty(test: UClass, nullness: String) {
        val old_getter = test.methods.find { it.name.startsWith("getPOld") }
            .orFail("cant find old getter")
        val old_setter = test.methods.find { it.name.startsWith("setPOld") }
            .orFail("cant find old setter")

        val new_getter = test.methods.find { it.name.startsWith("getPNew") }
            .orFail("cant find new getter")
        val new_setter = test.methods.find { it.name.startsWith("setPNew") }
            .orFail("cant find new setter")

        compareDeprecatedHidden(old_getter, new_getter, nullness)
        compareDeprecatedHidden(old_setter, new_setter, nullness)
    }

    private fun compareDeprecatedHidden(before: UMethod, after: UMethod, nullness: String) {
        TestCase.assertEquals("return type", after.returnType, before.returnType)

        TestCase.assertEquals("param size", after.uastParameters.size, before.uastParameters.size)
        after.uastParameters.zip(before.uastParameters).forEach { (afterParam, beforeParam) ->
            val paramName = afterParam.name
            TestCase.assertEquals(paramName, beforeParam.name)
            TestCase.assertEquals(paramName, afterParam.isVarArgs, beforeParam.isVarArgs)
            TestCase.assertEquals(paramName, afterParam.type, beforeParam.type)
            TestCase.assertEquals(
                paramName,
                (afterParam.javaPsi as PsiModifierListOwner).hasAnnotation(nullness),
                (beforeParam.javaPsi as PsiModifierListOwner).hasAnnotation(nullness)
            )
        }
    }

    fun checkReifiedTypeNullability(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                import kotlin.reflect.KClass

                interface NavArgs
                class Fragment
                class Bundle
                class NavArgsLazy<Args : NavArgs>(
                    private val navArgsClass: KClass<Args>,
                    private val argumentProducer: () -> Bundle
                )
                
                inline fun <reified Args : NavArgs> Fragment.navArgs() = NavArgsLazy(Args::class) {
                    throw IllegalStateException("Fragment $this has null arguments")
                }
                
                inline fun <reified Args : NavArgs> Fragment.navArgsNullable(flag: Boolean) =
                    if (flag)
                        NavArgsLazy(Args::class) {
                            throw IllegalStateException("Fragment $this has null arguments")
                        }
                    else
                        null
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!

        uFile.accept(object : AbstractUastVisitor() {
            override fun visitMethod(node: UMethod): Boolean {
                if (!node.name.startsWith("navArgs")) return super.visitMethod(node)

                TestCase.assertEquals("NavArgsLazy<Args>", node.javaPsi.returnType?.canonicalText)

                val annotations = node.javaPsi.annotations
                TestCase.assertEquals(1, annotations.size)
                val annotation = annotations.single()
                if (node.name.endsWith("Nullable")) {
                    TestCase.assertTrue(annotation.isNullable)
                } else {
                    TestCase.assertTrue(annotation.isNotNull)
                }

                return super.visitMethod(node)
            }
        })
    }

    fun checkInheritedGenericTypeNullability(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                class NonNullUpperBound<T : Any>(ctorParam: T) {
                    fun inheritedNullability(i: T): T = i
                    fun explicitNullable(e: T?): T? = e
                }

                class NullableUpperBound<T : Any?>(ctorParam: T) {
                    fun inheritedNullability(i: T): T = i
                    fun explicitNullable(e: T?): T? = e
                }

                class UnspecifiedUpperBound<T>(ctorParam: T) {
                    fun inheritedNullability(i: T): T = i
                    fun explicitNullable(e: T?): T? = e
                }

                fun <T : Any> topLevelNonNullUpperBoundInherited(t: T) = t
                fun <T : Any> T.extensionNonNullUpperBoundInherited(t: T) { }
                fun <T : Any> topLevelNonNullUpperBoundExplicitNullable(t: T?) = t

                fun <T : Any?> topLevelNullableUpperBoundInherited(t: T) = t
                fun <T : Any?> T.extensionNullableUpperBoundInherited(t: T) { }
                fun <T : Any?> topLevelNullableUpperBoundExplicitNullable(t: T?) = t

                fun <T> topLevelUnspecifiedUpperBoundInherited(t: T) = t
                fun <T> T.extensionUnspecifiedUpperBoundInherited(t: T) { }
                fun <T> topLevelUnspecifiedUpperBoundExplicitNullable(t: T?) = t
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        val service = ApplicationManager.getApplication().getService(BaseKotlinUastResolveProviderService::class.java)

        uFile.accept(object : AbstractUastVisitor() {
            var currentMethod: UMethod? = null

            override fun visitMethod(node: UMethod): Boolean {
                if (node.isConstructor) {
                    return super.visitMethod(node)
                }
                currentMethod = node
                return super.visitMethod(node)
            }

            override fun afterVisitMethod(node: UMethod) {
                currentMethod = null
            }

            override fun visitParameter(node: UParameter): Boolean {
                if (currentMethod == null) {
                    return super.visitParameter(node)
                }

                val name = currentMethod!!.name
                val annotations = node.uAnnotations
                if (name.endsWith("Nullable")) {
                    // explicitNullable or ...ExplicitNullable
                    checkNullableAnnotation(annotations)
                } else if (name == "inheritedNullability") {
                    val className = (currentMethod!!.uastParent as UClass).name!!
                    if (className.startsWith("NonNull")) {
                        // non-null upper bound (T: Any)
                        checkNonNullAnnotation(annotations)
                    } else {
                        TestCase.assertTrue(annotations.isEmpty())
                        TestCase.assertTrue(service.hasInheritedGenericType(node.sourcePsi!!))
                    }
                } else {
                    // ...Inherited
                    if (name.contains("NonNull")) {
                        // non-null upper bound (T: Any)
                        checkNonNullAnnotation(annotations)
                    } else {
                        TestCase.assertTrue(annotations.isEmpty())
                        TestCase.assertTrue(service.hasInheritedGenericType(node.sourcePsi!!))
                    }
                }

                return super.visitParameter(node)
            }

            private fun checkNonNullAnnotation(annotations: List<UAnnotation>) {
                TestCase.assertEquals(1, annotations.size)
                val annotation = annotations.single()
                TestCase.assertTrue(annotation.isNotNull)
            }

            private fun checkNullableAnnotation(annotations: List<UAnnotation>) {
                TestCase.assertEquals(1, annotations.size)
                val annotation = annotations.single()
                TestCase.assertTrue(annotation.isNullable)
            }
        })
    }

    fun checkInheritedGenericTypeNullability_propertyAndAccessor(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                class CircularArray<E> {
                    val first: E
                        get() = TODO()

                    var last: E
                        get() = TODO()
                        set(value) = TODO()
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        val service = ApplicationManager.getApplication().getService(BaseKotlinUastResolveProviderService::class.java)

        uFile.accept(object : AbstractUastVisitor() {
            override fun visitField(node: UField): Boolean {
                TestCase.assertTrue(node.uAnnotations.isEmpty())
                TestCase.assertTrue(service.hasInheritedGenericType(node.sourcePsi!!))
                return super.visitField(node)
            }

            override fun visitMethod(node: UMethod): Boolean {
                if (node.isConstructor) {
                    return super.visitMethod(node)
                }
                TestCase.assertTrue(node.uAnnotations.isEmpty())
                TestCase.assertTrue(
                    node.returnType == PsiTypes.voidType() || service.hasInheritedGenericType(node.sourcePsi!!)
                )
                return super.visitMethod(node)
            }

            override fun visitParameter(node: UParameter): Boolean {
                TestCase.assertTrue(node.uAnnotations.isEmpty())
                TestCase.assertTrue(service.hasInheritedGenericType(node.sourcePsi!!))
                return super.visitParameter(node)
            }
        })
    }

    fun checkImplicitReceiverType(myFixture: JavaCodeInsightTestFixture) {
        myFixture.addClass(
            """
            public class MyBundle {
              public void putString(String key, String value) { }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.kt", """
                fun foo() {
                  MyBundle().apply {
                    <caret>putString("k", "v")
                  }
                }
            """.trimIndent()
        )

        val uCallExpression = myFixture.file.findElementAt(myFixture.caretOffset).toUElement().getUCallExpression()
            .orFail("cant convert to UCallExpression")
        TestCase.assertEquals("putString", uCallExpression.methodName)
        TestCase.assertEquals("MyBundle", uCallExpression.receiverType?.canonicalText)
    }

    fun checkSubstitutedReceiverType(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                inline fun <T, R> T.use(block: (T) -> R): R {
                  return block(this)
                }
                
                fun foo() {
                  // T: String, R: Int
                  val len = "42".u<caret>se { it.length }
                }
            """.trimIndent()
        )

        val uCallExpression = myFixture.file.findElementAt(myFixture.caretOffset).toUElement().getUCallExpression()
            .orFail("cant convert to UCallExpression")
        TestCase.assertEquals("use", uCallExpression.methodName)
        TestCase.assertEquals("java.lang.String", uCallExpression.receiverType?.canonicalText)
    }

    fun checkUnderscoreOperatorForTypeArguments(myFixture: JavaCodeInsightTestFixture) {
        // example from https://kotlinlang.org/docs/generics.html#underscore-operator-for-type-arguments
        // modified to avoid using reflection (::class.java)
        myFixture.configureByText(
            "main.kt", """
                abstract class SomeClass<T> {
                    abstract fun execute() : T
                }

                class SomeImplementation : SomeClass<String>() {
                    override fun execute(): String = "Test"
                }

                class OtherImplementation : SomeClass<Int>() {
                    override fun execute(): Int = 42
                }

                object Runner {
                    inline fun <reified S: SomeClass<T>, T> run(instance: S) : T {
                        return instance.execute()
                    }
                }

                fun test() {
                    val i = SomeImplementation()
                    // T is inferred as String because SomeImplementation derives from SomeClass<String>
                    val s = Runner.run<_, _>(i)
                    assert(s == "Test")

                    val j = OtherImplementation()
                    // T is inferred as Int because OtherImplementation derives from SomeClass<Int>
                    val n = Runner.run<_, _>(j)
                    assert(n == 42)
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        uFile.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                if (node.methodName != "run") return super.visitCallExpression(node)

                TestCase.assertEquals(2, node.typeArgumentCount)
                val firstTypeArg = node.typeArguments[0]
                val secondTypeArg = node.typeArguments[1]

                when (firstTypeArg.canonicalText) {
                    "SomeImplementation" -> {
                        TestCase.assertEquals("java.lang.String", secondTypeArg.canonicalText)
                    }
                    "OtherImplementation" -> {
                        TestCase.assertEquals("java.lang.Integer", secondTypeArg.canonicalText)
                    }
                    else -> TestCase.assertFalse("Unexpected $firstTypeArg", true)
                }

                return super.visitCallExpression(node)
            }
        })
    }

    fun checkCallKindOfSamConstructor(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                val r = java.lang.Runnable { }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        val uCallExpression = uFile.findElementByTextFromPsi<UCallExpression>("Runnable", strict = false)
            .orFail("cant convert to UCallExpression")
        TestCase.assertEquals("Runnable", uCallExpression.methodName)
        TestCase.assertEquals(UastCallKind.CONSTRUCTOR_CALL, uCallExpression.kind)
    }

    // Regression test from KT-59564
    fun checkExpressionTypeOfForEach(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                // !LANGUAGE: +RangeUntilOperator
                @file:OptIn(ExperimentalStdlibApi::class)
                fun test(a: Int, b: Int) {
                  for (i in a..<b step 1) {
                       println(i)
                  }
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        uFile.accept(object : AbstractUastVisitor() {
            override fun visitForEachExpression(node: UForEachExpression): Boolean {
                when (val exp = node.iteratedValue.skipParenthesizedExprDown()) {
                    is UBinaryExpression -> {
                        TestCase.assertEquals("kotlin.ranges.IntProgression", exp.getExpressionType()?.canonicalText)
                        TestCase.assertEquals("kotlin.ranges.IntRange", exp.leftOperand.getExpressionType()?.canonicalText)
                    }
                }

                return super.visitForEachExpression(node)
            }
        })
    }

    // Regression test from KTIJ-23503
    fun checkExpressionTypeFromIncorrectObject(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                class Outer() {
                    object { // <no name provided>
                        class Inner() {}

                        fun getInner() = Inner()
                    }
                }

                fun main(args: Array<String>) {
                    val inner = Outer.getInner()
                }
            """.trimIndent()
        )

        val errorType = "<ErrorType>"
        val expectedPsiTypes = setOf("Outer.Inner", errorType)
        myFixture.file.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                // Mimic what [IconLineMarkerProvider#collectSlowLineMarkers] does.
                element.toUElementOfType<UCallExpression>()?.let {
                    val expressionType = it.getExpressionType()?.canonicalText ?: errorType
                    TestCase.assertTrue(
                        expressionType,
                        expressionType in expectedPsiTypes ||
                                // FE1.0 outputs Outer.no_name_in_PSI_hashcode.Inner
                                (expressionType.startsWith("Outer.") && expressionType.endsWith(".Inner"))
                    )
                }

                super.visitElement(element)
            }
        })
    }

    fun checkExpressionTypeForCallToInternalOperator(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                object Dependency {
                    internal operator fun unaryPlus() = Any()
                    operator fun unaryMinus() = Any()
                    operator fun not() = Any()
                }
                
                class OtherDependency {
                    internal operator fun inc() = this
                    operator fun dec() = this
                }
                
                fun test {
                    +Dependency
                    Dependency.unaryPlus()
                    -Dependency
                    Dependency.unaryMinus()
                    !Dependency
                    Dependency.not()
                    
                    var x = OtherDependency()
                    x++
                    x.inc()
                    x--
                    x.dec()
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!

        val binaryOperators = setOf("inc", "dec")

        uFile.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                if (node.isConstructorCall()) return super.visitCallExpression(node)

                if (node.methodName in binaryOperators) {
                    TestCase.assertEquals("OtherDependency", node.getExpressionType()?.canonicalText)
                } else {
                    TestCase.assertEquals("java.lang.Object", node.getExpressionType()?.canonicalText)
                }

                return super.visitCallExpression(node)
            }

            override fun visitPrefixExpression(node: UPrefixExpression): Boolean {
                TestCase.assertEquals("java.lang.Object", node.getExpressionType()?.canonicalText)

                return super.visitPrefixExpression(node)
            }

            override fun visitPostfixExpression(node: UPostfixExpression): Boolean {
                TestCase.assertEquals("OtherDependency", node.getExpressionType()?.canonicalText)

                return super.visitPostfixExpression(node)
            }
        })
    }

    fun checkFlexibleFunctionalInterfaceType(myFixture: JavaCodeInsightTestFixture) {
        myFixture.addClass(
            """
                package test.pkg;
                public interface ThrowingRunnable {
                    void run() throws Throwable;
                }
            """.trimIndent()
        )
        myFixture.addClass(
            """
                package test.pkg;
                public class Assert {
                    public static <T extends Throwable> T assertThrows(Class<T> expectedThrowable, ThrowingRunnable runnable) {}
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "main.kt", """
                import test.pkg.Assert

                fun dummy() = Any()
                
                fun test() {
                    Assert.assertThrows(Throwable::class.java) { dummy() }
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        val uLambdaExpression = uFile.findElementByTextFromPsi<ULambdaExpression>("{ dummy() }")
            .orFail("cant convert to ULambdaExpression")
        TestCase.assertEquals("test.pkg.ThrowingRunnable", uLambdaExpression.functionalInterfaceType?.canonicalText)
    }

    fun checkInvokedLambdaBody(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                class Lambda {
                  fun unusedLambda(s: String, o: Any) {
                    {
                      s === o
                      o.toString()
                      s.length
                    }
                  }

                  fun invokedLambda(s: String, o: Any) {
                    {
                      s === o
                      o.toString()
                      s.length
                    }()
                  }
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        var unusedLambda: ULambdaExpression? = null
        var invokedLambda: ULambdaExpression? = null
        uFile.accept(object : AbstractUastVisitor() {
            private var containingUMethod: UMethod? = null

            override fun visitMethod(node: UMethod): Boolean {
                containingUMethod = node
                return super.visitMethod(node)
            }

            override fun afterVisitMethod(node: UMethod) {
                containingUMethod = null
                super.afterVisitMethod(node)
            }

            override fun visitLambdaExpression(node: ULambdaExpression): Boolean {
                if (containingUMethod?.name == "unusedLambda") {
                    unusedLambda = node
                }

                return super.visitLambdaExpression(node)
            }

            override fun visitCallExpression(node: UCallExpression): Boolean {
                if (node.methodName != OperatorNameConventions.INVOKE.identifier) return super.visitCallExpression(node)

                val receiver = node.receiver
                TestCase.assertNotNull(receiver)
                TestCase.assertTrue(receiver is ULambdaExpression)
                invokedLambda = receiver as ULambdaExpression

                return super.visitCallExpression(node)
            }
        })
        TestCase.assertNotNull(unusedLambda)
        TestCase.assertNotNull(invokedLambda)
        TestCase.assertEquals(unusedLambda!!.asRecursiveLogString(), invokedLambda!!.asRecursiveLogString())
    }

    fun checkLambdaImplicitParameters(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                inline fun <T> fun0Consumer(f: () -> T): T {
                  return f()
                }
                
                inline fun <P, R> fun1Consumer(arg: P, f: (P) -> R): R {
                    return f(arg)
                }
                
                inline fun <P, R> fun1ExtConsumer(arg: P, f: P.() -> R): R {
                    return arg.f()
                }
                
                inline fun <P1, P2, R> fun2Consumer(arg1: P1, arg2: P2, f: (P1, P2) -> R): R {
                    return f(arg1, arg2)
                }
                
                inline fun <P1, P2, R> fun2ExtConsumer(arg1: P1, arg2: P2, f: P1.(P2) -> R): R {
                    return arg1.f(arg2)
                }
                
                fun test() {
                    fun0Consumer {
                        println("Function0")
                    }
                    fun1Consumer(42) {
                        println(it)
                    }
                    fun1ExtConsumer(42) {
                        println(this)
                    }
                    fun2Consumer(42, "42") { p1, p2 ->
                        println(p1.toString() == p2)
                    }
                    fun2ExtConsumer(42, "42") { 
                        println(this.toString() == it)
                    }
                }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        uFile.accept(object : AbstractUastVisitor() {
            override fun visitLambdaExpression(node: ULambdaExpression): Boolean {
                val parameters = node.parameters
                val methodName = (node.uastParent as? UCallExpression)?.methodName
                TestCase.assertNotNull(methodName)

                when (methodName!!) {
                    "fun0Consumer" -> {
                        TestCase.assertTrue(parameters.isEmpty())
                    }
                    "fun1Consumer" -> {
                        TestCase.assertEquals(1, parameters.size)
                        val it = parameters.single()
                        TestCase.assertEquals("it", it.name)
                        TestCase.assertEquals("int", it.type.canonicalText)
                    }
                    "fun1ExtConsumer" -> {
                        TestCase.assertEquals(1, parameters.size)
                        val it = parameters.single()
                        TestCase.assertEquals("<this>", it.name)
                        TestCase.assertEquals("int", it.type.canonicalText)
                    }
                    "fun2Consumer" -> {
                        TestCase.assertEquals(2, parameters.size)
                        val p1 = parameters[0]
                        TestCase.assertEquals("p1", p1.name)
                        TestCase.assertEquals("int", p1.type.canonicalText)
                        val p2 = parameters[1]
                        TestCase.assertEquals("p2", p2.name)
                        TestCase.assertEquals("java.lang.String", p2.type.canonicalText)
                    }
                    "fun2ExtConsumer" -> {
                        TestCase.assertEquals(2, parameters.size)
                        val p1 = parameters[0]
                        TestCase.assertEquals("<this>", p1.name)
                        TestCase.assertEquals("int", p1.type.canonicalText)
                        val p2 = parameters[1]
                        TestCase.assertEquals("it", p2.name)
                        TestCase.assertEquals("java.lang.String", p2.type.canonicalText)
                    }
                    else -> TestCase.assertFalse("Unexpected $methodName", true)
                }

                return super.visitLambdaExpression(node)
            }
        })
    }

    fun checkLambdaBodyAsParentOfDestructuringDeclaration(myFixture: JavaCodeInsightTestFixture) {
        // KTIJ-24108
        myFixture.configureByText(
            "main.kt", """
                fun fi(data: List<String>) =
                    data.filter {
                        va<caret>l (a, b)
                    }
            """.trimIndent()
        )

        val destructuringDeclaration =
            myFixture.file.findElementAt(myFixture.caretOffset)
                ?.getParentOfType<KtDestructuringDeclaration>(strict = true)
                .orFail("Cannot find KtDestructuringDeclaration")

        val uDestructuringDeclaration =
            destructuringDeclaration.toUElement().orFail("Cannot convert to KotlinUDestructuringDeclarationExpression")

        TestCase.assertNotNull(uDestructuringDeclaration.uastParent)
    }

    fun checkIdentifierOfNullableExtensionReceiver(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                enum class SortOrder {
                  ASCENDING, DESCENDING, UNSORTED
                }

                fun <C> Comparator<C>?.withOrder(sortOrder: SortOrder): Comparator<C>? =
                  this?.let {
                    when (sortOrder) {
                      SortOrder.ASCENDING -> it
                      SortOrder.DESCENDING -> it.reversed()
                      SortOrder.UNSORTED -> null
                    }
                  }
            """.trimIndent()
        )

        val uFile = myFixture.file.toUElement()!!
        val withOrder = uFile.findElementByTextFromPsi<UMethod>("withOrder", strict = false)
            .orFail("can't convert extension function: withOrder")
        val extensionReceiver = withOrder.uastParameters.first()
        val identifier = extensionReceiver.uastAnchor as? UIdentifier
        TestCase.assertNotNull(identifier)
    }

    fun checkReceiverTypeOfExtensionFunction(myFixture: JavaCodeInsightTestFixture) {
        myFixture.configureByText(
            "main.kt", """
                class Foo
                class Bar {
                  fun Foo.ext() {}
                  
                  fun test(f: Foo) {
                    f.ex<caret>t()
                  }
                }
            """.trimIndent()
        )
        val uCallExpression = myFixture.file.findElementAt(myFixture.caretOffset).toUElement().getUCallExpression()
            .orFail("cant convert to UCallExpression")
        TestCase.assertEquals("Foo", uCallExpression.receiverType?.canonicalText)
    }
}