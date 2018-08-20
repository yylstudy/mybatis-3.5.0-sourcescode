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
package org.apache.ibatis.builder.xml;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

  private final Configuration configuration;
  private final MapperBuilderAssistant builderAssistant;

  public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
    this.configuration = configuration;
    this.builderAssistant = builderAssistant;
  }

  /**
   * 解析include
   * @param source  注意这个source应该是<include></include>这种节点，而不是<select>等父节点
   */
  public void applyIncludes(Node source) {
    Properties variablesContext = new Properties();
    //获取configuration中的变量键值对
    Properties configurationVariables = configuration.getVariables();
    if (configurationVariables != null) {
      variablesContext.putAll(configurationVariables);
    }
    applyIncludes(source, variablesContext, false);
  }

  /**
   * Recursively apply includes through all SQL fragments.
   * @param source Include node in DOM tree
   * @param variablesContext Current context for static variables with values
   */
  /**
   * 将<include>标签替换成对应的sql语句
   * 这个过程差不多分为3步
   * 1）将include标签替换成对应的<sql>标签
   * 2）在sql标签之前添加对应的sql语句
   * 3）删除sql标签
   * @param source
   * @param variablesContext 全局变量键值对
   * @param included
   */
  private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
    //解析include，若节点名称为include
    if (source.getNodeName().equals("include")) {
      //根据refid查找包含的sql的Node节点，也就是<sql></sql>标签
      Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);
      /**
       * 这里对<include>标签下的<property>元素解析，若为空直接返回，
       * 不为空则解析成properties并添加configuration的变量键值对，然后返回
       */
      Properties toIncludeContext = getVariablesContext(source, variablesContext);
      //递归填充toInclude的值
      applyIncludes(toInclude, toIncludeContext, true);
      if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
        toInclude = source.getOwnerDocument().importNode(toInclude, true);
      }
      /**
       * 将include 替换成对应的包含sql的Node节点
       * source.getParentNode()就是select、insert、update、delete
       */
      source.getParentNode().replaceChild(toInclude, source);
        while (toInclude.hasChildNodes()) {
        /**
         * 将sql语句插入到<sql>节点之前，需要注意的是：toInclude.getFirstChild()其实就是sql字符串
         * 对于DOM来说，文本也是一个节点
         */
        toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
      }
      //删除之前插入的<sql>节点
      toInclude.getParentNode().removeChild(toInclude);
      /**
       * 这里第二次进来应该是<sql>节点，所有会走里面逻辑
       */
    } else if (source.getNodeType() == Node.ELEMENT_NODE) {
      //若<include>下有propertis标签，则设置sql的值
      if (included && !variablesContext.isEmpty()) {
        // replace variables in attribute values
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
          Node attr = attributes.item(i);
          attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
        }
      }
      NodeList children = source.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        applyIncludes(children.item(i), variablesContext, included);
      }
      //第三次进来应该是sql的文本节点
    } else if (included && source.getNodeType() == Node.TEXT_NODE
        && !variablesContext.isEmpty()) {
      // replace variables in text node
      source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
  }

  /**
   * 根据refid查找包含的sql的Node节点
   * @param refid
   * @param variables
   * @return
   */
  private Node findSqlFragment(String refid, Properties variables) {
    //获取refid真正映射的值，因为可能之前的refid是全局变量表达式 ${}类型
    refid = PropertyParser.parse(refid, variables);
    //daoName+refid
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
      //从全局sql中获取refid对应的包含sql的XNode对象
      XNode nodeToInclude = configuration.getSqlFragments().get(refid);
      return nodeToInclude.getNode().cloneNode(true);
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
  }

  private String getStringAttribute(Node node, String name) {
    return node.getAttributes().getNamedItem(name).getNodeValue();
  }

  /**
   * Read placeholders and their values from include node definition. 
   * @param node Include node instance
   * @param inheritedVariablesContext Current context used for replace variables in new variables values
   * @return variables context from include instance (no inherited values)
   */
  /**
   * 获取变量值
   * @param node 节点
   * @param inheritedVariablesContext 全局变量的Properties
   * @return
   */
  private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
    //当前标签下的property对应的properties对象
    Map<String, String> declaredProperties = null;
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      //若是子标签（因为Node的文本也是一个NodeType）
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        String name = getStringAttribute(n, "name");
        // Replace variables inside
        //从全局变量中获取值
        String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
        if (declaredProperties == null) {
          declaredProperties = new HashMap<>();
        }
        if (declaredProperties.put(name, value) != null) {
          throw new BuilderException("Variable " + name + " defined twice in the same include definition");
        }
      }
    }
    if (declaredProperties == null) {
      return inheritedVariablesContext;
    } else {
      //当前标签下的变量properties和全局的合并，并返回
      Properties newProperties = new Properties();
      newProperties.putAll(inheritedVariablesContext);
      newProperties.putAll(declaredProperties);
      return newProperties;
    }
  }
}
