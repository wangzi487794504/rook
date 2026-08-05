package com.mewcode.context;

import com.mewcode.llm.ChatMessage;

/** 边界消息 —— 压缩后插入，提醒模型勿脑补 */
public class BoundaryMessage {
    public static ChatMessage postCompression() {
        return new ChatMessage("user",
                "[上下文已压缩] 以上为历史摘要。如需文件完整内容，请用 read_file 重新读取，勿根据摘要脑补代码细节。");
    }
}
