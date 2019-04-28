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

import java.util.List;

/**
 * @author Clinton Begin
 */

/**
 * 标签语句类，里面存有一个SqlNode数组，表示这个标签下各个节点（文本、子标签等等）
 */
public class MixedSqlNode implements SqlNode {
  /**存放sql的各个子标签（包含动态（包含${}）和静态）*/
  private final List<SqlNode> contents;

  public MixedSqlNode(List<SqlNode> contents) {
    this.contents = contents;
  }

  /**
   * 拼装sql
   * @param context
   * @return
   */
  @Override
  public boolean apply(DynamicContext context) {
    //循环其中的sqlNode
    for (SqlNode sqlNode : contents) {
      sqlNode.apply(context);
    }
    return true;
  }
}
