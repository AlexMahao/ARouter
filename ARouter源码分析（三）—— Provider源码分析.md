## ARouter源码分析（三）—— Provider源码分析

> `Arouter`源码分析系列建议从最初开始阅读，全部文章请访问[https://github.com/AlexMahao/ARouter](https://github.com/AlexMahao/ARouter)

在之前分析拦截器时，发现拦截器的基础服务`InterceptorService`是通过`Providers`的方式构造实例对象的。于是以下进行`Providers`的分析。

首先看一下`InterceptorService`的相关声明类。

```java
public interface InterceptorService extends IProvider {

    /**
     * Do interceptions
     */
    void doInterceptions(Postcard postcard, InterceptorCallback callback);
}
```

定义`InterceptorService`继承`IProvider`类。

```java
public interface IProvider {

    /**
     * Do your init work in this method, it well be call when processor has been load.
     *
     * @param context ctx
     */
    void init(Context context);
}
```

`InterceptorService`的最终实例化对象的声明如下：

```java
@Route(path = "/arouter/service/interceptor")
public class InterceptorServiceImpl implements InterceptorService { }
```


而最终的使用如下：

```java
interceptorService = (InterceptorService) ARouter.getInstance().build("/arouter/service/interceptor").navigation();
```

那么这些是如何串联起来的呢。


### 逻辑分析


- 生成路由辅助类
- 加载辅助类
- 构造`InterceptorService`辅助类


### 辅助类生成

因为其用的也是`@Router`注解，所以其的处理所以也在`RouterProcessor`中。


在该类中有如下代码：

```java
 if (types.isSubtype(tm, iProvider)) {         // IProvider
                    logger.info(">>> Found provider route: " + tm.toString() + " <<<");
                    routeMeta = new RouteMeta(route, element, RouteType.PROVIDER, null);
                }

```

即某个如果是`Provider`的子类，那么其就是`RouteType.PROVIDER`。

`Provider`使用提供两种方式：

- 按照`path`的方式构造`Provider`，就是`InterceptorService`的方式。
- 按照接口的类名，查询子类实现。

所以会生成两个辅助类，其中以`path`为`key`。

该类型生成的辅助类如下：


```java
public class ARouter$$Group$$arouter implements IRouteGroup {
  @Override
  public void loadInto(Map<String, RouteMeta> atlas) {
    atlas.put("/arouter/service/autowired", RouteMeta.build(RouteType.PROVIDER, AutowiredServiceImpl.class, "/arouter/service/autowired", "arouter", null, -1, -2147483648));
    atlas.put("/arouter/service/interceptor", RouteMeta.build(RouteType.PROVIDER, InterceptorServiceImpl.class, "/arouter/service/interceptor", "arouter", null, -1, -2147483648));
  }
}
```

其中一个以父类的全路径类名为`key`:

```java
public class ARouter$$Providers$$arouterapi implements IProviderGroup {
  @Override
  public void loadInto(Map<String, RouteMeta> providers) {
    providers.put("com.alibaba.android.arouter.facade.service.AutowiredService", RouteMeta.build(RouteType.PROVIDER, AutowiredServiceImpl.class, "/arouter/service/autowired", "arouter", null, -1, -2147483648));
    providers.put("com.alibaba.android.arouter.facade.service.InterceptorService", RouteMeta.build(RouteType.PROVIDER, InterceptorServiceImpl.class, "/arouter/service/interceptor", "arouter", null, -1, -2147483648));
  }
}
```

### 加载辅助类

辅助类的加载同样是在`LogisticsCenter`的`init`中。

因为提供两种方式，所以`Provider`会以两种不同的方式存在于多个`map`中。


```java
  public synchronized static void init(Context context, ThreadPoolExecutor tpe) throws HandlerException {
        // ... 
        for (String className : routerMap) {
            if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_ROOT)) {
                // This one of root elements, load root.
                ((IRouteRoot) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.groupsIndex);
            } else if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_INTERCEPTORS)) {
                // Load interceptorMeta
                ((IInterceptorGroup) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.interceptorsIndex);
            } else if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_PROVIDERS)) {
                // Load providerIndex
                ((IProviderGroup) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.providersIndex);
            }
        }
    }

```

`IRouteRoot`中会以`path`为`key`的方式保存一份。`IProviderGroup`会以父类的类路径为`key`保存一份。


### 构造实例对象

构造实例对象和路由跳转使用方式相同。

```
interceptorService = (InterceptorService) ARouter.getInstance().build("/arouter/service/interceptor").navigation();
```

逻辑上和路由跳转的逻辑基本相同。

区别点有如下

`navigation()`中的`LogisticsCenter.completion(postcard)`方法。

该方法会判断目标的类型：

```java
 switch (routeMeta.getType()) {
                case PROVIDER:  // if the route is provider, should find its instance
                    // Its provider, so it must implement IProvider
                    Class<? extends IProvider> providerMeta = (Class<? extends IProvider>) routeMeta.getDestination();
                    IProvider instance = Warehouse.providers.get(providerMeta);
                    if (null == instance) { // There's no instance of this provider
                        IProvider provider;
                        try {
                            // 构造provider对象
                            provider = providerMeta.getConstructor().newInstance();
                            // 调用实例化对象的init
                            provider.init(mContext);
                            // 保存到warehose中
                            Warehouse.providers.put(providerMeta, provider);
                            instance = provider;
                        } catch (Exception e) {
                            throw new HandlerException("Init provider failed! " + e.getMessage());
                        }
                    }
                    // 保存实例对象
                    postcard.setProvider(instance);
                    // 声明为绿色通道，不走拦截器逻辑
                    postcard.greenChannel();    // Provider should skip all of interceptors
                    break;
                case FRAGMENT:
                    postcard.greenChannel();    // Fragment needn't interceptors
                default:
                    break;
            }

```

在该逻辑中，判断如果是`provider`类型，不仅会保存信息，同时会实例化`provider`对象并调用`init`方法。

而最终`navigation()`的返回逻辑为

```java
return postcard.getProvider();
```