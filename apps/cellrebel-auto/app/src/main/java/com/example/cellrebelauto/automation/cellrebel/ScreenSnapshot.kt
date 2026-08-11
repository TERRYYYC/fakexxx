package com.example.cellrebelauto.automation.cellrebel

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pure-Kotlin view of the accessibility tree — decouples state detection
 * from Android so the detector is unit-testable on the JVM.
 * # 无障碍树的纯 Kotlin 视图：让状态检测脱离 Android，可在 JVM 上单测
 */
data class ScreenNode(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val children: List<ScreenNode> = emptyList()
)

/**
 * Flattens a ScreenNode tree in DFS order.
 * # 深度优先展平节点树
 */
fun ScreenNode.flatten(): List<ScreenNode> {
    val result = mutableListOf<ScreenNode>()
    fun walk(node: ScreenNode) {
        result.add(node)
        node.children.forEach(::walk)
    }
    walk(this)
    return result
}

/**
 * Adapts an AccessibilityNodeInfo tree to a ScreenNode tree.
 * Guards against null children and reference cycles (a11y trees can cycle).
 * # 将 AccessibilityNodeInfo 树转换为 ScreenNode 树，
 * # 对 null 子节点和引用循环做了防护
 */
fun AccessibilityNodeInfo.toScreenNode(): ScreenNode {
    val seen = mutableSetOf<AccessibilityNodeInfo>()
    fun walk(node: AccessibilityNodeInfo): ScreenNode {
        if (!seen.add(node)) {
            // # 循环引用：以无子节点叶子截断
            return ScreenNode(
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                clickable = node.isClickable,
                enabled = node.isEnabled
            )
        }
        val children = mutableListOf<ScreenNode>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            children.add(walk(child))
        }
        return ScreenNode(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            children = children
        )
    }
    return walk(this)
}
