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

import java.util.regex.Pattern;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.scripting.ScriptingException;
import org.apache.ibatis.type.SimpleTypeRegistry;

/**
 * @author Clinton Begin
 * 动态的文本sql，其中包含${}
 */
public class TextSqlNode implements SqlNode {
  /**部分文本sql  select id,cnname,sex from ${tableName} where id=#{studentId}
   */
  private final String text;
  private final Pattern injectionFilter;

  public TextSqlNode(String text) {
    this(text, null);
  }
  
  public TextSqlNode(String text, Pattern injectionFilter) {
    this.text = text;
    this.injectionFilter = injectionFilter;
  }
  public static void main(String[] args){
    TextSqlNode tx = new TextSqlNode("hef${name.text}eefh");
    tx.isDynamic();
  }

  /**
   * 解析动态sql，${}这个的解析就是在这里完成的
   * @param context
   * @return
   */
  @Override
  public boolean apply(DynamicContext context) {
    GenericTokenParser parser = createParser(new BindingTokenParser(context, injectionFilter));

    context.appendSql(parser.parse(text));
    return true;
  }

  /**
   * TextSqlNode是否是动态的
   * @return
   */
  public boolean isDynamic() {
    //创建DynamicCheckerTokenParser
    DynamicCheckerTokenParser checker = new DynamicCheckerTokenParser();
    //生成${}符号解析器
    GenericTokenParser parser = createParser(checker);
    parser.parse(text);
    return checker.isDynamic();
  }

  private GenericTokenParser createParser(TokenHandler handler) {
    return new GenericTokenParser("${", "}", handler);
  }

  private static class BindingTokenParser implements TokenHandler {
    /**
     * 动态上下文
     */
    private DynamicContext context;
    private Pattern injectionFilter;

    public BindingTokenParser(DynamicContext context, Pattern injectionFilter) {
      this.context = context;
      this.injectionFilter = injectionFilter;
    }

    /**
     * ${}符号解析器
     * @param content
     * @return
     */
    @Override
    public String handleToken(String content) {
      //获取参数  参数名和参数值的映射关系，Map<String,Object>，@Param注解，那么这个parameter就是第一个参数本身
      Object parameter = context.getBindings().get("_parameter");
      if (parameter == null) {
        context.getBindings().put("value", null);
      } else if (SimpleTypeRegistry.isSimpleType(parameter.getClass())) {
        //是简单类型的数据，直接将值添加到 key为“value”的映射上
        context.getBindings().put("value", parameter);
      }
      //根据参数对象，获取${}表达式内的值
      Object value = OgnlCache.getValue(content, context.getBindings());
      String srtValue = (value == null ? "" : String.valueOf(value)); // issue #274 return "" instead of "null"
      checkInjection(srtValue);
      return srtValue;
    }

    private void checkInjection(String value) {
      if (injectionFilter != null && !injectionFilter.matcher(value).matches()) {
        throw new ScriptingException("Invalid input. Please conform to regex" + injectionFilter.pattern());
      }
    }
  }
  
  private static class DynamicCheckerTokenParser implements TokenHandler {
    /**判断当前sql是否是动态sql*/
    private boolean isDynamic;

    public DynamicCheckerTokenParser() {
      // Prevent Synthetic Access
    }

    public boolean isDynamic() {
      return isDynamic;
    }

    /**
     * 可以看到当前类若调用了handleToken方法，那么这个sql就是动态的sql
     * @param content
     * @return
     */
    @Override
    public String handleToken(String content) {
      this.isDynamic = true;
      return null;
    }
  }
  
}