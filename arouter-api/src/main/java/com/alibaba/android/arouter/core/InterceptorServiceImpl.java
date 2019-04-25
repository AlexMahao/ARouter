package com.alibaba.android.arouter.core;

import android.content.Context;

import com.alibaba.android.arouter.exception.HandlerException;
import com.alibaba.android.arouter.facade.Postcard;
import com.alibaba.android.arouter.facade.annotation.Route;
import com.alibaba.android.arouter.facade.callback.InterceptorCallback;
import com.alibaba.android.arouter.facade.service.InterceptorService;
import com.alibaba.android.arouter.facade.template.IInterceptor;
import com.alibaba.android.arouter.thread.CancelableCountDownLatch;
import com.alibaba.android.arouter.utils.MapUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.alibaba.android.arouter.launcher.ARouter.logger;
import static com.alibaba.android.arouter.utils.Consts.TAG;

/**
 * All of interceptors
 *
 * @author zhilong <a href="mailto:zhilong.lzl@alibaba-inc.com">Contact me.</a>
 * @version 1.0
 * @since 2017/2/23 下午2:09
 */
@Route(path = "/arouter/service/interceptor")
public class InterceptorServiceImpl implements InterceptorService {
    private static boolean interceptorHasInit;
    private static final Object interceptorInitLock = new Object();

    @Override
    public void doInterceptions(final Postcard postcard, final InterceptorCallback callback) {
        if (null != Warehouse.interceptors && Warehouse.interceptors.size() > 0) {
            // 判断是否初始化完成，该方法是一个循环，除非打断，不然不会结束
            checkInterceptorsInitStatus();
            // 如果初始化失败，则中断路由跳转
            if (!interceptorHasInit) {
                callback.onInterrupt(new HandlerException("Interceptors initialization takes too much time."));
                return;
            }

            LogisticsCenter.executor.execute(new Runnable() {
                @Override
                public void run() {
                    // 线程同步工具类
                    CancelableCountDownLatch interceptorCounter = new CancelableCountDownLatch(Warehouse.interceptors.size());
                    try {
                        // 执行所有拦截器
                        _excute(0, interceptorCounter, postcard);
                        // 阻塞线程，等待拦截器执行或者超时
                        interceptorCounter.await(postcard.getTimeout(), TimeUnit.SECONDS);
                        // 如果getCount>0,说明拦截器没有执行完毕就超时了
                        if (interceptorCounter.getCount() > 0) {    // Cancel the navigation this time, if it hasn't return anythings.
                            callback.onInterrupt(new HandlerException("The interceptor processing timed out."));
                        } else if (null != postcard.getTag()) {    // Maybe some exception in the tag.
                            // 某个拦截器发起的拦截操作
                            callback.onInterrupt(new HandlerException(postcard.getTag().toString()));
                        } else {
                            // 继续进行路由跳转
                            callback.onContinue(postcard);
                        }
                    } catch (Exception e) {
                        callback.onInterrupt(e);
                    }
                }
            });
        } else {
            // 如果没有拦截器，直接回调成功，继续路由跳转
            callback.onContinue(postcard);
        }
    }

    /**
     * Excute interceptor
     *
     * @param index    current interceptor index
     * @param counter  interceptor counter
     * @param postcard routeMeta
     */
    private static void _excute(final int index, final CancelableCountDownLatch counter, final Postcard postcard) {
        if (index < Warehouse.interceptors.size()) {
            // 获取拦截器
            IInterceptor iInterceptor = Warehouse.interceptors.get(index);
            // 调用拦截器 process 方法
            iInterceptor.process(postcard, new InterceptorCallback() {
                @Override
                public void onContinue(Postcard postcard) {
                    // Last interceptor excute over with no exception.
                    // 计数，用于线程同步
                    counter.countDown();
                    // 下一个拦截
                    _excute(index + 1, counter, postcard);  // When counter is down, it will be execute continue ,but index bigger than interceptors size, then U know.
                }

                @Override
                public void onInterrupt(Throwable exception) {
                    // Last interceptor excute over with fatal exception.
                    // 中断标记
                    postcard.setTag(null == exception ? new HandlerException("No message.") : exception.getMessage());    // save the exception message for backup.
                    // 结束线程同步，置计数器为0
                    counter.cancel();
                    // Be attention, maybe the thread in callback has been changed,
                    // then the catch block(L207) will be invalid.
                    // The worst is the thread changed to main thread, then the app will be crash, if you throw this exception!
//                    if (!Looper.getMainLooper().equals(Looper.myLooper())) {    // You shouldn't throw the exception if the thread is main thread.
//                        throw new HandlerException(exception.getMessage());
//                    }
                }
            });
        }
    }

    @Override
    public void init(final Context context) {
        // 启动一个子线程
        LogisticsCenter.executor.execute(new Runnable() {
            @Override
            public void run() {
                // 如果interceptors如果不为null,则进行初始化
                if (MapUtils.isNotEmpty(Warehouse.interceptorsIndex)) {
                    for (Map.Entry<Integer, Class<? extends IInterceptor>> entry : Warehouse.interceptorsIndex.entrySet()) {
                        Class<? extends IInterceptor> interceptorClass = entry.getValue();
                        try {
                            // 构造Interceptor实例
                            IInterceptor iInterceptor = interceptorClass.getConstructor().newInstance();
                            // 调用Interceptor的init方法
                            iInterceptor.init(context);
                            // 保存Interceptor对象
                            Warehouse.interceptors.add(iInterceptor);
                        } catch (Exception ex) {
                            throw new HandlerException(TAG + "ARouter init interceptor error! name = [" + interceptorClass.getName() + "], reason = [" + ex.getMessage() + "]");
                        }
                    }
                    // 初始化完成标识
                    interceptorHasInit = true;

                    logger.info(TAG, "ARouter interceptors init over.");

                    synchronized (interceptorInitLock) {
                        interceptorInitLock.notifyAll();
                    }
                }
            }
        });
    }

    private static void checkInterceptorsInitStatus() {
        synchronized (interceptorInitLock) {
            while (!interceptorHasInit) {
                try {
                    interceptorInitLock.wait(10 * 1000);
                } catch (InterruptedException e) {
                    throw new HandlerException(TAG + "Interceptor init cost too much time error! reason = [" + e.getMessage() + "]");
                }
            }
        }
    }
}
