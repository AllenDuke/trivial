package com.github.AllenDuke.myThreadPoolService;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
//TODO 增加可扩容、缩容的线程池服务（包括线程和并发队列，以降低线程竞争的激烈程度），可用一致性hash算法
/**
 * @author 杜科
 * @description 简单模仿线程池，原理基本一致，（原生的设计还是很巧妙、很复杂的，
 * 例如用一个AtomicInteger去保存多个状态,使得多个状态的切换是原子性的）
 * 先由核心线程处理，处理不来则加入队列，队列若满则新建非核心线程，达最大线程数则拒绝。
 * 每个线程完成当前任务后（还没有进入空闲，避免在空闲与非空闲之间频繁切换，而且造成线程不安全，因为如果此时有新任务进来，主线程尝试
 * 为它设定任务，而它自己又尝试从队列中拉取，由此覆盖，造成任务丢失）尝试从任务队列中拉取任务，
 * 拉取失败(队列已空)进入空闲状态（自己不再尝试从队列中拉取，而是‘轮询等待’主线程分配，注意防止分配到一半就去拉取），
 * 非核心线程记录和空闲时间有关的数据，拉取成功重置。
 * 非核心线程空闲一定时间后消亡，消亡后要从空闲非核心线程中移除，否则会造成任务丢失。
 * shutDonw后拒绝任务，所有存活线程在消费完任务队列后消亡。
 * @contact AllenDuke@163.com
 * @since 2019/11/30
 */
@Slf4j
public class ThreadPoolService {
    private int coreSize = 2;//核心线程数
    private int maxSize = 4;//最大线程数
    private long keepAlive = 2 * 1000;//空闲时间，单位毫秒
    private boolean isShutDown = false;
    /**
     * ThreadPoolExecutor用的是BlockingQueue(可设置超时等待)，这里用的ConcurrentLinkedQueue
     * 二者设计差别在于:
     * 前者：
     * 如果核心线程执行完当前任务后，尝试阻塞地从阻塞队列中拉取任务。
     * 如果非核心线程执行完当前任务后，尝试超时地从阻塞队列中拉取任务，若返回null则消亡。
     * 因此新任务进来是优先加入队列的，意味着这时候如果有大量任务并发过来，线程池只会接受队列大小(或者加上新建非核心线程数)
     * 节省cpu资源，但仍有线程状态切换的消耗，新任务并发数小（当然只是差了一个线程数而已），这方面设计较简单
     * 后者：
     * 如果核心线程执行完当前任务后，尝试从队列中拉取任务，若拉取失败，进入空闲自旋（消耗cpu)，等待分配任务
     * 如果非核心线程执行完当前任务后，尝试从队列中拉取任务，若拉取失败，进入空闲自旋，等待分配任务，进行空闲计时，到时消亡
     * 新任务进来优先分配给空闲线程，而后进入队列，意味着可接受（线程数+队列大小）的任务数
     * 没有线程状态切换的消耗，并发数较前者大
     * 但这方面的设计较为复杂，要严格控制线程空闲与在忙状态切换的条件，以免主线程分配了任务而自己有取队列拉取，造成丢失
     * 而要主线程的分配是线程安全的话，就必须要求分配时涉及的行为是线程安全的
     * 所以下面多出用到了线程安全的容器、变量，volatile关键字。
     * 总结：
     * 前者适合任务偶发提交（有时间隔挺久的）的情况
     * 后者适合任务频繁提交（即自旋时间少）的情况
     */
    private ConcurrentLinkedQueue<Runnable> taskQueue = new ConcurrentLinkedQueue();//并发任务队列，控制并发拉取
    private int queueCapacity = 10;//任务队列容量
    private AtomicInteger queueSize = new AtomicInteger(0);//当前任务队列大小，原子变量控制并发提交
    private RejectHandler rejectHandler = new DefaultRejectHandler();//拒绝策略
    private ConcurrentLinkedQueue<CoreThread> freeCorePool = new ConcurrentLinkedQueue();//空闲核心线程队列
    private ConcurrentLinkedQueue<NonCoreThread> freeNonCorePool = new ConcurrentLinkedQueue();//空闲非核心线程队列
    private AtomicInteger curSize = new AtomicInteger(2);//当前线程数，原子变量控制并发新建和消亡

