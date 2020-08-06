package com.github.AllenDuke.clientService;

/**
 * @author 杜科
 * @description 标志这次调用超时
 * @contact AllenDuke@163.com
 * @date 2020/8/6
 */
public class TimeOutResult {

    private String tips;

    public TimeOutResult(String tips){
        this.tips=tips;
    }

    public String getTips() {
        return tips;
    }

    public void setTips(String tips) {
        this.tips = tips;
    }

    @Override
    public String toString() {
        return "TimeResult{" +
                "tips='" + tips + '\'' +
                '}';
    }
}
