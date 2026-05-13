package com.autoclicker.arknights.util

/**
 * 明日方舟公招辅助工具 v2.0
 * 硬编码4星保底tag组合表 + 简单的OCR结果分析逻辑
 * 
 * 规则：识别到的tag列表中，如果存在以下组合之一，则选择该组合
 * 否则刷新tag，刷新次数用完则不选tag直接招
 * 
 * 来源：https://prts.wiki/w/%E5%85%AC%E5%BC%80%E6%8B%9B%E5%8B%9B
 */
object RecruitmentHelper {
    
    // 4星保底tag组合（每个组合包含2-3个tag）
    private val guaranty4StarCombos = listOf(
        setOf("近卫", "支援"),
        setOf("医疗", "支援"),
        setOf("重装", "防护"),
        setOf("特种", "减速"),
        setOf("先锋", "费用回复"),
        setOf("近卫", "群攻"),
        setOf("术师", "群攻"),
        setOf("狙击", "群攻"),
        setOf("近卫", "生存"),
        setOf("近战", "输出", "生存"),
        setOf("近战", "输出", "防护"),
        setOf("远程", "输出", "生存"),
        setOf("远程", "输出", "治疗"),
        setOf("近战", "输出", "位移"),
        setOf("近战", "群攻", "防护"),
        setOf("远程", "群攻", "削弱")
    )
    
    // 公招tag候选列表（用于OCR后处理）
    private val validTags = setOf(
        "近卫", "狙击", "医疗", "术师", "辅助", "特种", "重装", "先锋",
        "近战", "远程", "输出", "生存", "防护", "治疗", "支援", "群攻",
        "费用回复", "减速", "削弱", "位移", "召唤", "控场"
    )
    
    /**
     * 公招分析结果
     */
    data class RecruitmentResult(
        val shouldSelectTags: Boolean,   // 是否需要选tag
        val selectedTags: List<String>,   // 选择的tag
        val shouldRefresh: Boolean,       // 是否应该刷新tag
        val justRecruit: Boolean,         // 不选tag直接招
        val reason: String                // 原因说明
    )
    
    /**
     * 分析可用tag列表，决定下一步操作
     * @param availableTags 可用的tag列表（从OCR识别得到）
     * @param refreshRemaining 剩余刷新次数
     * @return RecruitmentResult 分析结果
     */
    fun analyzeTags(availableTags: List<String>, refreshRemaining: Int): RecruitmentResult {
        // 过滤掉无效tag
        val validAvailableTags = availableTags.filter { it in validTags }.toSet()
        
        // 检查是否有4星保底组合
        for (combo in guaranty4StarCombos) {
            if (combo.all { it in validAvailableTags }) {
                return RecruitmentResult(
                    shouldSelectTags = true,
                    selectedTags = combo.toList(),
                    shouldRefresh = false,
                    justRecruit = false,
                    reason = "检测到4星保底组合: ${combo.joinToString("+")}"
                )
            }
        }
        
        // 没有保底组合，看能否刷新
        if (refreshRemaining > 0) {
            return RecruitmentResult(
                shouldSelectTags = false,
                selectedTags = emptyList(),
                shouldRefresh = true,
                justRecruit = false,
                reason = "没有保底组合，刷新tag（剩余${refreshRemaining}次）"
            )
        }
        
        // 刷新用完，不选tag直接招
        return RecruitmentResult(
            shouldSelectTags = false,
            selectedTags = emptyList(),
            shouldRefresh = false,
            justRecruit = true,
            reason = "刷新次数用完，不选tag直接招"
        )
    }
    
    /**
     * 从OCR文本中提取可能的tag
     * @param ocrText OCR识别的原始文本
     * @return 可能的tag列表
     */
    fun extractTagsFromOcrText(ocrText: String): List<String> {
        val foundTags = mutableListOf<String>()
        for (tag in validTags) {
            if (ocrText.contains(tag)) {
                foundTags.add(tag)
            }
        }
        return foundTags
    }
    
    /**
     * 计算tag组合的分值（用于决定选哪些tag）
     * @param tags 选中的tag列表
     * @return 分值
     */
    fun calculateComboScore(tags: Set<String>): Int {
        var score = 0
        // 如果完全匹配一个4星组合，给高分
        for (combo in guaranty4StarCombos) {
            if (combo == tags) {
                return 100
            }
        }
        // 部分匹配给低分
        for (combo in guaranty4StarCombos) {
            val matchCount = combo.count { it in tags }
            if (matchCount > 0) {
                score += matchCount * 10
            }
        }
        return score
    }
}
