package com.casiku.aca.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.vcs.commit.CommitMessageUi
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class CommitMessageGenerationOverlay(
    private val commitMessageUi: CommitMessageUi,
) {
    private val active = AtomicBoolean(false)
    private var overlayPanel: LoadingOverlayPanel? = null
    private var targetComponent: JComponent? = null
    private var layeredPane: JLayeredPane? = null
    private var componentListener: ComponentAdapter? = null
    private var hierarchyBoundsListener: HierarchyBoundsAdapter? = null
    private var usingNativeLoading = false

    fun startGenerating() {
        active.set(true)
        updateStatus("AI 正在生成提交信息")
    }

    fun showThinking() {
        if (active.get()) {
            updateStatus("AI 正在思考中")
        }
    }

    fun stop() {
        active.set(false)
        onEdt {
            disposeOverlay()
        }
    }

    private fun updateStatus(message: String) {
        onEdt {
            if (!active.get()) return@onEdt
            val panel = ensureOverlay() ?: return@onEdt
            panel.statusText = message
            panel.startAnimation()
            updateBounds()
        }
    }

    private fun ensureOverlay(): LoadingOverlayPanel? {
        overlayPanel?.let { return it }

        val target = findTargetComponent()
        val rootPane = target?.rootPane
        val nextLayeredPane = rootPane?.layeredPane
        if (target == null || nextLayeredPane == null) {
            if (!usingNativeLoading) {
                commitMessageUi.startLoading()
                usingNativeLoading = true
            }
            return null
        }

        if (usingNativeLoading) {
            commitMessageUi.stopLoading()
            usingNativeLoading = false
        }

        val panel = LoadingOverlayPanel()
        nextLayeredPane.add(panel, JLayeredPane.POPUP_LAYER)

        val resizeListener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent?) = updateBounds()
            override fun componentResized(e: ComponentEvent?) = updateBounds()
            override fun componentShown(e: ComponentEvent?) = updateBounds()
        }
        val boundsListener = object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(e: HierarchyEvent?) = updateBounds()
            override fun ancestorResized(e: HierarchyEvent?) = updateBounds()
        }

        target.addComponentListener(resizeListener)
        target.addHierarchyBoundsListener(boundsListener)

        targetComponent = target
        layeredPane = nextLayeredPane
        overlayPanel = panel
        componentListener = resizeListener
        hierarchyBoundsListener = boundsListener
        updateBounds()
        return panel
    }

    private fun findTargetComponent(): JComponent? {
        val editorField = runCatching {
            commitMessageUi.javaClass.getMethod("getEditorField").invoke(commitMessageUi) as? JComponent
        }.getOrNull()
        if (editorField != null) return editorField

        return commitMessageUi as? JComponent
    }

    private fun updateBounds() {
        val target = targetComponent ?: return
        val panel = overlayPanel ?: return
        val parent = target.parent ?: return
        val layer = layeredPane ?: return
        val bounds = SwingUtilities.convertRectangle(parent, target.bounds, layer)
        panel.setBounds(bounds)
        panel.revalidate()
        panel.repaint()
    }

    private fun disposeOverlay() {
        if (usingNativeLoading) {
            commitMessageUi.stopLoading()
            usingNativeLoading = false
        }

        val target = targetComponent
        val resizeListener = componentListener
        val boundsListener = hierarchyBoundsListener
        if (target != null && resizeListener != null) {
            target.removeComponentListener(resizeListener)
        }
        if (target != null && boundsListener != null) {
            target.removeHierarchyBoundsListener(boundsListener)
        }

        val panel = overlayPanel
        panel?.stopAnimation()
        if (panel != null) {
            layeredPane?.remove(panel)
        }
        layeredPane?.revalidate()
        layeredPane?.repaint()

        overlayPanel = null
        targetComponent = null
        layeredPane = null
        componentListener = null
        hierarchyBoundsListener = null
    }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater(action)
        }
    }

    private class LoadingOverlayPanel : JPanel() {
        var statusText: String = "AI 正在生成提交信息"
            set(value) {
                field = value
                repaint()
            }

        private var frame = 0
        private val timer = Timer(32) {
            frame += 1
            repaint()
        }

        init {
            isOpaque = false
            isFocusable = false
            cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
            addMouseListener(object : MouseAdapter() {})
        }

        fun startAnimation() {
            if (!timer.isRunning) {
                timer.start()
            }
        }

        fun stopAnimation() {
            timer.stop()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                paintMask(g2)
                paintCard(g2)
            } finally {
                g2.dispose()
            }
        }

        private fun paintMask(g2: Graphics2D) {
            val base = UIUtil.getPanelBackground()
            val darkTheme = isDarkTheme()
            g2.color = alpha(base, if (darkTheme) 218 else 232)
            g2.fillRect(0, 0, width, height)
        }

        private fun paintCard(g2: Graphics2D) {
            val padding = JBUI.scale(18)
            val cardWidth = min(width - padding * 2, JBUI.scale(320)).coerceAtLeast(JBUI.scale(180))
            val cardHeight = min(height - padding * 2, JBUI.scale(148)).coerceAtLeast(JBUI.scale(104))
            val cardX = (width - cardWidth) / 2
            val cardY = (height - cardHeight) / 2
            val radius = JBUI.scale(20).toFloat()
            val darkTheme = isDarkTheme()

            g2.color = alpha(UIUtil.getPanelBackground(), if (darkTheme) 232 else 244)
            g2.fill(RoundRectangle2D.Float(cardX.toFloat(), cardY.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(), radius, radius))

            val iconSize = min(JBUI.scale(64), cardHeight - JBUI.scale(64)).coerceAtLeast(JBUI.scale(44))
            val centerX = cardX + cardWidth / 2
            val iconCenterY = cardY + JBUI.scale(48)
            paintOrbitalLoader(g2, centerX.toDouble(), iconCenterY.toDouble(), iconSize.toDouble())

            paintStatusText(g2, cardX, cardY, cardWidth, cardHeight)
        }

        private fun paintOrbitalLoader(g2: Graphics2D, centerX: Double, centerY: Double, size: Double) {
            val phase = frame * 0.075
            val blue = Color(34, 124, 255)
            val cyan = Color(32, 210, 255)
            val yellow = Color(255, 200, 30)
            val orbitStroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val oldTransform = g2.transform
            g2.stroke = orbitStroke

            listOf(-28.0, 28.0, 90.0).forEachIndexed { index, rotation ->
                g2.transform = oldTransform
                g2.rotate(Math.toRadians(rotation) + phase * (if (index == 2) -0.35 else 0.22), centerX, centerY)
                g2.color = alpha(if (index == 1) cyan else blue, 86)
                g2.draw(Ellipse2D.Double(centerX - size * 0.43, centerY - size * 0.16, size * 0.86, size * 0.32))
            }

            g2.transform = oldTransform
            g2.stroke = BasicStroke(JBUI.scale(3).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.color = alpha(blue, 210)
            val arcSize = size * 0.74
            g2.draw(
                Arc2D.Double(
                    centerX - arcSize / 2,
                    centerY - arcSize / 2,
                    arcSize,
                    arcSize,
                    Math.toDegrees(-phase),
                    230.0,
                    Arc2D.OPEN,
                )
            )

            val pulse = (sin(phase * 1.8) + 1.0) / 2.0
            g2.color = alpha(blue, (48 + pulse * 54).toInt())
            val halo = size * (0.34 + pulse * 0.08)
            g2.fill(Ellipse2D.Double(centerX - halo / 2, centerY - halo / 2, halo, halo))

            g2.color = alpha(cyan, 230)
            val core = size * 0.2
            g2.fill(Ellipse2D.Double(centerX - core / 2, centerY - core / 2, core, core))

            paintOrbitNode(g2, centerX, centerY, size * 0.42, phase, blue)
            paintOrbitNode(g2, centerX, centerY, size * 0.34, -phase * 1.25 + 1.7, cyan)
            paintOrbitNode(g2, centerX, centerY, size * 0.28, phase * 1.55 + 3.2, yellow)

            g2.transform = oldTransform
        }

        private fun paintOrbitNode(g2: Graphics2D, centerX: Double, centerY: Double, radius: Double, angle: Double, color: Color) {
            val nodeSize = JBUI.scale(6).toDouble()
            val x = centerX + cos(angle) * radius
            val y = centerY + sin(angle) * radius * 0.52
            g2.color = alpha(color, 245)
            g2.fill(Ellipse2D.Double(x - nodeSize / 2, y - nodeSize / 2, nodeSize, nodeSize))
        }

        private fun paintStatusText(g2: Graphics2D, cardX: Int, cardY: Int, cardWidth: Int, cardHeight: Int) {
            val dots = ".".repeat((frame / 12) % 4)
            val text = "$statusText$dots"
            val font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().style, UIUtil.getLabelFont().size2D + 1f)
            g2.font = font
            g2.color = UIUtil.getLabelForeground()

            val metrics = g2.fontMetrics
            val textX = cardX + (cardWidth - metrics.stringWidth(text)) / 2
            val textY = cardY + cardHeight - JBUI.scale(28)
            g2.drawString(text, textX, textY)
        }

        private fun alpha(color: Color, alpha: Int): Color {
            return Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
        }

        private fun isDarkTheme(): Boolean {
            val background = UIUtil.getPanelBackground()
            val luminance = background.red * 0.299 + background.green * 0.587 + background.blue * 0.114
            return luminance < 128.0
        }
    }
}
