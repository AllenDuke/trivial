# trivial 1.4
比起版本1.3，增加了注解@TrivialScan与@TrivialService的使用，优化了异步调用机制。
## 1.3异步调用机制
使用的依然是park与unpark，但是，当考虑到这样的一些复杂情况时，是会出现问题的：
1. caller按序发起了rpc1, rpc2，得到resultFuture1, resultFuture2。现在的情形为：rpc1已有结果（netty io线程调用了一次unpark），
rpc2未有。而后，caller先调用resultFuture2.get()，caller本应阻塞的，但因为被unpark了一次，所以直接返回了，
而后来再调用resultFuture1.get()的时候，却阻塞住了，直到rpc2有结果了（netty io线程再次调用unpark）。
2. caller按序发起了rpc1, rpc2，得到resultFuture1, resultFuture2。现在的情形为：rpc1已有结果（netty io线程调用了一次unpark），
rpc2已有结果（netty io线程调用了一次unpark），但是**unpark的资源值最多为1，不能叠加（操作系统互斥锁）**，
当caller第二次调用resultFuture.get时，将永远阻塞。
## 1.4异步调用机制
想象一下这种情形：你要下楼拿外卖，如果外卖已经到了，那么你直接拿到了外卖就回去了，
如果外卖还没到，那么你就在楼下等，外卖送到后就把你唤醒了，然后你再去拿外卖。

使用的是synchronized与resultFuture.wait,resultFuture.notifyAll。这也保证不会lost-wake-up的。

当caller在有效期内（默认5分钟），去调用resultFuture.get，如果已经有结果了，那么直接获取后返回。如果还没结果，那么
```java
synchroized(this){
    //todo 将wait置于while中
    this.wait(); 
}
``` 
当netty io线程收到结果时，发现这是一个异步调用的结果，那么找出其对应的resultFuture
```java
synchroized(resultFuture){
    resultFuture.notifyAll();
}
```
## 1.2通信协议
消费者发送给生产者的信息由：4 Byte头部（记录接下来的数据长度）+ClientMessage（JSON字符串）。
以
```java
ClientMessage clientMessage =new ClientMessage((short) 1, "Calculator", "add", new Object[]{1, 2}, "java.lang.Integer
 java.lang.Integer");
```
为例。clientMessage经FastJson序列化后，共130 Byte，即 JSON.toJSONString(message).getBytes().length=130。于是 4+130=134 Byte。
其中：
1. 4 Byte的头部是**必须**的，因为当参数变得复杂时，不能仅仅以‘}’来作为clientMessage的结束符，因为在参数中很可能也会出现。
2. 自动生成的调用id是**必须**的。
3. 线程id**不是必须**的，在1.3中，修改了线程的等待的数据结构后，clientMessage中不再包含线程id。
4. 要调用的类，方法，参数是**必须**的。
5. 参数的原始类型是**必须**的。假如没有参数的原始类型而参数是一个复杂对象时，那么clientMessage整体经过JSON序列化后，
在服务端反序列化时，参数的类型变为JSONObject。这样无法确定一个方法，只能确定类名，方法名，参数个数，虽然可以逐个遍历，但耗时，
而且不能缓存。
## 1.3通信协议
消费者发送给生产者的信息由：4 Byte头部（记录接下来的数据长度）+8 Byte调用id + 2 Byte类名字节数 + 类名 + 2 Byte方法名字节数 + 方法名 + 参数（JSON字符串）。
以
```java
ClientMessage clientMessage =new ClientMessage( "Calculator", "add", new Object[]{1, 2});
```
为例。与版本1.2一样的调用，数据量为：
8 + 2 + "Calculator".getBytes().length + 2 + "add".getBytes().length+JSON.toJSONString(new Object[]{1, 2}).getBytes
().length=30。于是 4+30=34 Byte，减少了100Byte。
要点：
1. 减少不必要的JSON序列化。1.2中对clientMessage整体进行序列化，这样会生成不必要的信息（参考json格式），如参数名"rpcId"，
参数名"className"等等。将clientMessage拆开后，只对方法参数进行序列化，这样服务端可以将其反序列化为原来的样子，
可以计算出参数的类型，减少了参数类型的传输。
2. 4Byte 2Byte等可以1Byte中的某个数代替，用这个数作为各信息的分割符，这样也可减少数据量，但用4Byte 2Byte，来记录数据长度，虽然增大
了数据量，但这样在读取、判断半包时可以避免遍历，提升效率。

