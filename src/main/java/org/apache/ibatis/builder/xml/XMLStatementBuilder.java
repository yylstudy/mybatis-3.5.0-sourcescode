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

import java.util.List;
import java.util.Locale;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {
  /**Mapper构建器助手*/
  private final MapperBuilderAssistant builderAssistant;
  /**select、insert、update、delete标签*/
  private final XNode context;
  private final String requiredDatabaseId;

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
    this(configuration, builderAssistant, context, null);
  }

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
    super(configuration);
    this.builderAssistant = builderAssistant;
    this.context = context;
    this.requiredDatabaseId = databaseId;
  }

  /**
   * 解析select、insert、update、delete
   */
  public void parseStatementNode() {
    //方法名
    String id = context.getStringAttribute("id");
    String databaseId = context.getStringAttribute("databaseId");
    //匹配MappedStaement的databaseId是否一致
    if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
      return;
    }
    //查询的长度
    Integer fetchSize = context.getIntAttribute("fetchSize");
    //超时时间
    Integer timeout = context.getIntAttribute("timeout");
    String parameterMap = context.getStringAttribute("parameterMap");
    //参数类型
    String parameterType = context.getStringAttribute("parameterType");
    Class<?> parameterTypeClass = resolveClass(parameterType);
    //返回的resultMap
    String resultMap = context.getStringAttribute("resultMap");
    //返回结果类型
    String resultType = context.getStringAttribute("resultType");
    String lang = context.getStringAttribute("lang");
    //语言驱动类，默认是XMLLanguageDriver
    LanguageDriver langDriver = getLanguageDriver(lang);

    Class<?> resultTypeClass = resolveClass(resultType);
    String resultSetType = context.getStringAttribute("resultSetType");
    StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);

    String nodeName = context.getNode().getNodeName();
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
    //是否是select语句
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
    //若不设置flushCache，默认非select语句会刷新缓存，这个缓存应该是一级缓存
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
    //若不设置，默认select语句会使用缓存
    boolean useCache = context.getBooleanAttribute("useCache", isSelect);
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);
    //创建XMLIncludeTransformer
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    //解析include元素并移除
    includeParser.applyIncludes(context.getNode());

    //解析selectKey元素并移除
    processSelectKeyNodes(id, parameterTypeClass, langDriver);
    //根据参数类型将sql创建成SqlSource
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
    String resultSets = context.getStringAttribute("resultSets");
    String keyProperty = context.getStringAttribute("keyProperty");
    String keyColumn = context.getStringAttribute("keyColumn");
    KeyGenerator keyGenerator;
    //主键生成策略
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    //加上daoName
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
    //判断之前的selectKey是否已经存在当前要执行的sql
    if (configuration.hasKeyGenerator(keyStatementId)) {
      keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {
      keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
          ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
    }

    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered, 
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
  }

  /**
   * 处理selectKey（selectKey经常作为insert主键返回） 并移除
   * @param id  dao的方法名
   * @param parameterTypeClass  参数类型Class
   * @param langDriver 语言驱动器
   */
  private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
    List<XNode> selectKeyNodes = context.evalNodes("selectKey");
    if (configuration.getDatabaseId() != null) {
      parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
    }
    //解析selectKey
    parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
    //删除selectKey
    removeSelectKeyNodes(selectKeyNodes);
  }
  /**
   * 处理selectKey（selectKey经常作为insert主键返回）
   * @param parentId  dao的方法名
   * @param list  selectKey的集合节点
   * @param parameterTypeClass  参数类型Class
   * @param langDriver 语言驱动器
   */
  private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
    for (XNode nodeToHandle : list) {
      String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
      String databaseId = nodeToHandle.getStringAttribute("databaseId");
      if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
        parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
      }
    }
  }

  /**
   * 删除 selectKey 文本
   * @param selectKeyNodes
   */
  private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
    for (XNode nodeToHandle : selectKeyNodes) {
      nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
    }
  }

  /**
   *处理selectKey（selectKey经常作为insert主键返回）
   * @param id  dao的方法名+"!selectKey"
   * @param nodeToHandle selectKey节点
   * @param parameterTypeClass insert方法的参数类型  selectKey主要会回填一些参数，所以需要参数类型
   * @param langDriver
   * @param databaseId
   */
  private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
    //selectKey的返回类型
    String resultType = nodeToHandle.getStringAttribute("resultType");
    //selectKey的返回类型
    Class<?> resultTypeClass = resolveClass(resultType);
    //statementType
    StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    //查询的值作为parameterTypeClass的属性key
    String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
    String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
    //是否在sql语句执行之前执行
    boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

    //defaults
    boolean useCache = false;
    boolean resultOrdered = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;
    //解析selectKey，创建对应的sqlSource
    SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;
    //创建一个MappedStatement
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
            fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
            resultSetTypeEnum, flushCache, useCache, resultOrdered,
            keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);
    //加上namespace
    id = builderAssistant.applyCurrentNamespace(id, false);

    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
  }

  /**
   * 匹配MappedStaement的databaseId是否一致
   * @param id
   * @param databaseId
   * @param requiredDatabaseId
   * @return
   */
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      if (!requiredDatabaseId.equals(databaseId)) {
        return false;
      }
    } else {
      if (databaseId != null) {
        return false;
      }
      // skip this statement if there is a previous one with a not null databaseId
      id = builderAssistant.applyCurrentNamespace(id, false);
      if (this.configuration.hasStatement(id, false)) {
        MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
        if (previous.getDatabaseId() != null) {
          return false;
        }
      }
    }
    return true;
  }

  private LanguageDriver getLanguageDriver(String lang) {
    Class<? extends LanguageDriver> langClass = null;
    if (lang != null) {
      langClass = resolveClass(lang);
    }
    return builderAssistant.getLanguageDriver(langClass);
  }

}
