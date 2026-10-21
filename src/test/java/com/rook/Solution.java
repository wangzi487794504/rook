import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Queue;
import java.util.concurrent.DelayQueue;

import static jdk.jfr.internal.handlers.EventHandler.timestamp;

某国际物流系统通过 Kafka 持续接收包裹状态事件。由于网络重试、消息重复投递等原因，同一个包裹可能在短时间内收到重复的状态事件。
请设计一个算法，实现以下规则：
对于同一包裹、同一种状态，如果该状态在过去 60 秒内已经被成功接收过，则当前事件视为重复事件，需要过滤。
accept("SF001", "PICKED_UP", 100);  // true
accept("SF001", "PICKED_UP", 120);  // false
accept("SF001", "IN_TRANSIT", 130); // true
accept("SF002", "PICKED_UP", 140);  // true
accept("SF001", "PICKED_UP", 161);  // true
public class EventDeduplicator {
    private final int windowSeconds;
    private final HashMap<String,Long> hashMap;
    private final Queue<Object[]> delayQueue;
    public EventDeduplicator(int windowSeconds) {
        this.windowSeconds=windowSeconds;
        this.hashMap=new HashMap<>();
        this.delayQueue=new ArrayDeque<Object[]>();
        //思路：时间窗口,hashmap去重,进去，hashmap验重，出队列，移除hashmap
        //延时队列
        // 初始化时间窗口
    }

    /**
     * @param trackingNo 运单号
     * @param status 物流状态
     * @param timestamp 事件到达时间（秒）
     * @return true 表示接收，false 表示重复
     */
    public boolean accept(
            String trackingNo,
            String status,
            long timestamp
    ) {
        //构造hashmap的key
        timeAndStatusSop(timestamp);
        //60s校验
        String key=trackingNo+status;
        //a b b
        //lasttime b 时间
        Long lastTime=hashMap.get(key);
        if (lastTime>timestamp){
            return false;
        }
        if (lastTime!=null && timestamp-lastTime<windowSeconds){
            return false;
        }
        hashMap.put(key,timestamp);
        delayQueue.add(new Object[]{key,timestamp});
        return false;
    }
    private void timeAndStatusSop( long timestamp){
        while (!delayQueue.isEmpty()){
            //数组维护时间，1表示时间
            Object[] peek = delayQueue.peek();
            Long time =(long) peek[1];
            if (timestamp-time<windowSeconds){
                break;
            }
            delayQueue.poll();
            //维护key用0元素
            String key= (String) peek[0];
            Long value = hashMap.get(key);
            if (value!=null && value==time){
                hashMap.remove(key);
            }

        }
    }
}