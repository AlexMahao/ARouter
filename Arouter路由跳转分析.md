## 路由跳转分析

> `Arouter`源码分析系列文章，请访问[https://github.com/AlexMahao/ARouter](https://github.com/AlexMahao/ARouter)

### 逻辑分析

`Arouter`的路由跳转整体可分为三个步骤：

- 编译时期利用`Processor`生成路由清单文件。
- 运行时期加载路由清单文件。
- 跳转时期根据标识符查询路由清单，完成路由地址跳转。

### 编译时期 `arouter-compiler`

`RouteProcessor`是处理路由清单生成的类。其初始化方法如下：

```java
@Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        // 判断是否需要生成文档，以json的形式生成路由文档
        if (generateDoc) {
            try {
                // ARouter/app/build/generated/source/apt/debug/com/alibaba/android/arouter/docs
                docWriter = mFiler.createResource(
                        StandardLocation.SOURCE_OUTPUT,
                        PACKAGE_OF_GENERATE_DOCS,
                        "arouter-map-of-" + moduleName + ".json"
                ).openWriter();
            } catch (IOException e) {
                logger.error("Create doc writer failed, because " + e.getMessage());
            }
        }

        iProvider = elementUtils.getTypeElement(Consts.IPROVIDER).asType();

        logger.info(">>> RouteProcessor init. <<<");
    }

```

主要是判断是否生成辅助查看的文档。

然后在`parseRoutes()`中获取`@Route`注解，解析并生成辅助类。该方法的流程不在深入研究。看一下生成的辅助类结构。

```
ARouter$$Group$$test.java
ARouter$$Group$$yourservicegroupname.java                               
ARouter$$Root$$app.java
ARouter$$Providers$$app.java
```

总共生成了以上几个文件，可以分为三类：

- `Root`根节点类: 包含多个分组。
- `Group`: 包含该组下面的详细的路由地址。
- `Providers` : 暂时忽略。

在`Arouter`中有分组的概念。官方文档解释如下：

> SDK中针对所有的路径(/test/1 /test/2)进行分组，分组只有在分组中的某一个路径第一次被访问的时候，该分组才会被初始化。可以通过@Route 注解主动指定分组，否则使用路径中第一段字符串(/*/)作为分组。注意：一旦主动指定分组之后，应用内路由需要使用 ARouter.getInstance().build(path, group) 进行跳转，手动指定分组，否则无法找到` @Route(path = "/test/1", group = "app")`
    
根据描述，可以看出`ARouter$$Root$$app.java`就是对分组的统计。该类内容如下：

```java
public class ARouter$$Root$$app implements IRouteRoot {
  @Override
  public void loadInto(Map<String, Class<? extends IRouteGroup>> routes) {
    routes.put("test", ARouter$$Group$$test.class);
    routes.put("yourservicegroupname", ARouter$$Group$$yourservicegroupname.class);
  }
}
```
每一个分组的标识又指向了内部路由清单明细的辅助类。

`ARouter$$Group$$test.java`如下：

```java
public class ARouter$$Group$$test implements IRouteGroup {
  @Override
  public void loadInto(Map<String, RouteMeta> atlas) {
    atlas.put("/test/activity1", RouteMeta.build(RouteType.ACTIVITY, Test1Activity.class, "/test/activity1", "test", new java.util.HashMap<String, Integer>(){{put("ser", 9); put("ch", 5); put("fl", 6); put("dou", 7); put("boy", 0); put("url", 8); put("pac", 10); put("obj", 11); put("name", 8); put("objList", 11); put("map", 11); put("age", 3); put("height", 3); }}, -1, -2147483648));
    atlas.put("/test/activity2", RouteMeta.build(RouteType.ACTIVITY, Test2Activity.class, "/test/activity2", "test", new java.util.HashMap<String, Integer>(){{put("key1", 8); }}, -1, -2147483648));
    atlas.put("/test/activity3", RouteMeta.build(RouteType.ACTIVITY, Test3Activity.class, "/test/activity3", "test", new java.util.HashMap<String, Integer>(){{put("name", 8); put("boy", 0); put("age", 3); }}, -1, -2147483648));
    atlas.put("/test/activity4", RouteMeta.build(RouteType.ACTIVITY, Test4Activity.class, "/test/activity4", "test", null, -1, -2147483648));
    atlas.put("/test/fragment", RouteMeta.build(RouteType.FRAGMENT, BlankFragment.class, "/test/fragment", "test", null, -1, -2147483648));
    atlas.put("/test/webview", RouteMeta.build(RouteType.ACTIVITY, TestWebview.class, "/test/webview", "test", null, -1, -2147483648));
  }
}
```

从省城的路由清单里可以看到，最终的路由清单里面，包含了路径已经对应的跳转信息。

跳转信息的类为`RouteMeta`, 该类结构如下：

```java
public class RouteMeta {
    private RouteType type;         // Type of route 
    private Element rawType;        // Raw type of route
    private Class<?> destination;   // Destination 跳转的目标类
    private String path;            // Path of route
    private String group;           // Group of route
    private int priority = -1;      // The smaller the number, the higher the priority
    private int extra;              // Extra data
    private Map<String, Integer> paramsType;  // Param type
    private String name;
}
```

由此看出，上面的路由清单中声明了当前路由是`activity`，跳转的`class`等。


以上就是对编译时期的分析。


### 运行时期 `arouter-api`

应用运行时需要调用`Arouter.init()`初始化，该初始化主要用于加载路由清单。

```java

    /**
     * Init, it must be call before used router.
     */
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

该逻辑中主要调用了`_Arouter.init()`和`_Arouter.afterInit()`,`afterInit()`方法主要用于加载拦截器，不在此次分析的范畴。

```java
 protected static synchronized boolean init(Application application) {
        mContext = application;
        // Logistics 后勤中心
        LogisticsCenter.init(mContext, executor);
        logger.info(Consts.TAG, "ARouter init success!");
        hasInit = true;
        mHandler = new Handler(Looper.getMainLooper());
        return true;
    }

```

继续深入`LogisticsCenter.init(mContext, executor);`

```java
 public synchronized static void init(Context context, ThreadPoolExecutor tpe) throws HandlerException {
        mContext = context;
        executor = tpe;

        try {
            long startInit = System.currentTimeMillis();
            //billy.qi modified at 2017-12-06
            //load by plugin first
            loadRouterMap();
            if (registerByPlugin) {
                logger.info(TAG, "Load router map by arouter-auto-register plugin.");
            } else {
               // 获取路由辅助类路径
                Set<String> routerMap;

                // It will rebuild router map every times when debuggable.
                // 如果是debug版本或者是新版本，就会重新加载路由表
                if (ARouter.debuggable() || PackageUtils.isNewVersion(context)) {
                    logger.info(TAG, "Run with debug mode or new install, rebuild router map.");
                    // These class was generated by arouter-compiler.
                    // 获取com.alibaba.android.arouter.routes的类列表，及Processor生成的辅助类
                    routerMap = ClassUtils.getFileNameByPackageName(mContext, ROUTE_ROOT_PAKCAGE);
                    if (!routerMap.isEmpty()) {
                        // 保存路由表缓存
                        context.getSharedPreferences(AROUTER_SP_CACHE_KEY, Context.MODE_PRIVATE).edit().putStringSet(AROUTER_SP_KEY_MAP, routerMap).apply();
                    }
                    // 修改新的路由表版本
                    PackageUtils.updateVersion(context);    // Save new version name when router map update finishes.
                } else {
                    logger.info(TAG, "Load router map from cache.");
                    routerMap = new HashSet<>(context.getSharedPreferences(AROUTER_SP_CACHE_KEY, Context.MODE_PRIVATE).getStringSet(AROUTER_SP_KEY_MAP, new HashSet<String>()));
                }

                logger.info(TAG, "Find router map finished, map size = " + routerMap.size() + ", cost " + (System.currentTimeMillis() - startInit) + " ms.");
                startInit = System.currentTimeMillis();

                // 把上面加载得到的路由映射根据ClassName分为三种，分别进行注册
                // IRouteRoot 路由的分组辅助类
                // IInterceptorGroup 拦截器
                // IProviderGroup 服务组件
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
    }
         
```

该方法可以分为两个部分。第一个部分是获取编译时期获取的类的全路径列表。第二个部分是分别调用辅助类的`loadInto()`将路由表信息保存到`Warehouse`中。

该方法内部流程如下：

- `loadRouterMap()`中将`registerByPlugin`置为`false`，所以逻辑直接进入到`else`中。
- 判断是否是新版本或者是`debug`，如果是则重新获取辅助类的全路径列表，否则就从缓存里面取。
- 调用辅助类，将路由信息存储到`Warehouse`中。

### 跳转时期 `arouter-api`

```java
ARouter.getInstance()
                        .build("/test/activity2")
                        .navigation();
```
跳转的使用方式如上，看一下`build`方法，该方法最终调用`_Arouter.build()`方法

```java
   protected Postcard build(String path, String group) {
        if (TextUtils.isEmpty(path) || TextUtils.isEmpty(group)) {
            throw new HandlerException(Consts.TAG + "Parameter is invalid!");
        } else {
            PathReplaceService pService = ARouter.getInstance().navigation(PathReplaceService.class);
            if (null != pService) {
                path = pService.forString(path);
            }
            return new Postcard(path, group);
        }
    }
```

`group`如果没有传入，就是默认组名。

`PathReplaceService`是`Arouter`提供的一个路径替换的服务，默认是`null`。

最终`build()`返回了`Postcard`对象。该对象继承`RouteMeta`。结构如下。

```java
public final class Postcard extends RouteMeta {
    // Base
    private Uri uri;
    private Object tag;             // A tag prepare for some thing wrong.
    private Bundle mBundle;         // Data to transform
    private int flags = -1;         // Flags of route
    private int timeout = 300;      // Navigation timeout, TimeUnit.Second
    private IProvider provider;     // It will be set value, if this postcard was provider.
    private boolean greenChannel;
    private SerializationService serializationService;

    // Animation
    private Bundle optionsCompat;    // The transition animation of activity
    private int enterAnim = -1;
    private int exitAnim = -1;
}
```

可见该类包含了一些跳转所需要的参数。但是当前只是包含了`path`等地址标识。然后调用`navigation()`。

该方法最终调用到`_Arouter_navigation()`方法


```java

    protected Object navigation(final Context context, final Postcard postcard, final int requestCode, final NavigationCallback callback) {
        try {
            // 根据地址信息匹配出跳转信息
            LogisticsCenter.completion(postcard);
        } catch (NoRouteFoundException ex) {
            // 降级处理等 ...
        }

        if (null != callback) {
            callback.onFound(postcard);
        }
        // 如果不是绿色通多，拦截器做拦截
        if (!postcard.isGreenChannel()) {   // It must be run in async thread, maybe interceptor cost too mush time made ANR.
            // 拦截器处理等 ...
            
        } else {
            return _navigation(context, postcard, requestCode, callback);
        }

        return null;
    }

```

该方法首先调用`LogisticsCenter.completion(postcard);`从`Warehouse`中查询跳转所需要的关键信息。比如目标`class`等。

然后判断是否是绿色通道，最终都调用了`_navigation()`


```java
 private Object _navigation(final Context context, final Postcard postcard, final int requestCode, final NavigationCallback callback) {
        final Context currentContext = null == context ? mContext : context;

        switch (postcard.getType()) {
            case ACTIVITY:
                // 执行activity跳转
                // Build intent
                final Intent intent = new Intent(currentContext, postcard.getDestination());
                intent.putExtras(postcard.getExtras());

                // Set flags.
                int flags = postcard.getFlags();
                if (-1 != flags) {
                    intent.setFlags(flags);
                } else if (!(currentContext instanceof Activity)) {    // Non activity, need less one flag.
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }

                // Set Actions
                String action = postcard.getAction();
                if (!TextUtils.isEmpty(action)) {
                    intent.setAction(action);
                }

                // Navigation in main looper.
                runInMainThread(new Runnable() {
                    @Override
                    public void run() {
                        startActivity(requestCode, currentContext, intent, postcard, callback);
                    }
                });

                break;
            case PROVIDER:
                return postcard.getProvider();
            case BOARDCAST:
            case CONTENT_PROVIDER:
            case FRAGMENT:
                Class fragmentMeta = postcard.getDestination();
                try {
                    Object instance = fragmentMeta.getConstructor().newInstance();
                    if (instance instanceof Fragment) {
                        ((Fragment) instance).setArguments(postcard.getExtras());
                    } else if (instance instanceof android.support.v4.app.Fragment) {
                        ((android.support.v4.app.Fragment) instance).setArguments(postcard.getExtras());
                    }

                    return instance;
                } catch (Exception ex) {
                    logger.error(Consts.TAG, "Fetch fragment instance error, " + TextUtils.formatStackTrace(ex.getStackTrace()));
                }
            case METHOD:
            case SERVICE:
            default:
                return null;
        }

        return null;
    }

```


