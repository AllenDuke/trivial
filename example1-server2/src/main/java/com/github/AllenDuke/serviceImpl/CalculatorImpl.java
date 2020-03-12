package com.github.AllenDuke.serviceImpl;

import com.github.AllenDuke.service.Calculator;

/**
 * @author 杜科
 * @description 计算器服务实现
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
public class CalculatorImpl implements Calculator {

    @Override
    public Integer add(Integer a, String b) {
        return a+Integer.valueOf(b);
    }

    @Override
    public int multipy(Integer a, Integer b) {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return a*b;
    }
}
