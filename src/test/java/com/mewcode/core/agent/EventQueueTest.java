package com.mewcode.core.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件队列单元测试。
 */
class EventQueueTest {

    @Test
    void should_收发单事件_When_push一个event() throws Exception {
        BlockingQueue<AgentEvent> queue = new LinkedBlockingQueue<>(10);
        queue.put(AgentEvent.text("hello"));
        AgentEvent event = queue.take();

        assertEquals(AgentEventType.TEXT, event.type());
        assertEquals("hello", event.text());
    }

    @Test
    void should_按序收发_When_push10个事件() throws Exception {
        BlockingQueue<AgentEvent> queue = new LinkedBlockingQueue<>(20);
        for (int i = 0; i < 10; i++) {
            queue.put(AgentEvent.text("msg" + i));
        }

        for (int i = 0; i < 10; i++) {
            AgentEvent event = queue.take();
            assertEquals(AgentEventType.TEXT, event.type());
            assertEquals("msg" + i, event.text());
        }
    }

    @Test
    void should_DONE事件正常结束() throws Exception {
        BlockingQueue<AgentEvent> queue = new LinkedBlockingQueue<>(10);
        queue.put(AgentEvent.text("hello"));
        queue.put(AgentEvent.done());

        assertEquals(AgentEventType.TEXT, queue.take().type());
        assertEquals(AgentEventType.DONE, queue.take().type());
    }
}
