package com.example.cellrebelauto.automation

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Robust utility for finding nodes in the accessibility tree.
 * Provides multiple search strategies with fallbacks.
 *
 * # 无障碍节点查找工具类：提供多种搜索策略和回退机制
 */
object NodeFinder {

    /**
     * Finds the first node whose text contains [query] (case-insensitive).
     * Optionally filter by [clickable] and/or [className].
     *
     * # 查找文本包含 query 的第一个节点（不区分大小写）
     */
    fun findByText(
        root: AccessibilityNodeInfo,
        query: String,
        clickable: Boolean? = null,
        className: String? = null
    ): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText(query)
        return candidates.firstOrNull { node ->
            val textMatch = node.text?.toString()?.contains(query, ignoreCase = true) == true
            val clickableMatch = clickable == null || node.isClickable == clickable
            val classMatch = className == null || node.className?.toString() == className
            textMatch && clickableMatch && classMatch
        }
    }

    /**
     * Finds all nodes whose text contains [query] (case-insensitive).
     * # 查找所有文本包含 query 的节点
     */
    fun findAllByText(
        root: AccessibilityNodeInfo,
        query: String
    ): List<AccessibilityNodeInfo> {
        return root.findAccessibilityNodeInfosByText(query)
            .filter { it.text?.toString()?.contains(query, ignoreCase = true) == true }
    }

    /**
     * Finds the first node with the given class name via DFS.
     * # 通过深度优先搜索查找指定 className 的第一个节点
     */
    fun findByClassName(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (root.className?.toString() == className) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findByClassName(child, className)
            if (found != null) return found
        }
        return null
    }

    /**
     * Finds the first node whose content description contains [desc].
     * # 查找 contentDescription 包含 desc 的第一个节点
     */
    fun findByContentDescription(
        root: AccessibilityNodeInfo,
        desc: String
    ): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findByContentDescription(child, desc)
            if (found != null) return found
        }
        return null
    }

    /**
     * Finds a node by view ID (resource name).
     * # 通过 viewIdResourceName 查找节点
     */
    fun findByViewId(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return nodes.firstOrNull()
    }

    /**
     * Flattens the accessibility tree into a list (DFS order).
     * Useful for scanning nearby nodes.
     * # 将无障碍树展平为列表（深度优先遍历）
     */
    fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        flattenRecursive(root, result)
        return result
    }

    private fun flattenRecursive(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        result.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            flattenRecursive(child, result)
        }
    }

    /**
     * Searches for a nearby node that satisfies [predicate], within [range] positions
     * of [anchor] in the flattened tree.
     *
     * # 在展平树中 anchor 附近 range 个位置内，查找满足条件的节点
     */
    fun findNearby(
        allNodes: List<AccessibilityNodeInfo>,
        anchor: AccessibilityNodeInfo,
        range: Int = 5,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val anchorIndex = allNodes.indexOf(anchor)
        if (anchorIndex < 0) return null
        val start = maxOf(0, anchorIndex - range)
        val end = minOf(allNodes.size - 1, anchorIndex + range)
        for (i in start..end) {
            if (predicate(allNodes[i])) return allNodes[i]
        }
        return null
    }

    /**
     * Finds a clickable node by text, with fallback to content description.
     * # 先按文本查找可点击节点，失败则按 contentDescription 查找
     */
    fun findClickable(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        // # 策略 1：按文本查找可点击节点
        val byText = findByText(root, label, clickable = true)
        if (byText != null) return byText

        // # 策略 2：按 contentDescription 查找
        val byDesc = findByContentDescription(root, label)
        if (byDesc != null && byDesc.isClickable) return byDesc

        // # 策略 3：查找文本匹配的节点，向上遍历找到可点击的父节点
        val textNode = findByText(root, label)
        if (textNode != null) {
            var parent = textNode.parent
            while (parent != null) {
                if (parent.isClickable) return parent
                parent = parent.parent
            }
        }

        return null
    }

    /**
     * Checks if any node in the tree contains text matching [query].
     * # 检查树中是否有节点包含指定文本
     */
    fun containsText(root: AccessibilityNodeInfo, query: String): Boolean {
        return root.findAccessibilityNodeInfosByText(query).isNotEmpty()
    }
}