另外：ServerMessage中成功标志记录在rpcId（8 Byte）中没有用到的最高位（符号位），同样减少了1 Byte。0为成功，1为失败，
因为成功次数居多，可以减少运算。

参看common模块下util包中的TrivialEncoder与TrivialDecode。

## 数据结构ResultMap
1.2中，同一个线程，连续发起多次异步调用，只会保留最后一次调用的结果，此前的会被认为是历史信息，这是因为，这里的数据结构为，一个线程
对应一个调用结果。这是不合理的。

1.3中，一个线程可对应多个调用，一个调用对应一个结果，但这样也会有一个问题，就是要是线程发起异步调用后不去获取结果呢？岂不是会内存泄漏，
resultMap中的数据越积越多？

所以为什么防止这种情况的发生，我们需要清理掉那些越期（比如5分钟）不被获取的元素，我们可以让一个线程定期去清理。

但这样比较麻烦且浪费资源。这样带有清理功能的map，我们可以联想到ThreadLocalMap，线程在获取元素、线性探测时，会去清理掉，key为null的元素。

所以我们也可以让线程在操作这个map的时候去清理，但如果O(n)时间去清理很耗时且低效率。

简单分析可知，resultMap中的元素是按时间先后put进来的，所以我们应该维护一个线程安全的待清理双端队列，每次操作map的时候，只要判断队头
元素是否过期，如果不过期那么再将它加回队头，注意，在并发的时候可能队头元素会失序，但问题不大。

而且，联想redis的渐进式rehash，我们也让线程每次操作resultMap的时候，只对队头进行操作，即每次最多只清理一个过期元素。

当然，也可能真的就没有线程来去操作resultMap（同步调用一定会操作），但正常情况下会对resultMap进行很多次操作的，即过期元素最终能被清理干净。

