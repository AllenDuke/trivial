package com.github.AllenDuke.util;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;


/**
 * @author 杜科
 * @description 解析myrpc.yml文件(源码中位于resource文件夹下)
 * @contact AllenDuke@163.com
 * @since 2020/2/27
 */
public class YmlUtil {
    /**
     * 获取yml文件中的指定字段,返回一个map
     *
     * @param sourcename
     * @return
     */
    public static Map<String, Object> getResMap(String sourcename) {
        return YmlInit.getMapByName(YmlInit.ymlMap, sourcename);
    }

    // 配置文件仅需要读取一次,读取配置文件的同时把数据保存到map中,map定义为final,仅可以被赋值一次
    private static class YmlInit {
        //初始化文件得到的map
        private static final Map<String, Object> ymlMap = getYml();

        // 读取配置文件,并初始化ymlMap
        private static Map<String, Object> getYml() {
            Yaml yml = new Yaml();
            String path = Object.class.getResource("/").getPath().substring(1) + "rpc.yml";
            Reader reader = null;
            try {
                reader = new FileReader(new File(path));
            } catch (Exception e) {
                // TODO: handle exception
                e.printStackTrace();
            }
            return yml.loadAs(reader, Map.class);
        }

        // //传入想要得到的字段
        private static Map<String, Object> getMapByName(Map<String, Object> map, String name) {
            Map<String, Object> maps = new HashMap<String, Object>();
            Set<Map.Entry<String, Object>> set = map.entrySet();
            for (Map.Entry<String, Object> entry : set) {// 遍历map
                Object obj = entry.getValue();
                if (entry.getKey().equals(name))      // 递归结束条件
                    return (Map<String, Object>) obj;

                if (entry.getValue() instanceof Map) {//如果value是Map集合递归
                    maps = getMapByName((Map<String, Object>) obj, name);
                    if (maps == null)				   //递归的结果如果为空,继续遍历
                        continue;
                    return maps;					  //不为空返回
                }
            }
            return null;
        }
    }
}
