/**
 * Copyright 2004-2048 .
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ipd.jsf.gd.client;

import com.ipd.jsf.gd.error.ClientTimeoutException;
import com.ipd.jsf.gd.error.RpcException;
import com.ipd.jsf.gd.msg.MessageHeader;
import com.ipd.jsf.gd.msg.ResponseMessage;
import com.ipd.jsf.gd.protocol.Protocol;
import com.ipd.jsf.gd.protocol.ProtocolFactory;
import com.ipd.jsf.gd.util.Constants;
import com.ipd.jsf.gd.util.DateUtils;
import com.ipd.jsf.gd.util.JSFContext;
import com.ipd.jsf.gd.util.NetUtils;
import com.ipd.jsf.gd.util.RpcContext;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * Title: 返回消息Future <br>
 * <p/>
 * Description: 包含了get方法，同步等待返回<br>
 * <p/>
 */
public class MsgFuture<V> implements java.util.concurrent.Future<V>{

    /**
     * slf4j logger
     */
    private final static Logger logger = LoggerFactory.getLogger(MsgFuture.class);

    /**
     * 结果监听器，在返回成功或者异常的时候需要通知
     */
    private List<ResultListener> listeners = new ArrayList<ResultListener>();

    private volatile Object result;

    private short waiters;

    private static final String UNCANCELLABLE = "UNCANCELLABLE";

    private static final CauseHolder CANCELLATION_CAUSE_HOLDER  = new CauseHolder(new CancellationException());

    /**
     * 当前连接
     */
    private final Channel channel;
    /**
     * 当前消息头
     */
    private final MessageHeader header;
    /**
     * 用户设置的超时时间
     */
    private final int timeout;
    /**
     * Future生成时间
     */
    private final long genTime = JSFContext.systemClock.now(); //System.currentTimeMillis()
    /**
     * Future已发送时间
     */
    private volatile long sentTime;
    /**
     * Future完成的时间
     */
    private volatile long doneTime = 0l;
    /**
     * 是否同步调用，默认是
     */
    private boolean asyncCall;

    /**
     * 当前请求服务端版本 1.5.0+有此值
     */
    private Short providerJsfVersion;
    /**
     * 构造函数
     *
     * @param channel
     *         连接
     * @param header
     *         消息头
     * @param timeout
     *         超时时间
     */
    public MsgFuture(Channel channel, MessageHeader header, int timeout) {
        this.channel = channel;
        this.header = header;
        this.timeout = timeout;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean res = this.cancle0(mayInterruptIfRunning);
        notifyListeners();
        return res;
    }

    private boolean cancle0(boolean mayInterruptIfRunning){
        Object result = this.result;
        if (isDone0(result) || result == UNCANCELLABLE) {
            return false;
        }
        synchronized (this) {
            // Allow only once.
            result = this.result;
            if (isDone0(result) || result == UNCANCELLABLE) {
                return false;
            }
            this.result = CANCELLATION_CAUSE_HOLDER;
            this.setDoneTime();
            if (hasWaiters()) {
                notifyAll();
            }
        }
        return true;
    }

    @Override
    public boolean isCancelled() {
        return this.result == CANCELLATION_CAUSE_HOLDER;
    }

    @Override
    public boolean isDone() {
        return isDone0(result);
    }

    private static boolean isDone0(Object result) {
        return result != null && result != UNCANCELLABLE;
    }

