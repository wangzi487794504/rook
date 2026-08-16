package com.rook.context;

import com.rook.llm.ChatMessage;
import com.rook.llm.Usage;

import java.util.List;

/**
 * Token 近似估算器 —— 锚定最近 Usage 精确值，新增消息按字符数/4 估算。
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class TokenEstimator {

    private final int toolsDefTokens; // 工具定义 token 数（稳定，仅算一次）

    /** @return 方法返回值 */
    public TokenEstimator(String toolsJson) {
        this.toolsDefTokens = toolsJson != null ? toolsJson.length() / 4 : 0;
    }

    /** 估算消息列表的总 token 数 */
    public int estimate(List<ChatMessage> messages, Usage lastKnownUsage) {
        if (messages.isEmpty()) return 0;

        int total = toolsDefTokens;
        for (ChatMessage m : messages) {
            String c = m.content();
            total += (c != null ? c.length() : 0) / 4;
        }
        // 用锚定值校准：如果锚定值的 input 比当前估算准，用锚定值 + 增量
        if (lastKnownUsage != null && lastKnownUsage.inputTokens() > 0) {
            // 简单策略：取两者较大值
            total = Math.max(total, lastKnownUsage.inputTokens());
        }
        return total;
    }

    /** 从尾部往回数约 targetTokens 的位置，返回切分索引 */
    public int findSplitPoint(List<ChatMessage> messages, int targetTokens) {
        int sum = 0;
        int count = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            String c = messages.get(i).content();
            sum += (c != null ? c.length() : 0) / 4;
            count++;
            if (sum >= targetTokens && count >= 5) return i;
        }
        return 0; // 全保留
    }
}
