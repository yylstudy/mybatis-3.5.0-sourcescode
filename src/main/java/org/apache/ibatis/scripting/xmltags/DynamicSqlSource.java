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
package org.apache.ibatis.scripting.xmltags;

import java.util.Map;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */

/**
 * 动态sqlSource，里面存有动态的sqlNode，动态sqlNode也就是平常的动态标签
 * 以及TextSqlNode，TextSqlNode是含有${}符的sql文本
 */
public class DynamicSqlSource implements SqlSource {

  private final Configuration configuration;
  /**MixedSqlNode*/
  private final SqlNode rootSqlNode;

  public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
    this.configuration = configuration;
    this.rootSqlNode = rootSqlNode;
  }

  /**
   * @param parameterObject 参数名和参数值的映射关系，Map<String,Object>，
   *      *                  若参数只有一个且没有@Param注解，那么这个parameter就是第一个参数本身
   * 获取动态sql的BoundSql对象
   * @return 上下文Map，其中包含参数等
   */
  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    //创建动态上下文
    DynamicContext context = new DynamicContext(configuration, parameterObject);
    /**
     * 解析动态sql的表达式，动态的sql语句就是在这里生成sql字符串的并且在这里将sql语句append到
     * 注意这里apply，是采用装饰类模式，大部分都是判断表达式本身，但是最底层的staticTextSqlNode
     * 就是这个类的apply方法将sql字符串赋值给boundSql对象
     * 这个rootSqlNode是MixedSqlNode，其中包含对象其它动态node的集合，所以MixedSqlNode
     * 的apply方法是遍历其中的list，分别调用其中的apply
     */
    rootSqlNode.apply(context);
    //创建一个sqlSourceBuilder，用于解析sqlSource
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
    //解析sqlSource，此时这个SqlSource是个StaticSqlSource
    SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
    for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
      boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
    }
    return boundSql;
  }

}
