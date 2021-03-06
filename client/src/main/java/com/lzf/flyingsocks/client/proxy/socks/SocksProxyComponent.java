package com.lzf.flyingsocks.client.proxy.socks;

import com.lzf.flyingsocks.ConfigManager;
import com.lzf.flyingsocks.client.Client;
import com.lzf.flyingsocks.client.proxy.ProxyComponent;

import com.lzf.flyingsocks.client.proxy.ProxyRequest;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.ReferenceCountUtil;

import java.util.*;
import java.util.concurrent.*;

public class SocksProxyComponent extends ProxyComponent {

    /**
     * Worker线程池
     */
    private EventLoopGroup nettyWorkerLoopGroup;

    /**
     * 转发任务列表
     */
    private List<ClientMessageTransferTask> transferTaskList = new CopyOnWriteArrayList<>();

    /**
     * 转发任务执行线程池
     */
    private ExecutorService clientMessageProcessor;


    public SocksProxyComponent(Client client) {
        super(Objects.requireNonNull(client));
    }


    @Override
    protected void initInternal() {
        ConfigManager<?> manager = parent.getConfigManager();
        SocksConfig cfg = new SocksConfig(manager);
        manager.registerConfig(cfg);

        int cpus = getParentComponent().availableProcessors();
        nettyWorkerLoopGroup = new NioEventLoopGroup(Math.max(cpus, 4));

        clientMessageProcessor = new ThreadPoolExecutor(0, 12, 180, TimeUnit.SECONDS,
                new SynchronousQueue<>());

        addComponent(new SocksReceiverComponent(this));
        addComponent(new SocksSenderComponent(this));

        super.initInternal();
    }


    @Override
    protected void startInternal() {
        List<ClientMessageTransferTask> tasks = new ArrayList<>();
        for(int i = 0; i < 4; i++) {
            ClientMessageTransferTask t = new ClientMessageTransferTask();
            tasks.add(t);
            clientMessageProcessor.submit(t);
        }

        transferTaskList.addAll(tasks);

        super.startInternal();
    }


    @Override
    protected void stopInternal() {
        super.stopInternal();
    }


    EventLoopGroup getWorkerEventLoopGroup() {
        return nettyWorkerLoopGroup;
    }

    @Override
    protected boolean needProxy(String host) {
        return super.needProxy(host);
    }

    /**
     * 将代理请求放入组件
     * 这里重写了父类的pushProxyRequest方法，是为了将代理请求转发给ClientMessageTransferTask
     * @param request 代理请求
     */
    @Override
    public void publish(ProxyRequest request) {
        super.publish(request);
        if(!request.needProxy()) {
            if(request instanceof SocksProxyRequest) {
                ClientMessageTransferTask task = transferTaskList.get(Math.abs(request.hashCode() % transferTaskList.size()));
                task.pushProxyRequest((SocksProxyRequest) request);
            }
        }
    }

    /**
     * 这个任务会不断地接收无需进行代理的代理请求(即直连模式)
     * 并获取来自客户端的消息(ByteBuf对象)，然后将其转发给ServerChannel(目标服务器)。
     */
    private static final class ClientMessageTransferTask implements Runnable {
        private final List<SocksProxyRequest> requests = new LinkedList<>();
        private final BlockingQueue<SocksProxyRequest> newRequestQueue = new LinkedBlockingQueue<>();

        @Override
        public void run() {
            Thread t = Thread.currentThread();
            while(!t.isInterrupted()) {
                ListIterator<SocksProxyRequest> it = requests.listIterator();
                while(it.hasNext()) {
                    SocksProxyRequest req = it.next();
                    Channel cc = req.clientChannel();
                    if(cc != null && !cc.isActive()) {
                        it.remove();
                        clearProxyRequest(req);
                        continue;
                    }

                    Channel sc = req.serverChannel();
                    if(sc == null) {
                        continue;
                    }

                    if(!sc.isActive() || req.isCtlMark(31)) {
                        it.remove();
                        clearProxyRequest(req);
                        continue;
                    }

                    BlockingQueue<ByteBuf> queue = req.messageQueue();
                    ByteBuf buf;
                    try { //之所以采用循环是为了转发客户端请求时避免消息不完整
                        int time = 0;
                        while ((buf = queue.poll(1, TimeUnit.MILLISECONDS)) != null) {
                            time++;
                            if(time / 4 == 1) {  //每4次写入刷新一次
                                sc.writeAndFlush(buf);
                                time = 0;
                            } else {
                                sc.write(buf);
                            }
                        }

                        if(time > 0) {
                            sc.flush();
                        }

                    } catch (InterruptedException e) {
                        break;
                    }
                }

                //接收新的代理请求
                SocksProxyRequest newRequest;
                try {
                    while ((newRequest = newRequestQueue.poll(2, TimeUnit.MILLISECONDS)) != null) {
                        requests.add(newRequest);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }

            for(SocksProxyRequest request : requests) {
                clearProxyRequest(request);
            }

            requests.clear();
            newRequestQueue.clear();
        }

        private void clearProxyRequest(SocksProxyRequest request) {
            BlockingQueue<ByteBuf> queue = request.messageQueue();
            ByteBuf buf;
            try {
                while ((buf = queue.poll(1, TimeUnit.MILLISECONDS)) != null) {
                    ReferenceCountUtil.release(buf);
                }
            } catch (InterruptedException ignore) {
                // IGNORE
            }
        }

        private void pushProxyRequest(SocksProxyRequest request) {
            newRequestQueue.offer(request);
        }
    }
}
