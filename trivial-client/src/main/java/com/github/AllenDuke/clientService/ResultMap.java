package com.github.AllenDuke.clientService;

import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * @author 杜科
 * @description 带有清除功能的ConcurrentHashMap
 * @contact AllenDuke@163.com
 * @date 2020/8/6
 */
public class ResultMap<K,V> implements Map<K,V> {

    private class Node<V>{

        Node(long inTime,V value){
            this.inTime=inTime;
            this.value=value;
        }

        long inTime;//记录进入map的时间

        V value;
    }

    //当超过这个时限（5分钟），还有元素没有被获取时，那么这些元素将被删除
    private static long timeOut=5*60*1000;//ms

    private Deque<K> queue=new ConcurrentLinkedDeque<>();

    private Map<K,Node> map=new ConcurrentHashMap<>();

    /**
     * @description: 检查queue中最老元素，即队头，如果达存储时限，那么将其map中删掉。
     * 在并发执行时，有可能会将队列头部的一些元素打乱，但问题不大，因为我们的目的只是尽量让map中保持有用的元素，而这总能实现
     * @param
     * @return: void
     * @author: 杜科
     * @date: 2020/8/7
     */
    private void clearTimeOut(){
        if(queue.size()==0) return;
        K k = queue.pollFirst();
        Node node = map.get(k);
        if(node==null) return;
        if(System.currentTimeMillis()-node.inTime<timeOut) queue.addFirst(k);
        else {
            map.remove(k);
            System.out.println("成功清除过时键值对：" + k + " " + node.value);
        }
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return map.containsValue(value);
    }

    @Override
    public V get(Object key) {
        /**
         * 将操作分散到每一个get，类比redis渐进式rehash
         */
        clearTimeOut();
        return (V) map.get(key).value;
    }

    @Override
    public V put(K key, V value) {
        map.put(key,new Node(System.currentTimeMillis(),value));
        queue.addLast(key);
        return value;
    }

    @Override
    public V remove(Object key) {
        /**
         * 这里不去remove queue中的key，因为remove的话需要遍历，不remove也能达到最终目的
         */
        clearTimeOut();
        return (V) map.remove(key).value;
    }

    @Override
    public void putAll(Map m) {
        map.putAll(m);
        queue.addAll(m.keySet());
    }

    @Override
    public void clear() {
        map.clear();
        queue.clear();
    }

    @Override
    public Set keySet() {
        return map.keySet();
    }

    @Override
    public Collection values() {
        return null;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return null;
    }
}
