package com.github.AllenDuke.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * @author 杜科
 * @description 自定义类加载器 在trivial中，这里只用户扫描，不去加载
 * @contact 15521177704
 * @since 2019/11/11
 */
public class TrivialClassLoader extends ClassLoader{

    private  Map<String, byte[]> classByteMap = new ConcurrentHashMap<>();
    private String classPath;

    public TrivialClassLoader(String classPath) {
        if (classPath.endsWith(File.separator)) {
            this.classPath = classPath;
        } else {
            this.classPath = classPath + File.separator;
        }
        preReadClassFile();
        preReadJarFile();
    }

    public  Map getClassByteMap(){return classByteMap;}

    public  boolean addClassByte(String className, byte[] byteCode) {

        if (!classByteMap.containsKey(className)) {
            classByteMap.put(className, byteCode);
            return true;
        }
        return false;
    }


    /**

     这里仅仅卸载了myclassLoader的classMap中的class,虚拟机中的
     Class的卸载是不可控的
     自定义类的卸载需要MyClassLoader不存在引用等条件
     @param className
     @return
     */
    public  boolean unloadClass(String className) {
        if (classByteMap.containsKey(className)) {
            classByteMap.remove(className);
            return true;
        }
        return false;
    }

    /**

     遵守双亲委托规则br/>*/
    @Override
    public Class<?> findClass(String name) {
        try {
            byte[] result = getClassByte(name);
            if (result == null) {
                System.out.println("cloudnot find "+name+" maybe loaded by parent");
            } else {
                return defineClass(name, result, 0, result.length);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    private byte[] getClassByte(String className) {
        if (classByteMap.containsKey(className)) {
            return classByteMap.get(className);
        } else {
            return null;
        }
    }

    private void preReadClassFile() {
        File[] files = new File(classPath).listFiles();
        if (files != null) {
            for (File file : files) {
                scanClassFile(file);
            }
        }
    }

    private void scanClassFile(File file) {
        if (file.exists()) {
            if (file.isFile() && file.getName().endsWith(".class")) {
                try {
                    byte[] byteCode = Files.readAllBytes(Paths.get(file.getAbsolutePath()));
                    String className = file.getAbsolutePath().replace(classPath, "")
                            .replace(File.separator, ".")
                            .replace(".class", "");
                    //if(findClass(className)!=null)
                        addClassByte(className, byteCode);
                    //System.out.println(className);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (file.isDirectory()) {
                for (File f : file.listFiles()) {
                    scanClassFile(f);
                }
            }
        }
    }

    private void preReadJarFile() {
        File[] files = new File(classPath).listFiles();
        if (files != null) {
            for (File file : files) {
                scanJarFile(file);
            }
        }
    }

    private void readJAR(JarFile jar) throws IOException {
        Enumeration<JarEntry> en = jar.entries();
        while (en.hasMoreElements()) {
            JarEntry je = en.nextElement();
            je.getName();
            String name = je.getName();
            if (name.endsWith(".class")) {
//String className = name.replace(File.separator, ".").replace(".class", "");
                String className = name
                        .replace("\\", ".")
                        .replace("/", ".")
                        .replace(".class", "");
                InputStream input = null;
                ByteArrayOutputStream baos = null;
                try {
                    input = jar.getInputStream(je);
                    baos = new ByteArrayOutputStream();
                    int bufferSize = 1024;
                    byte[] buffer = new byte[bufferSize];
                    int bytesNumRead = 0;
                    while ((bytesNumRead = input.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesNumRead);
                    }
                   // addClassByte(className, baos.toByteArray());
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (baos != null) {
                        baos.close();
                    }
                    if (input != null) {
                        input.close();
                    }
                }
            }
        }
    }

    private void scanJarFile(File file) {
        if (file.exists()) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                try {
                    readJAR(new JarFile(file));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (file.isDirectory()) {
                for (File f : file.listFiles()) {
                    scanJarFile(f);
                }
            }
        }
    }

    public void addJar(String jarPath) throws IOException {
        File file = new File(jarPath);
        if (file.exists()) {
            JarFile jar = new JarFile(file);
            readJAR(jar);
        }
    }
}
