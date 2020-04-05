package com.github.AllenDuke.business;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author 杜科
 * @description LRU缓存
 * @contact AllenDuke@163.com
 * @date 2020/4/4
 */
public class LRU<K,V> extends LinkedHashMap<K,V> {

    private int capacity=50;

    public LRU(){
        super(50,0.75f,true);//开启访问顺序
    }

    public LRU(int capacity){
        super(capacity,0.75f,true);
        this.capacity=capacity;
    }

    /**
     * @description: 如果当前大小大于容量或者最旧节点存储已经超过一天，那么删除
     * @param eldest 最旧节点
     * @return: boolean
     * @author: 杜科
     * @date: 2020/4/4
     */
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        InvokeErrorNode errorNode= (InvokeErrorNode) eldest.getValue();

        return this.size()>capacity||System.currentTimeMillis()-errorNode.getLastTime()>1000*60*60*24;
    }

}