    //无参初始化
    public ThreadPoolService(){
        CoreThread core0 = new CoreThread();
        core0.setName("core0");
        CoreThread core1 = new CoreThread();
        core1.setName("core1");
        freeCorePool.add(core0);
        freeCorePool.add(core1);
    }

    //含参初始化
    public ThreadPoolService(int coreSize,int maxSize,long keepAlive,int queueCapacity){
        this.coreSize=coreSize;
        this.maxSize=maxSize;
        this.keepAlive=keepAlive;
        this.queueCapacity=queueCapacity;
        for (int i = 0; i < coreSize; i++) {
            CoreThread core=new CoreThread();
            core.setName("core"+i);
            freeCorePool.add(core);
        }
    }

    //含参初始化
    public ThreadPoolService(int coreSize,int maxSize,long keepAlive,int queueCapacity,RejectHandler handler){
        this.rejectHandler=handler;
        this.coreSize=coreSize;
        this.maxSize=maxSize;
        this.keepAlive=keepAlive;
        this.queueCapacity=queueCapacity;
        for (int i = 0; i < coreSize; i++) {
            CoreThread core=new CoreThread();
            core.setName("core"+i);
            freeCorePool.add(core);
        }
    }

    /**
     * @param task 任务
     * @description: 有以下几种情况：
     * 1.如果线程池已经关闭则直接拒绝
     * 2.成功交付空闲线程
     * 3.成功加任务队列
     * 4.如果队列已满且已达最大线程数，那么拒绝
     * @return: void
     * @author: 杜科
     * @date: 2020/2/10
     */
    public void execute(Runnable task) {
        if (isShutDown) reject((task));
        else if(!executeByCore(task)&&!executeByNonCore(task)&&!waitInQueue(task)) {
            if(curSize.get()==maxSize) reject(task);//有可能刚询问完，队列就为空了
            else if(curSize.incrementAndGet()<=maxSize){//注意并发新建非核心线程
                NonCoreThread nonCore=new NonCoreThread();
                nonCore.setName("nonCore"+(curSize.get()-2));
                freeNonCorePool.add(nonCore);
                executeByNonCore(task);
            }else{//新建失败
                curSize.decrementAndGet();//将加上的1减回去
                reject(task);
            }

        }

    }

    /**
     * @param task
     * @description: 尝试从空闲的核心线程队列中弹出一个线程，任务就给该核心线程执行
     * @return: boolean true为交付成功，false为交付失败
     * @author: 杜科
     * @date: 2020/2/10
     */
    private boolean executeByCore(Runnable task) {
        CoreThread core = freeCorePool.poll();
        if (core != null) {
            core.setTask(task);
            if (core.getState() == Thread.State.NEW) core.start();
            return true;
        } else return false;

    }

    /**
     * @param task
     * @description: 尝试把任务加入队列，用原子变量控制
     * @return: boolean true为添加成功，false为添加失败
     * @author: 杜科
     * @date: 2020/2/10
     */
    private boolean waitInQueue(Runnable task) {
        //如果+1后仍在容量内，则添加成功
        if (queueSize.incrementAndGet() <= queueCapacity) {
            taskQueue.add(task);
            log.info("任务 "+task+" 加入队列");
            return true;
        }
        //+1后超出容量，则添加失败，需要把-1
        else {
            queueSize.decrementAndGet();
            return false;
        }
    }

    /**
     * @param task
     * @description: 尝试从空闲的非核心线程队列中弹出一个线程，任务就给该非核心线程执行
     * @return: boolean true为交付成功，false为交付失败
     * @author: 杜科
     * @date: 2020/2/10
     */
    private boolean executeByNonCore(Runnable task) {
        NonCoreThread nonCore = freeNonCorePool.poll();
        if (nonCore != null) {
            nonCore.setTask(task);
            if (nonCore.getState() == Thread.State.NEW) nonCore.start();
            return true;
        } else return false;
    }