    @Override
    public V get() throws InterruptedException {
        return get(timeout, TimeUnit.MILLISECONDS);
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException{
        timeout = unit.toMillis(timeout); // 转为毫秒
        long remaintime = timeout - (sentTime - genTime); // 剩余时间
        if (remaintime <= 0) { // 没有剩余时间不等待
            if (isDone()) { // 直接看是否已经返回
                return getNow();
            }
        } else { // 等待剩余时间
            if (await(remaintime, TimeUnit.MILLISECONDS)) {
                return getNow();
            }
        }
        this.setDoneTime();
        throw clientTimeoutException(false);
    }

    /**
     * 构建超时异常
     *
     * @param scan
     *         是否扫描线程
     * @return 异常ClientTimeoutException
     */
    public ClientTimeoutException clientTimeoutException(boolean scan) {
        Date now = new Date();
        String errorMsg = (sentTime > 0 ? "[JSF-22110]Waiting provider return response timeout"
                : "[JSF-22111]Consumer send request timeout")
                + ". Start time: " + DateUtils.dateToMillisStr(new Date(genTime))
                + ", End time: " + DateUtils.dateToMillisStr(now)
                + ((sentTime > 0 ?
                ", Client elapsed: " + (sentTime - genTime)
                        + "ms, Server elapsed: " + (now.getTime() - sentTime)
                : ", Client elapsed: " + (now.getTime() - genTime))
                + "ms, Timeout: " + timeout
                + "ms, MsgHeader: " + this.header
                + ", Channel: " + NetUtils.channelToString(channel.localAddress(), channel.remoteAddress()))
                + (scan ? ", throws by scan thread" : ".");
        return new ClientTimeoutException(errorMsg);
    }

    public boolean isSuccess() {
        Object result = this.result;
        if (result == null ) {
            return false;
        }
        return !(result instanceof CauseHolder);
    }

    public Throwable cause() {
        Object result = this.result;
        if (result instanceof CauseHolder) {
            return ((CauseHolder) result).cause;
        }
        return null;
    }

    public V getNow() {
        Object result = this.result;
        if (result instanceof ResponseMessage) { // 服务端返回
            ResponseMessage tmp = (ResponseMessage) result;
            if (tmp.getMsgBody() != null) {
                synchronized (this) {
                    if (tmp.getMsgBody() != null) {
                        Protocol protocol = ProtocolFactory.getProtocol(tmp.getProtocolType(), tmp.getMsgHeader().getCodecType());
                        try {
                            if (providerJsfVersion != null) { // 供序列化时特殊判断
                                RpcContext.getContext().setAttachment(Constants.HIDDEN_KEY_DST_JSF_VERSION, providerJsfVersion);
                            }
                            // TODO 是否追加对方语言 c++？
                            ResponseMessage ins = (ResponseMessage) protocol.decode(tmp.getMsgBody(), ResponseMessage.class.getCanonicalName());
                            if (ins.getResponse() != null) {
                                tmp.setResponse(ins.getResponse());
                            } else if (ins.getException() != null) tmp.setException(ins.getException());
                        } finally {
                            if (providerJsfVersion != null) { // 供序列化时特殊判断
                                RpcContext.getContext().removeAttachment(Constants.HIDDEN_KEY_DST_JSF_VERSION);
                            }
                            if (tmp.getMsgBody() != null) {
                                tmp.getMsgBody().release();
                            }
                            tmp.setMsgBody(null); // 防止多次调用get方法触发反序列化异常
                        }
                    }
                }
            }
        } else if (result instanceof CauseHolder) { // 本地异常
            Throwable e = ((CauseHolder) result).cause;
            if (e instanceof RpcException) {
                RpcException rpcException = (RpcException) e;
                rpcException.setMsgHeader(header);
                throw rpcException;
            } else {
                throw new RpcException(this.header, ((CauseHolder) result).cause);
            }
        }
        return (V) result;
    }

    public void releaseIfNeed() {
        this.releaseIfNeed0((V) result);
    }

    private synchronized void releaseIfNeed0(V result){
        if(result instanceof ResponseMessage){
            ByteBuf byteBuf = ((ResponseMessage) result).getMsgBody();
            if(byteBuf != null && byteBuf.refCnt() > 0 ){
                byteBuf.release();
            }
        }
    }

    private static final class CauseHolder {
        final Throwable cause;
        private CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    public boolean await(long timeout, TimeUnit unit)
            throws InterruptedException {
        return await0(unit.toNanos(timeout), true);
    }

    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        if (isDone()) {
            return true;
        }

        if (timeoutNanos <= 0) {
            return isDone();
        }

        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        long startTime = System.nanoTime();
        long waitTime = timeoutNanos;
        boolean interrupted = false;

        try {
            synchronized (this) {
                if (isDone()) {
                    return true;
                }

                if (waitTime <= 0) {
                    return isDone();
                }

                //checkDeadLock(); need this check?
                incWaiters();
                try {
                    for (;;) {
                        try {
                            wait(waitTime / 1000000, (int) (waitTime % 1000000));
                        } catch (InterruptedException e) {
                            if (interruptable) {
                                throw e;
                            } else {
                                interrupted = true;
                            }
                        }

                        if (isDone()) {
                            return true;
                        } else {
                            waitTime = timeoutNanos - (System.nanoTime() - startTime);
                            if (waitTime <= 0) {
                                return isDone();
                            }
                        }
                    }
                } finally {
                    decWaiters();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }


    public MsgFuture<V> setSuccess(V result) {
        if(this.isCancelled()){
            this.releaseIfNeed0(result);
            return this;
        }
        if (setSuccess0(result)) {
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    private boolean setSuccess0(V result) {
        if (isDone()) {
            return false;
        }
        synchronized (this) {
            // Allow only once.
            if (isDone()) {
                return false;
            }
            if (this.result == null) {
                this.result = result;
                this.setDoneTime();
            }
            if (hasWaiters()) {
                notifyAll();
            }
        }
        return true;
    }


    public MsgFuture<V> setFailure(Throwable cause) {
        if(this.isCancelled()){
            this.releaseIfNeed();
            return this;
        }
        if (setFailure0(cause)) {
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this, cause);
    }

    private boolean setFailure0(Throwable cause) {
        if (isDone()) {
            return false;
        }

        synchronized (this) {
            // Allow only once.
            if (isDone()) {
                return false;
            }
            result = new CauseHolder(cause);
            this.setDoneTime();
            if (hasWaiters()) {
                notifyAll();
            }
        }
        return true;
    }

    public MsgFuture addListener(ResultListener listener){

        if (listener == null) {
            throw new NullPointerException("listener");
        }

        if (isDone()) {
            notifyListener0(listener);
            return this;
        }

        synchronized (this) {
            if (!isDone()) {
                listeners.add(listener);
                return this;
            }
        }

        notifyListener0(listener);
        return this;
    }
    /*
     * notify all listener.
     */
    private void notifyListeners() {
        if (listeners == null || listeners.isEmpty()) {
            if ( isAsyncCall() && !this.isCancelled()){
                //主要是释放掉msgBody 的buf
                getNow();
            }
            return;
        }
        for (ResultListener resultListener : listeners) {
            notifyListener0(resultListener);
        }
    }
    /*
     * 调用listener 新启动线程 防止阻塞当前线程
     */
    private void notifyListener0(final ResultListener listener){
        listener.operationComplete(this);
    }

    private boolean hasWaiters() {
        return waiters > 0;
    }

    private void incWaiters() {
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }
        waiters ++;
    }

    private void decWaiters() {
        waiters --;
    }

    /**
     * 查看生成时间
     *
     * @return 生成时间
     */
    public long getGenTime() {
        return genTime;
    }

    /**
     * 查看超时时间
     *
     * @return 超时时间
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     *  查看完成时间
     * @return  完成时间
     */
    public long getDoneTime() {
        if (doneTime == 0l) {
            long remaintime = timeout - (sentTime - genTime); // 剩余时间
            if (remaintime <= 0) { // 没有剩余时间
                doneTime = JSFContext.systemClock.now();
            }
        }
        return doneTime;
    }

    private void setDoneTime() {
        if (doneTime == 0l) {
            doneTime = JSFContext.systemClock.now();
        }
    }

    /**
     * 设置已发送时间
     *
     * @param sentTime
     *         已发送时间
     */
    public void setSentTime(long sentTime) {
        this.sentTime = sentTime;
    }

    /**
     * 是否异步调用
     *
     * @return 是否异步调用
     */
    public boolean isAsyncCall() {
        return asyncCall;
    }

    /**
     * 标记为异步调用
     *
     * @param asyncCall
     *         是否异步调用
     */
    public void setAsyncCall(boolean asyncCall) {
        this.asyncCall = asyncCall;
    }

    /**
     * 服务端jsf版本号
     *
     * @return 服务端jsf版本号
     */
    public Short getProviderJsfVersion() {
        return providerJsfVersion;
    }

    /**
     * 服务端jsf版本号
     *
     * @param providerJsfVersion
     *         服务端jsf版本号
     */
    public void setProviderJsfVersion(Short providerJsfVersion) {
        this.providerJsfVersion = providerJsfVersion;
    }
}