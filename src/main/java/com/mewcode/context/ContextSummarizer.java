package com.mewcode.context;

import com.mewcode.llm.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 第2层：重量兜底 —— 调用 LLM 生成结构化摘要，压缩较早的历史消息。
 */
public class ContextSummarizer {

    private static final String SUMMARY_PROMPT = """
            你正在为一个AI编程助手执行上下文压缩。请将以下对话历史总结为结构化摘要。

            要求：
            1. 不要调用任何工具，只需输出文本。
            2. 先写分析草稿（分析对话流程、关键操作），再写正式摘要。
            3. 草稿用完即丢弃，最终只保留正式摘要。
            4. 摘要按以下结构组织：

               ## 用户意图
               ## 已完成步骤
               ## 当前状态
               ## 关键发现
               ## 待办事项
               ## 文件修改记录

            5. 保留具体文件名、行号、命令参数等关键细节。
            6. 不要过度概括——保留足够的细节让后续对话能继续。

            对话历史:
            """;

    private int consecutiveFailures = 0;

    /** 调用 LLM 生成摘要，返回替换后的消息列表 */
    public List<ChatMessage> summarize(List<ChatMessage> messages, LlmProvider provider,
                                        TokenEstimator estimator) {
        int splitPoint = estimator.findSplitPoint(messages, 10_000);

        List<ChatMessage> toSummarize = new ArrayList<>(messages.subList(0, splitPoint));
        List<ChatMessage> toKeep = new ArrayList<>(messages.subList(splitPoint, messages.size()));

        if (toSummarize.isEmpty()) return messages;

        // 拼接待摘要的消息为纯文本
        StringBuilder historyText = new StringBuilder();
        for (ChatMessage m : toSummarize) {
            if (m.content() != null) {
                historyText.append("[").append(m.role()).append("]: ")
                        .append(m.content()).append("\n\n");
            }
        }

        // 调用 LLM 生成摘要（不带工具）
        String fullPrompt = SUMMARY_PROMPT + "\n" + historyText;
        List<ChatMessage> summaryRequest = List.of(new ChatMessage("user", fullPrompt));

        try {
            String summary = provider.complete(summaryRequest);
            consecutiveFailures = 0;

            // 构建新的消息列表：system + 摘要 + 边界消息 + 保留的原文
            List<ChatMessage> result = new ArrayList<>();
            result.add(new ChatMessage("user", "## 历史摘要\n" + summary));
            result.add(BoundaryMessage.postCompression());
            result.addAll(toKeep);
            return result;
        } catch (Exception e) {
            consecutiveFailures++;
            return messages; // 失败时返回原文
        }
    }

    public boolean isTripped() { return consecutiveFailures >= 3; }
    public void reset() { consecutiveFailures = 0; }
}
