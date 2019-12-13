/**
 *    Copyright ${license.git.copyrightYears} the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.mapping;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;
import org.apache.ibatis.builder.InitializingObject;
import org.apache.ibatis.cache.decorators.BlockingCache;
import org.apache.ibatis.cache.decorators.LoggingCache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.decorators.ScheduledCache;
import org.apache.ibatis.cache.decorators.SerializedCache;
import org.apache.ibatis.cache.decorators.SynchronizedCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

/**
 * @author Clinton Begin
 */
public class CacheBuilder {
  /**当前mapper的nameSpace，也就是dao的地址*/
  private final String id;
  /**缓存的实现类 默认PerpetualCache*/
  private Class<? extends Cache> implementation;
  /**缓存的装饰类集合，LruCache*/
  private final List<Class<? extends Cache>> decorators;
  /**缓存存储的大小*/
  private Integer size;
  /**缓存刷新时间*/
  private Long clearInterval;
  /**是否能读写 这里的读写是指是否可序列化*/
  private boolean readWrite;
  /**cache下子标签的键值对*/
  private Properties properties;
  /**是否阻塞*/
  private boolean blocking;

  public CacheBuilder(String id) {
    this.id = id;
    this.decorators = new ArrayList<>();
  }

  public CacheBuilder implementation(Class<? extends Cache> implementation) {
    this.implementation = implementation;
    return this;
  }

  public CacheBuilder addDecorator(Class<? extends Cache> decorator) {
    if (decorator != null) {
      this.decorators.add(decorator);
    }
    return this;
  }

  public CacheBuilder size(Integer size) {
    this.size = size;
    return this;
  }

  public CacheBuilder clearInterval(Long clearInterval) {
    this.clearInterval = clearInterval;
    return this;
  }

  public CacheBuilder readWrite(boolean readWrite) {
    this.readWrite = readWrite;
    return this;
  }

  public CacheBuilder blocking(boolean blocking) {
    this.blocking = blocking;
    return this;
  }
  
  public CacheBuilder properties(Properties properties) {
    this.properties = properties;
    return this;
  }

  /**
   * 创建真正的缓存对象
   * @return
   */
  public Cache build() {
    //设置缓存的实现类以及回收策略类
    setDefaultImplementations();
    //创建一个缓存的实例
    Cache cache = newBaseCacheInstance(implementation, id);
    //填充缓存属性
    setCacheProperties(cache);
    // issue #352, do not apply decorators to custom caches
    //如果缓存的实现类是PerpetualCache，也就是默认缓存实现类
    if (PerpetualCache.class.equals(cache.getClass())) {
      for (Class<? extends Cache> decorator : decorators) {
        //遍历装饰类，构造出装饰类实例，遍历完成后，cache就是最外层的装饰对象，最里层装饰对象就是正的缓存类
        cache = newCacheDecoratorInstance(decorator, cache);
        setCacheProperties(cache);
      }
      //设置通用的装饰器
      cache = setStandardDecorators(cache);
      //若自定义的缓存类不是LoggingCache的子类，那么创建一个LoggingCache装饰类，用于统计缓存的命中率
    } else if (!LoggingCache.class.isAssignableFrom(cache.getClass())) {
      cache = new LoggingCache(cache);
    }
    return cache;
  }

  /**
   * 设置缓存实现类，如果为空采用默认的PerpetualCache
   * 查看PerpetualCache源码可知，默认mybatis是采用hashMap做缓存的
   * 默认回收策略是LruCache，查看LruCache源码可知，其实是采用了装饰模式
   * 进行回收的，所以这个名字为decorators也是有意义的
   */
  private void setDefaultImplementations() {
    if (implementation == null) {
      implementation = PerpetualCache.class;
      if (decorators.isEmpty()) {
        decorators.add(LruCache.class);
      }
    }
  }

  /**
   * 设置装饰缓存类
   * @param cache 装饰缓存类
   * @return
   */
  private Cache setStandardDecorators(Cache cache) {
    try {
      MetaObject metaCache = SystemMetaObject.forObject(cache);
      //设置缓存的长度
      if (size != null && metaCache.hasSetter("size")) {
        metaCache.setValue("size", size);
      }
      //若设置缓存刷新时间，那么再包一层装饰类 ScheduleCache，默认是一个小时刷新一次
      if (clearInterval != null) {
        cache = new ScheduledCache(cache);
        ((ScheduledCache) cache).setClearInterval(clearInterval);
      }
      //若设置可序列化，则创建序列化缓存装饰类
      if (readWrite) {
        cache = new SerializedCache(cache);
      }
      //创建logging缓存装饰类，统计缓存的命中率
      cache = new LoggingCache(cache);
      //创建同步缓存装饰类，类方法都是同步的，以保证数据的线程安全性
      cache = new SynchronizedCache(cache);
      //若是阻塞，那么创建阻塞缓存装饰类
      if (blocking) {
        cache = new BlockingCache(cache);
      }
      return cache;
    } catch (Exception e) {
      throw new CacheException("Error building standard cache decorators.  Cause: " + e, e);
    }
  }