    //拒绝当前任务
    private void reject(Runnable task) {
        rejectHandler.reject(task);
    }

    //关闭线程池，拒绝任务，线程消费完任务队列后消亡
    public void shutDown() {
        isShutDown = true;
        log.info("线程池关闭");
    }

    /**
     * @description: 核心线程不断工作，完成当前任务后会尝试从队列中拉取任务，在shutDown前不会消亡
     * @author: 杜科
     * @date: 2020/2/10
     */
    class CoreThread extends Thread {

        private volatile boolean isFree = false;//volatile禁止指令重排序，且确保主线程的修改对它本身可见
        private volatile Runnable task;

        /**
         * @description: 尝试从队列拉取任务，若成功则设置当前任务，失败则当前线程回到空闲核心线程队列
         * @return: void
         * @author: 杜科
         * @date: 2020/2/10
         */
        public void pullTask() {
            Runnable task = taskQueue.poll();
            if (task != null) {
                setTask(task);
                queueSize.decrementAndGet();
            } else {//失败说明任务队列已为空
                isFree = true;
                freeCorePool.add(this);
            }
        }

        public void setTask(Runnable task) {
            isFree = false;
            this.task = task;
        }

        @Override
        public void run() {
            while (!isShutDown || !taskQueue.isEmpty()) {
                if (task != null) {
                    task.run();
                    log.info(Thread.currentThread().getName() + "完成任务——" + task);
                    task = null;
                }
                //这个判断条件是很苛刻的
                if(!isFree&&task==null) pullTask();//二者都用volatile遵循happens-bofore,防止主线程修改到一半就去拉取
            }
            log.info(Thread.currentThread().getName() + "消亡");
        }
    }

    /**
     * @description: 非核心线程不断工作，完成当前任务后会尝试从队列中拉取任务，在空闲一定时间后消亡
     * @author: 杜科
     * @date: 2020/2/10
     */
    class NonCoreThread extends Thread {

        private volatile boolean isFree = false;//volatile确保主线程的修改对它本身可见
        private long beginFree;
        private volatile Runnable task;

        /**
         * @param task
         * @description: 每一次设置任务后重置空闲标志
         * @return: void
         * @author: 杜科
         * @date: 2020/2/10
         */
        public void setTask(Runnable task) {
            this.task = task;
            isFree = false;
        }

        /**
         * @description: 尝试从队列拉取任务，若成功则重新设置任务，
         * 若失败，不是空闲则设置空闲标记，进行空闲计时，当前线程回到空闲队列
         * @return: void
         * @author: 杜科
         * @date: 2020/2/10
         */
        public void pullTask() {
            Runnable task = taskQueue.poll();
            if (task != null) {
                setTask(task);
                queueSize.decrementAndGet();
            } else {//失败说明任务队列已为空
                isFree = true;
                beginFree = System.currentTimeMillis();
                freeNonCorePool.add(this);
            }
        }

        /**
         * @param
         * @description: 在没有shutDown且(在忙或者剩余时间 > 0)时不会消亡，
         * 每次完成任务后将当前任务置空
         * 总是尝试从队列中拉取任务
         * 消亡时，当前线程数-1
         * @return: void
         * @author: 杜科
         * @date: 2020/2/10
         */
        @Override
        public void run() {
            while ((!isShutDown || !taskQueue.isEmpty()) && (!isFree || System.currentTimeMillis() - beginFree < keepAlive)) {
                if (task != null) {
                    task.run();
                    log.info(Thread.currentThread().getName() + "完成任务——" + task);
                    task = null;
                }
                if(!isFree&&task==null) pullTask();
            }
            curSize.decrementAndGet();
            freeNonCorePool.remove(this);//把消亡的线程从队列中移除，消亡后的线程不为null，不会被上面的executrByNonCore感知到，进而造成任务丢失
            log.info(Thread.currentThread().getName() + "消亡");
        }
    }

}
