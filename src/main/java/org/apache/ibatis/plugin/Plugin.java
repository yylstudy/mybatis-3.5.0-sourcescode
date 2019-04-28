/**
 *    Copyright ${license.git.copyrightYears} the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *wrap
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * 这个就是JDK实现的正常的动态代理的写法
 * @author Clinton Begin
 */
public class Plugin implements InvocationHandler {
  /**被代理的目标对象*/
  private final Object target;
  /**拦截器*/
  private final Interceptor interceptor;
  /**需要代理的对象（SqlSession下的四大对象：Executor、StatementHandler、ParameterHandler、ResultHandler）
   和其需要拦截的方法集合的映射*/
  private final Map<Class<?>, Set<Method>> signatureMap;

  private Plugin(Object target, Interceptor interceptor, Map<Class<?>, Set<Method>> signatureMap) {
    this.target = target;
    this.interceptor = interceptor;
    this.signatureMap = signatureMap;
  }

  /**
   * 生成代理对象
   * @param target 要被代理的对象
   * @param interceptor 拦截器对象
   * @return
   */
  public static Object wrap(Object target, Interceptor interceptor) {
    //获取需要代理的对象（SqlSession下的四大对象：Executor、StatementHandler、ParameterHandler、ResultHandler）
    //和其需要拦截的方法集合的映射
    Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
    Class<?> type = target.getClass();
    //获取类符合的之前解析的签名Map的接口
    Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
    if (interfaces.length > 0) {
      //Jdk动态代理，生成target的代理对象
      return Proxy.newProxyInstance(
          type.getClassLoader(),
          interfaces,
          new Plugin(target, interceptor, signatureMap));
    }
    return target;
  }

  /**
   * 拦截器真正的调用方法，这个方法是在拦截器的方法上调用的，而不是在mapper的方法，因为此时mapper方法肯定已经调用了
   * @param proxy
   * @param method
   * @param args
   * @return
   * @throws Throwable
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      Set<Method> methods = signatureMap.get(method.getDeclaringClass());
      //就是在这里进行需要额外处理方法的判定，否则直接调用被代理对象的方法
      if (methods != null && methods.contains(method)) {
        //这里才是调用拦截器真正的intercept方法的实现
        return interceptor.intercept(new Invocation(target, method, args));
      }
      return method.invoke(target, args);
    } catch (Exception e) {
      throw ExceptionUtil.unwrapThrowable(e);
    }
  }

  /**
   * 获取需要代理的对象（SqlSession下的四大对象：Executor、StatementHandler、ParameterHandler、ResultHandler）
   * 和其需要拦截的方法集合的映射
   * @param interceptor
   * @return
   */
  private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
    //获取拦截器上的@Intercepts的注解
    Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);
    // issue #251
    if (interceptsAnnotation == null) {
      throw new PluginException("No @Intercepts annotation was found in interceptor " + interceptor.getClass().getName());      
    }
    //获取@Intercepts上的Signature的注解
    Signature[] sigs = interceptsAnnotation.value();
    Map<Class<?>, Set<Method>> signatureMap = new HashMap<>();
    for (Signature sig : sigs) {
      //添加  @Signature注解的type值 sqlsession的四大对象之一
      Set<Method> methods = signatureMap.computeIfAbsent(sig.type(), k -> new HashSet<>());
      try {
        //通过@Signature的type和args以及method构建出一个method对象
        Method method = sig.type().getMethod(sig.method(), sig.args());
        //加入到集合中
        methods.add(method);
      } catch (NoSuchMethodException e) {
        throw new PluginException("Could not find method on " + sig.type() + " named " + sig.method() + ". Cause: " + e, e);
      }
    }
    return signatureMap;
  }


  private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
    Set<Class<?>> interfaces = new HashSet<>();
    while (type != null) {
      for (Class<?> c : type.getInterfaces()) {
        if (signatureMap.containsKey(c)) {
          interfaces.add(c);
        }
      }
      type = type.getSuperclass();
    }
    return interfaces.toArray(new Class<?>[interfaces.size()]);
  }
}
