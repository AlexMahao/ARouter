## ARouter 拦截器源码分析

在分析路由跳转时，最终的跳转会判断是否是绿色通道，如果不是，将会走拦截器相关的逻辑。

```java
 // 如果不是绿色通多，拦截器做拦截
        if (!postcard.isGreenChannel()) {   // It must be run in async thread, maybe interceptor cost too mush time made ANR.
            interceptorService.doInterceptions(postcard, new InterceptorCallback() {
                /**
                 * Continue process
                 *
                 * @param postcard route meta
                 */
                @Override
                public void onContinue(Postcard postcard) {
                    _navigation(context, postcard, requestCode, callback);
                }

                /**
                 * Interrupt process, pipeline will be destory when this method called.
                 *
                 * @param exception Reson of interrupt.
                 */
                @Override
                public void onInterrupt(Throwable exception) {
                    if (null != callback) {
                        callback.onInterrupt(postcard);
                    }

                    logger.info(Consts.TAG, "Navigation failed, termination by interceptor : " + exception.getMessage());
                }
            });
        } else {
            return _navigation(context, postcard, requestCode, callback);
        }

```

那么拦截器到底是怎么实现的呢？

### 逻辑分析

官方demo如下：

```java
@Interceptor(priority = 7)
public class Test1Interceptor implements IInterceptor {}

```

- 编译时期生成拦截器辅助类
- 初始化时期加载拦截器辅助类，并初始化拦截器服务。
- 跳转时期进行拦截器执行


### 编译时期生成辅助工具类


编译时期通过`apt`对`@Interceptor`做处理，生成拦截器辅助类。处理类为`InterceptorProcessor`。逻辑比较简单，看一下生成的辅助类代码：

```java
public class ARouter$$Interceptors$$app implements IInterceptorGroup {
  @Override
  public void loadInto(Map<Integer, Class<? extends IInterceptor>> interceptors) {
    interceptors.put(7, Test1Interceptor.class);
  }
}
```

该辅助类就是将拦截器的类存入到一个集合中去。其中`key`是声明的拦截器的优先级。


### 初始化时期加载拦截器

在之前分析路由跳转时，加载路由表到`Warehouse`中存储是通过`LogisticsCenter.init()`方法，该方法中获取`com.alibaba.android.arouter.routes`下面的所有类，其中包含了`Interceptors`的辅助类。在该方法中加载辅助类并将`Interceptor`存储到`Warehouse`中。

```java
  public synchronized static void init(Context context, ThreadPoolExecutor tpe) throws HandlerException {
      
        try {
            // ....
            if (registerByPlugin) {
                logger.info(TAG, "Load router map by arouter-auto-register plugin.");
            } else {
                Set<String> routerMap;

                for (String className : routerMap) {
                    if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_ROOT)) {
                        // This one of root elements, load root.
                        ((IRouteRoot) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.groupsIndex);
                    } else if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_INTERCEPTORS)) {
                        // Load interceptorMeta
                        // ==== 加载插件代码 === 
                        ((IInterceptorGroup) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.interceptorsIndex);
                    } else if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_PROVIDERS)) {
                        // Load providerIndex
                        ((IProviderGroup) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.providersIndex);
                    }
                }
            }
        } catch (Exception e) {
            throw new HandlerException(TAG + "ARouter init logistics center exception! [" + e.getMessage() + "]");
        }
    }
```

这只是加载了插件，同时在`ARouter.init()`方法中，有如下逻辑

```java
  public static void init(Application application) {
        if (!hasInit) {
            logger = _ARouter.logger;
            _ARouter.logger.info(Consts.TAG, "ARouter init start.");
            // 初始化操作
            hasInit = _ARouter.init(application);

            if (hasInit) {
                // 初始化拦截器
                _ARouter.afterInit();
            }

            _ARouter.logger.info(Consts.TAG, "ARouter init over.");
        }
    }


```

其中`_Arouter.init()`是对辅助类进行加载。然后调用了`_ARouter.afterInit()`进行加载插件。

```java
  static void afterInit() {
        // Trigger interceptor init, use byName.
        interceptorService = (InterceptorService) ARouter.getInstance().build("/arouter/service/interceptor").navigation();
    }
```

该方法获取`interceptorService`的实例，该实例其实也是一个路由地址。`ARouter`提供一种加载实例的方式，就是`Provider`，通过该机制，可以`@Router`注解的方式，获取对象实例。这个后面文章分析。

而对于`InterceptorService`，最终实例化的对象就是`InterceptorServiceImpl`类。

并且实例化对象的时候，会调用`InterceptorServiceImpl.init()`方法。

```java
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

```

此处仅仅是初始化一个`InterceptorServiceImpl`对象并保存。


### 跳转时期，进行拦截

路由在跳转是时期，判断是否是绿色通道不然拦截并调用如下代码：

```java
interceptorService.doInterceptions(postcard, new InterceptorCallback() {
                /**
                 * Continue process
                 *
                 * @param postcard route meta
                 */
                @Override
                public void onContinue(Postcard postcard) {
                    _navigation(context, postcard, requestCode, callback);
                }

                /**
                 * Interrupt process, pipeline will be destory when this method called.
                 *
                 * @param exception Reson of interrupt.
                 */
                @Override
                public void onInterrupt(Throwable exception) {
                    if (null != callback) {
                        callback.onInterrupt(postcard);
                    }

                    logger.info(Consts.TAG, "Navigation failed, termination by interceptor : " + exception.getMessage());
                }
            });

```

实质就是调用 `InterceptorServiceImpl.doInterceptions()`.

```java
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

```

判断是否有拦截器，如果没有，则直接回调路由跳转。否则进行拦截器拦截操作。主要流程如下：

检查拦截器加载是否完成。因为拦截器的加载和初始化是放在子线程进行，并且是在路由初始化时期。所以需要检查状态。如果拦截器初始化失败，则直接中断路由跳转。

在子线中开始准备拦截器遍历，在这里用到了`CancelableCountDownLatch`,该类继承`CountDownLatch`,主要用于线程间同步。

然后执行拦截器遍历`_excute()`。该方法主要是拦截器的遍历和执行。

```java
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
                }
            });
        }
    }

```

拦截器的遍历有一个关键点，执行下一个拦截器的前提是前一个拦截器调用了`InterceptorCallback.onContinue()`方法。如果没有回调，那么久不会执行下一个拦截器。


继续回到`doInterceptions()`方法。

```java
// 阻塞线程，等待拦截器执行或者超时
interceptorCounter.await(postcard.getTimeout(), TimeUnit.SECONDS);
```

由于上面的问题，这一行代码就很关键。万一拦截器没有回调并开始下一个拦截，那么该行代码将会阻塞，直到所有的拦截器被执行或者是超时。通过代码看，默认超时时间是300s。

再往下就是根据拦截器的结果进行不同逻辑的分发了。





