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

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 */
public class XMLMapperBuilder extends BaseBuilder {
  /**mapper资源文件的解析器*/
  private final XPathParser parser;
  /**MapperBuilderAssistant*/
  private final MapperBuilderAssistant builderAssistant;
  /**存放所有的<sql>标签的键值对，daoName+"."+id   --->   sqlNode*/
  private final Map<String, XNode> sqlFragments;
  /**mapper资源文件的字符串路径*/
  private final String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  /**
   * 构建XMLMapperBuilder
   * @param inputStream mapper文件的输入流
   * @param configuration 配置类
   * @param resource mapper的字符串路径
   * @param sqlFragments <sql></sql>标签对象
   */
  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    //方法参数和构建XMLConfigBuilder是一样的
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  /**
   * 构建XMLMapperBuilder对象
   * @param parser mapper文件的解析器
   * @param configuration 配置类
   * @param resource mapper 的字符串路径
   * @param sqlFragments
   */
  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  /**
   * 解析mapper资源文件
   */
  public void parse() {
    //判断是否已经解析过了
    if (!configuration.isResourceLoaded(resource)) {
      //解析mapper元素
      configurationElement(parser.evalNode("/mapper"));
      configuration.addLoadedResource(resource);
      //根据namespace构建Mapper
      bindMapperForNamespace();
    }
    //解析之前因异常解析未完成的ResultMap
    parsePendingResultMaps();
    //解析之前因异常解析未完成的cacheRef
    parsePendingCacheRefs();
    //解析之前因异常解析未完成的Statements
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  /**
   *
   * @param context mapper节点
   */
  private void configurationElement(XNode context) {
    try {
      String namespace = context.getStringAttribute("namespace");
      if (namespace == null || namespace.equals("")) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      //设置nameSpace
      builderAssistant.setCurrentNamespace(namespace);
      //解析cache-ref标签
      cacheRefElement(context.evalNode("cache-ref"));
      //解析cache标签
      cacheElement(context.evalNode("cache"));
      //解析parameterMap元素
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      //解析resultMap元素
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      //解析sql元素
      sqlElement(context.evalNodes("/mapper/sql"));
      //解析 select、insert、update、delete
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
    }
  }

  /**
   * 解析 select、insert、update、delete
   * @param list
   */
  private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      //先去匹配和configuration中databaseId一致的mappedStatement
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    //再去匹配databaseId为空的mappedStatement
    buildStatementFromContext(list, null);
  }

