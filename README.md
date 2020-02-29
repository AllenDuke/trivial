# trivial
## 简介
这是一个简单的RPC框架，基于netty，利用了fastjson进行序列化和反序列化（因此要注意Number类的传输规则）。
作为一个平凡的框架，它的好处在于让平凡的我们能看清像Dubbo那些优秀的框架的源头在哪里。
从初学者的角度出发，让人看得明白的设计思路和编码风格。
能让人轻松读懂这个算是一个“框架”的源码，逐步建立阅读复杂框架源码的信心。
## 关键词
netty，并发，线程池，原子变量，阻塞队列，反射，jdk动态代理
## 目前
1. 多线程rpc，保障每个线程调用的正确性。
2. 超时机制。
3. 较为完善的日志。
3. 基于yml文件来进行参数设置。
4. 客户端超时重试机制。
## 未来
4. 增加业务线程池，用来处理可能涉及io的调用，转移netty线程的阻塞点。
5. 转换自己的业务线程池（性能未经测试）。
6. 可以为客户端不同种类任务定制不同的超时机制。
7. 异步调用
8. 集群化和超时请求重路由
9. 增加注解使用
10. 可集成到spring项目
## 使用
test模块下有样例。