  /**
   * 校验缓存类属性（<cache> -> <property> 标签是否在缓存类中存在setter方法），并设置缓存类的属性
   * @param cache
   */
  private void setCacheProperties(Cache cache) {
    if (properties != null) {
      MetaObject metaCache = SystemMetaObject.forObject(cache);
      for (Map.Entry<Object, Object> entry : properties.entrySet()) {
        String name = (String) entry.getKey();
        String value = (String) entry.getValue();
        if (metaCache.hasSetter(name)) {
          Class<?> type = metaCache.getSetterType(name);
          if (String.class == type) {
            metaCache.setValue(name, value);
          } else if (int.class == type
              || Integer.class == type) {
            metaCache.setValue(name, Integer.valueOf(value));
          } else if (long.class == type
              || Long.class == type) {
            metaCache.setValue(name, Long.valueOf(value));
          } else if (short.class == type
              || Short.class == type) {
            metaCache.setValue(name, Short.valueOf(value));
          } else if (byte.class == type
              || Byte.class == type) {
            metaCache.setValue(name, Byte.valueOf(value));
          } else if (float.class == type
              || Float.class == type) {
            metaCache.setValue(name, Float.valueOf(value));
          } else if (boolean.class == type
              || Boolean.class == type) {
            metaCache.setValue(name, Boolean.valueOf(value));
          } else if (double.class == type
              || Double.class == type) {
            metaCache.setValue(name, Double.valueOf(value));
          } else {
            throw new CacheException("Unsupported property type for cache: '" + name + "' of type " + type);
          }
        }
      }
    }
    if (InitializingObject.class.isAssignableFrom(cache.getClass())){
      try {
        ((InitializingObject) cache).initialize();
      } catch (Exception e) {
        throw new CacheException("Failed cache initialization for '" +
            cache.getId() + "' on '" + cache.getClass().getName() + "'", e);
      }
    }
  }

  private Cache newBaseCacheInstance(Class<? extends Cache> cacheClass, String id) {
    //获取缓存实现类的单个String构造函数
    Constructor<? extends Cache> cacheConstructor = getBaseCacheConstructor(cacheClass);
    try {
      /**
       * 使用这个构造函数，创建一个实例这里的id是dao的类名，所以在自定义缓存中,必须要
       * 定义一个String的构造函数，例如：public RedisCache(final String id){}
       * id就是在这里赋值进去的，id就是namespace的值
       */
      return cacheConstructor.newInstance(id);
    } catch (Exception e) {
      throw new CacheException("Could not instantiate cache implementation (" + cacheClass + "). Cause: " + e, e);
    }
  }

  private Constructor<? extends Cache> getBaseCacheConstructor(Class<? extends Cache> cacheClass) {
    try {
      return cacheClass.getConstructor(String.class);
    } catch (Exception e) {
      throw new CacheException("Invalid base cache implementation (" + cacheClass + ").  " +
          "Base cache implementations must have a constructor that takes a String id as a parameter.  Cause: " + e, e);
    }
  }

  /**
   * 实例化装饰类对象，可以以LruCache为例子
   * @param cacheClass 装饰类Class
   * @param base 被装饰的缓存实例
   * @return
   */
  private Cache newCacheDecoratorInstance(Class<? extends Cache> cacheClass, Cache base) {
    Constructor<? extends Cache> cacheConstructor = getCacheDecoratorConstructor(cacheClass);
    try {
      //将被装饰的类传入，创建一个装饰缓存
      return cacheConstructor.newInstance(base);
    } catch (Exception e) {
      throw new CacheException("Could not instantiate cache decorator (" + cacheClass + "). Cause: " + e, e);
    }
  }

  /**
   * 获取缓存装饰类的构造器，可以看到装饰类的构造器必须包含一个Cache的有参构造
   * @param cacheClass
   * @return
   */
  private Constructor<? extends Cache> getCacheDecoratorConstructor(Class<? extends Cache> cacheClass) {
    try {
      return cacheClass.getConstructor(Cache.class);
    } catch (Exception e) {
      throw new CacheException("Invalid cache decorator (" + cacheClass + ").  " +
          "Cache decorators must have a constructor that takes a Cache instance as a parameter.  Cause: " + e, e);
    }
  }
}
