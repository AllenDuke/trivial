package com.github.AllenDuke;

import java.util.Random;

/**
 * @author 杜科
 * @description 测试负数取模
 * @contact AllenDuke@163.com
 * @date 2020/3/12
 */
public class Test {
    public static void main(String[] args) {
        System.out.println(-3 % 5);//-3
        System.out.println(5 % -3);//2
        System.out.println(-5 % -3);//-2
        System.out.println(-3 % -5);//-3
        for (int i = 0; i < 100; i++) {
            System.out.println(new Random().nextInt(10));
        }
    }
}
