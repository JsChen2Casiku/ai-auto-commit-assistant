package com.casiku.aca.settings

import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

class PromptStyleListCellRenderer(
    private val isChinese: () -> Boolean,
) : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        if (value is PromptStyle) {
            text = if (isChinese()) value.chineseName else value.displayName
        }
        return component
    }
}

val PromptStyle.chineseName: String
    get() = when (this) {
        PromptStyle.CONVENTIONAL -> "约定式提交"
        PromptStyle.SIMPLE -> "简洁"
        PromptStyle.DETAILED -> "详细"
        PromptStyle.CUSTOM -> "自定义"
    }
