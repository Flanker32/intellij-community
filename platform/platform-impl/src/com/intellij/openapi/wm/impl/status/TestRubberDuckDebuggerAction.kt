// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl.status

import com.intellij.ide.IdeBundle
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColorUtil
import com.intellij.ui.jcef.JCEFHtmlPanel
import java.awt.Dimension
import java.io.File

/**
 * @author Konstantin Bulenkov
 */
class TestRubberDuckDebuggerAction: DumbAwareAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val bgColor = ColorUtil.toHtmlColor(EditorColorsManager.getInstance().globalScheme.defaultBackground)
    val url = javaClass.getResource("RubberDuck.html")
    val html = url?.toURI()?.let {
      File(it).readText()
        .replace("background-color:#ffffff;", "background-color:$bgColor;")
    }
    if (html == null) return
    val panel = JCEFHtmlPanel(null).apply{
      setHtml(html)
    }.component
    panel.preferredSize = Dimension(300, 200)
    JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel)
      .setTitle(IdeBundle.message("rubber.duck.debugger.popup.title"))
      .setResizable(true)
      .setMovable(true)
      .setDimensionServiceKey(e.project, "rubber.duck.debugger.popup", true)
      .setCancelOnClickOutside(false)
      .createPopup().showInBestPositionFor(e.dataContext)
  }
}