  /**
   * 解析 select、insert、update、delete
   * @param list
   * @param requiredDatabaseId
   */
  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      //构建XMLStatementBuilder
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        //解析语句成MappedStatement,MappedStatement就是语句在mybatis中的表现形式，并加入到缓存中
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  private void parsePendingCacheRefs() {
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolveCacheRef();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
        }
      }
    }
  }

  private void parsePendingStatements() {
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) {
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().parseStatementNode();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
        }
      }
    }
  }

  /**
   * 解析 cache-ref元素
   * @param context cache-ref节点
   */
  private void cacheRefElement(XNode context) {
    if (context != null) {
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
        //解析CacheRef
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  private void cacheElement(XNode context) throws Exception {
    if (context != null) {
      //获取type属性,如果type属性为空，就取值为PERPETUAL（PERPETUAL这个可以在别名注册器中获取PerpetualCache.class）
      String type = context.getStringAttribute("type", "PERPETUAL");
      //获取缓存的Class
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      //获取缓存回收策略，LRU在别名注册器类对应的是LruCache
      String eviction = context.getStringAttribute("eviction", "LRU");
      //获取缓存回收策略的Class
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
      //刷新间隔时间
      Long flushInterval = context.getLongAttribute("flushInterval");
      //缓存长度
      Integer size = context.getIntAttribute("size");
      //是否只读
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      //是否阻塞
      boolean blocking = context.getBooleanAttribute("blocking", false);
      Properties props = context.getChildrenAsProperties();
      //使用mybatis的二级缓存
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

  /**
   * 解析parameterMap元素
   * @param list parameterMap节点集合
   * @throws Exception
   */
  private void parameterMapElement(List<XNode> list) throws Exception {
    for (XNode parameterMapNode : list) {
      //获取该parameterMap的别名
      String id = parameterMapNode.getStringAttribute("id");
      //获取该parameterMap的类名
      String type = parameterMapNode.getStringAttribute("type");
      //获取参数类名
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<>();
      for (XNode parameterNode : parameterNodes) {
        //parameterMap下的parameter节点
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  /**
   * 解析resultMap元素
   * @param list resultMap集合节点
   * @throws Exception
   */
  private void resultMapElements(List<XNode> list) throws Exception {
    for (XNode resultMapNode : list) {
      try {
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
    return resultMapElement(resultMapNode, Collections.<ResultMapping> emptyList());
  }

  /**
   * 解析resultMap节点
   * @param resultMapNode resultMap节点或者<association><collection>节点
   * @param additionalResultMappings 集合
   * @return
   * @throws Exception
   */
  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings) throws Exception {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
    //获取id值，这里的id值可能是resultMap的id属性，如果是<association><collection>标签
    //那么是没有id属性的就需要通过resultMapNode.getValueBasedIdentifier()方法获取
    String id = resultMapNode.getStringAttribute("id",
        resultMapNode.getValueBasedIdentifier());
    //获取type值，如果是<collection>标签，那么就是获取ofType，如果是<association>
    // 标签，那么就是javaType,<resultMap>就是type
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));
    //获取继承的resultMap
    String extend = resultMapNode.getStringAttribute("extends");
    //是否自动映射
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
    //获取type对应的Class
    Class<?> typeClass = resolveClass(type);
    Discriminator discriminator = null;
    //resultMap下的子元素集合，每一个result或者id属性都是一个ResultMapping对象
    List<ResultMapping> resultMappings = new ArrayList<>();
    resultMappings.addAll(additionalResultMappings);
    List<XNode> resultChildren = resultMapNode.getChildren();
    //解析resultMap下的子节点
    for (XNode resultChild : resultChildren) {
      if ("constructor".equals(resultChild.getName())) {
        //解析constructor节点,constructor节点是表示这个resultMap的javaBean的构造参数
        processConstructorElement(resultChild, typeClass, resultMappings);
      } else if ("discriminator".equals(resultChild.getName())) {
        //解析discriminator
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {
        //id或者result标签
        List<ResultFlag> flags = new ArrayList<>();
        if ("id".equals(resultChild.getName())) {
          flags.add(ResultFlag.ID);
        }
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    //构建resultMap解析器
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      return resultMapResolver.resolve();
    } catch (IncompleteElementException  e) {
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  /**
   * 解析constructor参数
   * @param resultChild constructor 节点
   * @param resultType resultMap的返回类型
   * @param resultMappings
   * @throws Exception
   */
  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    List<XNode> argChildren = resultChild.getChildren();
    for (XNode argChild : argChildren) {
      List<ResultFlag> flags = new ArrayList<>();
      flags.add(ResultFlag.CONSTRUCTOR);
      if ("idArg".equals(argChild.getName())) {
        flags.add(ResultFlag.ID);
      }
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    Class<?> javaTypeClass = resolveClass(javaType);
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<>();
    for (XNode caseChild : context.getChildren()) {
      String value = caseChild.getStringAttribute("value");
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings));
      discriminatorMap.put(value, resultMap);
    }
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  /**
   * 解析sql
   * @param list
   * @throws Exception
   */
  private void sqlElement(List<XNode> list) throws Exception {
    if (configuration.getDatabaseId() != null) {
      //当configuration中配置了databaseId时，先去匹配和该databaseId一致的sql
      sqlElement(list, configuration.getDatabaseId());
    }
    //再去匹配databaseId为空的sql
    sqlElement(list, null);
  }

  private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
    for (XNode context : list) {
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id");
      //获取sql的唯一标识，和resultMap一样，格式都是 daoName+"."+id
      id = builderAssistant.applyCurrentNamespace(id, false);
      //校验当前sql的数据库产商和configuration中的厂商是否一致
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
        sqlFragments.put(id, context);
      }
    }
  }

  /**
   * 校验当前sql的databaseId和configuration中的是否一致
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
      // skip this fragment if there is a previous one with a not null databaseId
      if (this.sqlFragments.containsKey(id)) {
        XNode context = this.sqlFragments.get(id);
        if (context.getStringAttribute("databaseId") != null) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * 解析resultMap下子标签
   * @param context resultMap下子标签<result>、<id>等
   * @param resultType resultType
   * @param flags <id/>标签的集合
   * @return
   * @throws Exception
   */
  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
    //java属性名
    String property;
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      property = context.getStringAttribute("name");
    } else {
      property = context.getStringAttribute("property");
    }
    //列名
    String column = context.getStringAttribute("column");
    //java类型
    String javaType = context.getStringAttribute("javaType");
    //数据库类型
    String jdbcType = context.getStringAttribute("jdbcType");
    //select属性值，主要用于联合查询
    String nestedSelect = context.getStringAttribute("select");
    //返回resultMap的id，若是不存在，并且标签为 association、collection、case，并且select元素为空
    // 则进行递归解析并返回resultMap的id
    String nestedResultMap = context.getStringAttribute("resultMap",
        processNestedResultMappings(context, Collections.<ResultMapping> emptyList()));
    String notNullColumn = context.getStringAttribute("notNullColumn");
    String columnPrefix = context.getStringAttribute("columnPrefix");
    //获取typeHandler
    String typeHandler = context.getStringAttribute("typeHandler");
    String resultSet = context.getStringAttribute("resultSet");
    String foreignColumn = context.getStringAttribute("foreignColumn");
    //是否懒加载
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
    //字段所表示的java类型Class
    Class<?> javaTypeClass = resolveClass(javaType);
    @SuppressWarnings("unchecked")
    //从别名中获取typeHandler的class
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }

  /**
   * 解析association、collection、case下的子标签（也就是不存在select属性）
   * @param context
   * @param resultMappings
   * @return
   * @throws Exception
   */
  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings) throws Exception {
    if ("association".equals(context.getName())
        || "collection".equals(context.getName())
        || "case".equals(context.getName())) {
      if (context.getStringAttribute("select") == null) {
        /**
         * 递归解析association、collection、case下的子标签，将其解析成一个个resultMapping
         * 并返回一个resultMap，这里也会将association、collection、case解析成resultMap加入到configuration
         * 中，并且返回一个id，将这个id和其外层resultMap标签关联，就是这么关联上的
         */
        ResultMap resultMap = resultMapElement(context, resultMappings);
        return resultMap.getId();
      }
    }
    return null;
  }
  /**根据nameSpace绑定Mapper*/
  private void bindMapperForNamespace() {
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        //可以看到mapper文件，可以不存在对应的dao
        //ignore, bound type is not required
      }
      if (boundType != null) {
        //该Mapper未注册到Configuration
        if (!configuration.hasMapper(boundType)) {
          // Spring may not know the real resource name so we set a flag
          // to prevent loading again this resource from the mapper interface
          // look at MapperAnnotationBuilder#loadXmlResource
          configuration.addLoadedResource("namespace:" + namespace);
          //向Configuration中注册这个dao
          configuration.addMapper(boundType);
        }
      }
    }
  }

}