详细参看client模块下，clientService包中的ResultMap
## 简介
这是一个简单的RPC框架，基于netty，利用了fastjson进行序列化和反序列化（因此要注意Number类的传输规则）。
作为一个平凡的框架，它的好处在于让平凡的我们能看清像Dubbo那些优秀的框架的源头在哪里。
从初学者的角度出发，让人看得明白的设计思路和编码风格。
有关并发的细节，都有详细的解释。
## 关键词
netty，并发，线程池，原子变量，阻塞队列，并发队列，synchronized，volatile，自旋，反射，jdk动态代理
## 目前
1. 多线程rpc，保障每个线程调用的正确性。
2. 超时机制。
3. 基于yml文件来进行参数设置。
4. 客户端超时重试机制。
5. 增加业务线程池，用来处理可能涉及io的调用，转移netty线程的阻塞点。
6. 增加自己实现的业务线程池（性能未经测试）。
7. 观察者模式，用户可自定义超时策略。
8. 集群化，添加zookeeper注册中心。
9. 客户端可同时向多个服务端通信。
10. 空闲机制，空闲后服务端主动断开连接。
11. 随机选取服务。
12. 超时后下一次请求将随机重路由到其他机器上。
13. 异步调用，可避免lost-wake-up。
14. 客户端增加本地存根，注册中心宕机也可以连接到服务端。
15. 客户端设置消息高水位，防止发送队列积压。
16. 服务端利用LRU缓存，实现简单的防护，防止恶意攻击。
17. 增加动态服务降级，服务降级后，消费方不发起调用，直接返回null。
18. 集成到spring项目中
19. 增加了注解@TrivialScan, @TrivialService使用。使用参见模块example1-server2。
## 未来
7. 优化LRU并发操作。
9. 可以为客户端不同种类任务定制不同的超时机制。
10. 增加随机权重的负载均衡策略以及自定义策略。
11. 下一版本中完善服务监控与动态治理（容错，降级等）。
## 使用
example模块为样例，要安装lombok。
注意：
1. server提供的服务，应尽量是无状态的，具体解释在server的InvokeHandler.java invoke方法114行。
2. server提供的服务，如果是在spring环境中，那么需要注册Bean——TrivialSpringUtil。
## 简单同步调用时序图
![image1](./image/a%20simple%20sequence%20chart.PNG)
## 简单异步调用时许图
![image2](./image/a%20simple%20asy%20sequence%20chart.PNG)
## 设计思路与一些细节
### 注册中心
1. 服务端启动后，将自己可以提供的服务注册到zookeeper上，如：/trivial/Calculator/providors/127.0.0.1:8000。
2. 客户端要消费的时候，如消费Calculator，先检查缓存中有没有该服务的连接，没有就到注册中心的/trivial/Calculator/providors节点
下拉取子节点。经过过滤后，得到一个随机的地址，然后通信，进行调用。
### 客户端
1. 在构建连接时，为避免多个线程为一个服务对一个或多个服务端建立多个连接，采用了同步，先建立连接的线程将连接放到缓存，
后面的线程从缓存中拿取，同时也防止对同一个远程主机建立多个连接。
2. 线程调用前应该确保连接已经建立，可以进行通信，为此，有时要自旋等待。
3. 当某次服务调用超时，且重试失败后，那么这个服务端的地址将会被记录在客户端中该服务的黑名单中，且从该服务的连接缓存中删除（假断连）
客户端中的所有线程下次调用该服务时，将会忽略该服务端，但不影响消费该服务端提供的其他服务（不应该直接断连的，因为它的其他服务
有可能正常提供，而且有可能别的线程正在与之通信），用户可自定义超时策略。
4. 调用失败或者成功，都应该唤醒**相应的、对的**线程，返回**相应的、对的**结果，尤为重要，因此这里要抽取出统一的同步因素，
忽略不同线程、不同连接带来的差异性，这里每次调用都有相应的id，同样超时观察线程关注的也是调用的id。
5. 线程误与黑名单中的服务端建立通信是可以接收的，不进行强同步、增加编码难度。
6. 当连接断开后，旧的连接进入空闲的连接池，将来可以重用。（暂未搞懂怎么重用）
7. 客户端可以多个线程同时消费一个或多个服务端提供的服务，多个线程、多个连接互不干扰。
8. 超时扫描线程为daemon线程，不断工作，直至所有用户线程退出或者收到结束标志且队列中没有其他任务。检测到超时后将通知监听器，
调用监听器的相关方法。
9. 在异步调用获取结果时，由于检查resultMap和加入waiterMap阻塞自己不是原子性的，所以在这中间有可能出现lost-wake-up的情况，解决方
案为：不管同步或是异步，在发起调用时都加入waiterMap，在netty收到信息时，都去unpark调用线程（充分利用了unpark可以先调用，
先发放许可的特性，这样的话，虽然前面的操作不是原子性，但是却可以避免lost-wake-up)。因此，为保证这样的操作，在每次获取结果时都要把
本次的结果清空（以免下一次调用时获取到了上一次的结果），在每次调用前，要检查上一次的结果有没有获取了，如果没有（上一次异步调用没有
去获取结果），那么要先消费掉上一次的许可，即先LockSupport.park()。
### 服务端
1. 每次收到客户端发来的调用信息时，进行解码调用，如有线程池，则连同连接信息封装成任务，待任务完成后可以根据任务中的连接信息，
将结果写到相应的连接，交由相应的netty线程发送。
2. 服务端进行空闲计时，达最大空闲空闲时间后，断开连接。
3. 自实现的线程池在源码中（common模块下）有详细解释。
4. 服务端比客户端简单，因为没有太多要同步的地方。
## BUG回忆录
1. 消息丢失。connect方法调用时非阻塞，所以在真正要发送信息时确保已经连接成功，为此有时要自旋等待。
2. 异步调用时，lost-wake-up。充分了利用LockSupport.unpark可以先调用先发放许可的特性（但不能累积）。
3. 客户端发送完大量信息后，连接关闭。一开始认为是客户端的问题。解决历程如下：
   * 一开始认为是客户端的问题。
   * 尝试找到触发连接关闭的阈值，发现是在一次发送13条时触发。
   * 以为是触发了消息队列高水位，经排查不是。
   * 网上寻找相关问题无果。
   * 是不是抛出某种异常未被捕获，在exceptionCaught打印异常信息。**强烈建议小心的重写父类HandlerAdapter的方法，要考虑
   是否应该调用父类的方法，如在channelInactive中调用super.channelInactive。**
   * 在channelInactive中调用super.channelInactive，发现概率性地有信息返回，概率与发送数量和发送时是否调用Thread.yield()有关。
   **实际上连接早已断开，super.channelInactive中只不过是已经解码好的信息继续fireChannelRead **
   * 至此是服务端的问题，服务端捕获异常，在exceptionCaught打印异常信息，发现原来是线程池的默认的拒绝策略——抛弃抛异常(咋就没想到)
   ，异常在调用者线程即netty线程抛出，netty线程捕捉到异常断开了连接。调整任务队列大小，遭拒绝后将告知客户端，BUG解决。
   * 总的来说，难排查的原因有以下几个：
     * 此次BUG出现的背景是测试新功能——消息队列高水位和服务端基于LRU的简单防护机制，以为与新功能有关，实际是无关的，也怪自己在
     上一次稳定版本中没有进行完备的测试即发送大量消息，导致现在才发现，验证了BUG发现越晚越难排查这句话。
     * 没有打印异常信息也就是重写的时候要小心。**细节啊，细节啊。**
