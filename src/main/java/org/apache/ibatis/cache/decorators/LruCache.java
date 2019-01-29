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
package org.apache.ibatis.cache.decorators;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;

/**
 * Lru (least recently used) cache decorator
 *
 * @author Clinton Begin
 */
public class LruCache implements Cache {
  /**缓存类*/
  private final Cache delegate;
  /**缓存键集合*/
  private Map<Object, Object> keyMap;
  /**存放将要删除的缓存的key值*/
  private Object eldestKey;

  public LruCache(Cache delegate) {
    this.delegate = delegate;
    setSize(1024);
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  public void setSize(final int size) {
    //创建一个有序的hashMap，匿名内部类
    keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
      private static final long serialVersionUID = 4267176411845948333L;

      /**
       * 通过查看LinkedHashMap可知，这个方法是在afterNodeInsertion里调用的，而afterNodeInsertion
       * 是在put中调用的，如果LinkedHashMap长度大于size，linkedHashMap在添加的时候就会删除之前的节点
       * @param eldest
       * @return
       */
      @Override
      protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
        boolean tooBig = size() > size;
        /**若是当前LinkedHashMap中的键值对大于缓存允许的最大长度，那么就将第一个键值对删除*/
        if (tooBig) {
          /**将要删除的key赋值*/
          eldestKey = eldest.getKey();
        }
        return tooBig;
      }
    };
  }

  /**
   * 添加sql和查询结果进缓存
   * @param key Can be any object but usually it is a {@link CacheKey}
   * @param value The result of a select.
   */
  @Override
  public void putObject(Object key, Object value) {
    //真正缓存对象添加
    delegate.putObject(key, value);
    //回收策略
    cycleKeyList(key);
  }

  @Override
  public Object getObject(Object key) {
    keyMap.get(key); //touch
    return delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    delegate.clear();
    keyMap.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  private void cycleKeyList(Object key) {
    keyMap.put(key, key);
    //如果要删除的key不为空
    if (eldestKey != null) {
      //真正缓存中删除这个key
      delegate.removeObject(eldestKey);
      /**置空要删除的key*/
      eldestKey = null;
    }
  }

}
