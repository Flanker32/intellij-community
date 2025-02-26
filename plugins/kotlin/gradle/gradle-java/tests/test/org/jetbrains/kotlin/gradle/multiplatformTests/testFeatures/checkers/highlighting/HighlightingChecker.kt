// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.gradle.multiplatformTests.testFeatures.checkers.highlighting

import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.VfsUtil
import org.jetbrains.kotlin.gradle.multiplatformTests.AbstractTestChecker
import org.jetbrains.kotlin.gradle.multiplatformTests.KotlinMppTestsContext
import org.jetbrains.kotlin.idea.codeInsight.gradle.HighlightingCheck
import java.io.File

object HighlightingChecker : AbstractTestChecker<HighlightingCheckConfiguration>() {
    override fun createDefaultConfiguration() = HighlightingCheckConfiguration()

    override fun preprocessFile(origin: File, text: String): String? {
        if (origin.extension != "kt" && origin.extension != "java") return null
        if (HIGHLIGHTING_CONFIGURATION_HEADER !in text) return null

        // Make sure that the highlighting configuration is exactly first lines and not
        // moved elsewhere by accident
        val lines = text.lines()
        require(lines.first().trim() == HIGHLIGHTING_CONFIGURATION_HEADER) {
            "Error: Highlighting configuration should be exactly first lines in file\n" +
                    "If you've edited the file, please put the source-comment with configuration\n" +
                    "on top of the file, or remove it completely and let the tests infrastructure\n" +
                    "regenerate it for you"
        }

        val textWithRemovedTestConfig = lines.drop(1) // header
            .filter { !it.startsWith(HIGHLIGHTING_CONFIGURATION_LINE_PREFIX) && it != HIGHLIGHTING_CONFIGURATION_FOOTER }
            .joinToString(separator = System.lineSeparator())
        return textWithRemovedTestConfig
    }

    override fun KotlinMppTestsContext.check() {
        val highlightingConfig = testConfiguration.getConfiguration(HighlightingChecker)
        if (highlightingConfig.skipCodeHighlighting) return
        val renderedConfig = renderConfiguration()
        val highlightingCheck = HighlightingCheck(
            project = testProject,
            projectPath = testProjectRoot.path,
            testDataDirectory = testDataDirectory,
            testLineMarkers = !highlightingConfig.hideLineMarkers,
            severityLevel = highlightingConfig.hideHighlightsBelow,
            postprocessActualTestData = { actualTestData -> renderedConfig + "\n" + actualTestData }
        )

        if (!highlightingConfig.checkLibrarySources) {
            highlightingCheck.invokeOnAllModules()
        } else {
            val librarySources = parseLibrarySourcesConfig()
            addSourcesJarToWorkspace(librarySources)
            highlightingCheck.invokeOnLibraries(librarySources)
        }

    }

    private fun KotlinMppTestsContext.renderConfiguration(): String {
        val configuration = testConfiguration.getConfiguration(this@HighlightingChecker)

        val hiddenHighlightingEntries: List<String> = buildList {
            if (configuration.skipCodeHighlighting) add("code highlighting")
            if (configuration.hideLineMarkers) add("line markers")
        }

        return if (hiddenHighlightingEntries.isNotEmpty())
            """
                $HIGHLIGHTING_CONFIGURATION_HEADER
                ${HIGHLIGHTING_CONFIGURATION_LINE_PREFIX}hidden: ${hiddenHighlightingEntries.joinToString()}
                $HIGHLIGHTING_CONFIGURATION_FOOTER
            """.trimIndent()
        else
            ""
    }

    private fun KotlinMppTestsContext.parseLibrarySourcesConfig(): Map<String, LibrarySourceRoot> {
        fun error(): Nothing = error(
            "Error while parsing library-sources.txt. Expected line format:\n\n" +
                    "<library name> <path to sources jar> <path to test data sources>"
        )

        val configFile = testDataDirectory.resolve("library-sources.txt")

        if (!configFile.exists()) error()

        return configFile.readLines()
            .filter { it.isNotBlank() }
            .map {
                val split = it.split(' ')

                LibrarySourceRoot(
                    libraryName = split.getOrNull(0) ?: error(),
                    sourcesJar = split.getOrNull(1) ?: error(),
                    testDataPath = split.getOrNull(2) ?: error(),
                )
            }.associateBy { it.libraryName }
    }

    // workaround for IDEA-227215
    private fun KotlinMppTestsContext.addSourcesJarToWorkspace(librarySources: Map<String, LibrarySourceRoot>) {
        LibraryTablesRegistrar.getInstance().getLibraryTable(testProject).libraries.forEach { library ->
            val sourceRoot = librarySources[library.name] ?: return@forEach

            runWriteActionAndWait {
                library.modifiableModel.apply {
                    addRoot(VfsUtil.getUrlForLibraryRoot(testProjectRoot.resolve(sourceRoot.sourcesJar)), OrderRootType.SOURCES)
                    commit()
                }
            }
        }
    }

    private const val HIGHLIGHTING_CONFIGURATION_HEADER = "//region Test configuration"
    private const val HIGHLIGHTING_CONFIGURATION_LINE_PREFIX = "// - "
    private const val HIGHLIGHTING_CONFIGURATION_FOOTER = "//endregion"
}

class LibrarySourceRoot(val libraryName: String, val sourcesJar: String, val testDataPath: String)