4. OOM，Direct内存溢出，没有释放请求ByteBuf，即没有继承SimpleChannelInboundHandler，没有fireChannelRead，
也没有ReferenceCountUtil.release。继承SimpleChannelInboundHandler，重写channelRead0。
5. 在持续一段比较高的并发后，发现很多线程park在RPCClientHandler的send处。解决历程如下：
   * 以为是我用了HashMap，在高并发的时候，一些调用结果被误删或者被覆盖，但是全部换上ConcurrentHashMap后，情况仍然存在。
   * 仔细分析后，发现，线程获取到的null，理论上这绝对不会出现，它unpark后，要么获取服务发来的结果，要么获取超时结果，
   但这里首次确实获得null，然后下次再发起调用时，发现存在上次服务端发来的结果，于是认为上次的结果没有去获取，于是park了。
   * 再分析，得知这个null是由于没有正确的park造成的，首次park时直接返回，去获取结果，于是就拿到了null。
   * park线程没有阻塞，在没有许可的条件下直接返回了，那么可能了caller被设置了中断标志，于是park才会直接返回。
   * park前检查中断标志，发现线程并没有中断。
   * 开始挠头。
   * park是否有线程不安全的说法？并没有。
   * 查看LockSupport.park()，发现有这么一段话，The call spuriously (that is, **for no reason**) returns，它有可能会无故返回！
   * 至此问题得以解决，在park返回后，确定是否已有调用结果，如果没有那么在循环中park。
   * 注意：不能完全确定真的是因为LockSupport.park()无故返回，但我如此做后，问题不再出现。

## 与dubbo相异之处
1. trivial超时扫描线程使用blockingQueue；dubbo使用ConcurrentHashMap的值的集合视图。
详细比较参见：https://www.cnblogs.com/AllenDuke/p/12387493.html
2. trivial客户端不加入业务线程池，接收到结果时，由netty io线程经过简单解释后唤醒响应的线程，因为这个过程并不耗时且能快速响应；
dubbo加入业务线程池，接收到结果时，io线程封装成一个任务交予业务线程池，由业务线程池去唤醒调用者线程。如图：
![dubbo-invokeProcedure](./image/dubbo-invokeProcedure.PNG)   
3. trivial的协议中，传输的数据量似乎更小。

如果你觉得对你有帮助的话，就给个star吧。
