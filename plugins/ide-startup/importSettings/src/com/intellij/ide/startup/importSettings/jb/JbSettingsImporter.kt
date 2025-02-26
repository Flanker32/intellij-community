// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.startup.importSettings.jb

import com.intellij.configurationStore.*
import com.intellij.configurationStore.schemeManager.SchemeManagerFactoryBase
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ConfigImportHelper
import com.intellij.openapi.application.ConfigImportSettings
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.SchemeManagerFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.codeStyle.CodeStyleSchemes
import com.intellij.serviceContainer.ComponentManagerImpl
import com.intellij.util.io.systemIndependentPath
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.*

class JbSettingsImporter(private val configDirPath: Path, private val pluginsPath: Path) {
  private val componentStore = ApplicationManager.getApplication().stateStore as ComponentStoreImpl

  fun importOptions(categories: Set<SettingsCategory>) {
    val allFiles = mutableSetOf<String>()
    val optionsPath = configDirPath / PathManager.OPTIONS_DIRECTORY
    optionsPath.listDirectoryEntries("*.xml").map { it.name }.forEach { allFiles.add(it) }
    for (optionsEntry in optionsPath.listDirectoryEntries()) {
      if (optionsEntry.name.lowercase().endsWith(".xml") && optionsEntry.name.lowercase() != StoragePathMacros.NON_ROAMABLE_FILE) {
        allFiles.add(optionsEntry.name)
      }
      else if (optionsEntry.isDirectory() && optionsEntry.name.lowercase() == getPerOsSettingsStorageFolderName()) {
        allFiles.addAll(filesFromFolder(optionsEntry, ""))
      }
    }
    // ensure CodeStyleSchemes manager is created
    // TODO check other scheme managers that needs to be created
    CodeStyleSchemes.getInstance()

    val schemeManagerFactory = SchemeManagerFactory.getInstance() as SchemeManagerFactoryBase
    schemeManagerFactory.process {
      val dirPath = configDirPath / it.fileSpec
      if (dirPath.isDirectory()) {
        allFiles.addAll(filesFromFolder(dirPath))
      }
    }
    LOG.info("Detected ${allFiles.size} files to import: ${allFiles.joinToString()}")
    val files2process = filterFiles(allFiles, categories)

    LOG.info("After filtering we have ${files2process.size} files to import: ${files2process.joinToString()}")
    val storageManager = componentStore.storageManager as StateStorageManagerImpl
    val provider = ImportStreamProvider(configDirPath)
    storageManager.addStreamProvider(provider)
    componentStore.reloadComponents(files2process, emptyList())
    storageManager.removeStreamProvider(provider::class.java)
  }

  private fun filesFromFolder(dir: Path, prefix: String = dir.name): Collection<String> {
    val retval = ArrayList<String>()
    for (entry in dir.listDirectoryEntries()) {
      if (entry.isRegularFile()) {
        retval.add("$prefix/${entry.name}")
      }
    }
    return retval
  }


  private fun filterFiles(allFiles: Set<String>, categories: Set<SettingsCategory>): List<String> {
    val componentManager = ApplicationManager.getApplication() as ComponentManagerImpl
    val retval = hashSetOf<String>()
    componentManager.processAllImplementationClasses { aClass, _ ->
      if (PersistentStateComponent::class.java.isAssignableFrom(aClass)) {
        val state = aClass.getAnnotation(State::class.java) ?: return@processAllImplementationClasses
        if (!categories.contains(state.category))
          return@processAllImplementationClasses
        state.storages.forEach { storage ->
          if (!storage.deprecated && allFiles.contains(storage.value)) {
            @Suppress("UNCHECKED_CAST")
            retval.add(storage.value)
          }
        }
      }
    }
    val schemeCategories = hashSetOf<String>()
    // fileSpec is e.g. keymaps/mykeymap.xml
    (SchemeManagerFactory.getInstance() as SchemeManagerFactoryBase).process {
      if (categories.contains(it.getSettingsCategory())) {
        schemeCategories.add(it.fileSpec)
      }
    }
    for (file in allFiles) {
      val split = file.split('/')
      if (split.size != 2)
        continue

      if (schemeCategories.contains(split[0])){
        retval.add(file)
      }
    }

    return retval.toList()
  }

  fun installPlugins(progressIndicator: ProgressIndicator, pluginIds: List<String>) {
    val importOptions = ConfigImportHelper.ConfigImportOptions(LOG)
    importOptions.isHeadless = true
    importOptions.headlessProgressIndicator = progressIndicator
    importOptions.importSettings = object : ConfigImportSettings {
      override fun processPluginsToMigrate(newConfigDir: Path,
                                           oldConfigDir: Path,
                                           bundledPlugins: MutableList<IdeaPluginDescriptor>,
                                           nonBundledPlugins: MutableList<IdeaPluginDescriptor>) {
        nonBundledPlugins.removeIf { !pluginIds.contains(it.pluginId.idString) }
      }
    }
    ConfigImportHelper.migratePlugins(
      pluginsPath,
      configDirPath,
      PathManager.getPluginsDir(),
      PathManager.getConfigDir(),
      importOptions
    ) { false }
  }

  internal class ImportStreamProvider(private val configDirPath: Path) : StreamProvider {
    override val isExclusive = true

    override fun write(fileSpec: String, content: ByteArray, roamingType: RoamingType) {
      LOG.warn("Writing to $fileSpec (Will do nothing)")
    }

    override fun read(fileSpec: String, roamingType: RoamingType, consumer: (InputStream?) -> Unit): Boolean {
      (configDirPath / PathManager.OPTIONS_DIRECTORY / fileSpec).let {
        if (it.exists()) {
          consumer(FileInputStream(it.toFile()))
          return true
        }
      }
      (configDirPath / fileSpec).let {
        if (it.exists()) {
          consumer(FileInputStream(it.toFile()))
          return true
        }
      }
      return false
    }

    override fun processChildren(path: String,
                                 roamingType: RoamingType,
                                 filter: (name: String) -> Boolean,
                                 processor: (name: String, input: InputStream, readOnly: Boolean) -> Boolean): Boolean {
      LOG.warn("Process Children $path")
      val folder = configDirPath.resolve(path)
      if (!folder.exists()) return true

      Files.walkFileTree(folder, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes?): FileVisitResult {
          if (!filter(file.name)) return FileVisitResult.CONTINUE
          if (!file.isRegularFile()) return FileVisitResult.CONTINUE

          val shouldProceed = file.inputStream().use { inputStream ->
            val fileSpec = configDirPath.relativize(file).systemIndependentPath
            read(fileSpec) {
              processor(file.fileName.toString(), inputStream, false)
            }
          }
          return if (shouldProceed) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
        }
      })
      // this method is called only for reading => no SETTINGS_CHANGED_TOPIC message is needed
      return true
    }

    override fun delete(fileSpec: String, roamingType: RoamingType): Boolean {
      LOG.warn("Deleting $fileSpec")
      return false
    }

  }

  companion object {
    val LOG = logger<JbSettingsImporter>()
  }
}