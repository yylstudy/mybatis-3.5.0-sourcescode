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
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */

/**
 * 默认resultSetHandler的实现
 */
public class DefaultResultSetHandler implements ResultSetHandler {

  private static final Object DEFERED = new Object();
  /**执行器*/
  private final Executor executor;
  private final Configuration configuration;
  /**MappedStatement*/
  private final MappedStatement mappedStatement;
  private final RowBounds rowBounds;
  /**ParameterHandler参数处理器*/
  private final ParameterHandler parameterHandler;
  /**结果处理器*/
  private final ResultHandler<?> resultHandler;
  private final BoundSql boundSql;
  /**TypeHandler注册器*/
  private final TypeHandlerRegistry typeHandlerRegistry;
  /**对象工厂创建器*/
  private final ObjectFactory objectFactory;
  /**反射器工厂*/
  private final ReflectorFactory reflectorFactory;

  // nested resultmaps
  /**这个应该是存放resultMap的缓存key（这个由resultMapId、columnName、columnValue决定）和 列上resultMap的映射关系 */
  private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
  /** 存放  dao类名+"."+resultMapId  ->>>resultMapping上的 resultMap对象的映射关系， 这个在解析resultMapping
   * 上的resultMap后马上移除
   * */
  private final Map<String, Object> ancestorObjects = new HashMap<>();
  private Object previousRowValue;

  // multiple resultsets
  private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
  private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

  // Cached Automappings
  private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

  // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
  //是否使用构造函数构造Mapping
  private boolean useConstructorMappings;

  private static class PendingRelation {
    public MetaObject metaObject;
    public ResultMapping propertyMapping;
  }

  private static class UnMappedColumnAutoMapping {
    private final String column;
    private final String property;
    private final TypeHandler<?> typeHandler;
    private final boolean primitive;

    public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
      this.column = column;
      this.property = property;
      this.typeHandler = typeHandler;
      this.primitive = primitive;
    }
  }

  public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                 RowBounds rowBounds) {
    this.executor = executor;
    this.configuration = mappedStatement.getConfiguration();
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;
    this.parameterHandler = parameterHandler;
    this.boundSql = boundSql;
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();
    this.reflectorFactory = configuration.getReflectorFactory();
    this.resultHandler = resultHandler;
  }

  //
  // HANDLE OUTPUT PARAMETER
  //

  @Override
  public void handleOutputParameters(CallableStatement cs) throws SQLException {
    final Object parameterObject = parameterHandler.getParameterObject();
    final MetaObject metaParam = configuration.newMetaObject(parameterObject);
    final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    for (int i = 0; i < parameterMappings.size(); i++) {
      final ParameterMapping parameterMapping = parameterMappings.get(i);
      if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
        if (ResultSet.class.equals(parameterMapping.getJavaType())) {
          handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
        } else {
          final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
          metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
        }
      }
    }
  }

  private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
    if (rs == null) {
      return;
    }
    try {
      final String resultMapId = parameterMapping.getResultMapId();
      final ResultMap resultMap = configuration.getResultMap(resultMapId);
      final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
      if (this.resultHandler == null) {
        final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
        metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
      } else {
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
      }
    } finally {
      // issue #228 (close resultsets)
      closeResultSet(rs);
    }
  }

  //
  // HANDLE RESULT SETS
  //

  /**
   * 封装statement的返回结果集
   * @param stmt
   * @return
   * @throws SQLException
   */
  @Override
  public List<Object> handleResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling results").object(mappedStatement.getId());
    /**存放返回结果集*/
    final List<Object> multipleResults = new ArrayList<>();
    int resultSetCount = 0;
    ResultSetWrapper rsw = getFirstResultSet(stmt);
    /**获取mappedStatement所有的resultMap集合，通常一个mappedStatement只有一个resultMap，也就是那个设置的值*/
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    int resultMapCount = resultMaps.size();
    /**校验resultMap数量和返回结果集是否冲突*/
    validateResultMapsCount(rsw, resultMapCount);
    /**遍历resultMap*/
    while (rsw != null && resultMapCount > resultSetCount) {
      ResultMap resultMap = resultMaps.get(resultSetCount);
      //处理resultSet
      handleResultSet(rsw, resultMap, multipleResults, null);
      rsw = getNextResultSet(stmt);
      cleanUpAfterHandlingResultSet();
      resultSetCount++;
    }

    String[] resultSets = mappedStatement.getResultSets();
    if (resultSets != null) {
      while (rsw != null && resultSetCount < resultSets.length) {
        ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
        if (parentMapping != null) {
          String nestedResultMapId = parentMapping.getNestedResultMapId();
          ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
          handleResultSet(rsw, resultMap, null, parentMapping);
        }
        rsw = getNextResultSet(stmt);
        cleanUpAfterHandlingResultSet();
        resultSetCount++;
      }
    }

    return collapseSingleResultList(multipleResults);
  }

  @Override
  public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

    ResultSetWrapper rsw = getFirstResultSet(stmt);

    List<ResultMap> resultMaps = mappedStatement.getResultMaps();

    int resultMapCount = resultMaps.size();
    validateResultMapsCount(rsw, resultMapCount);
    if (resultMapCount != 1) {
      throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
    }

    ResultMap resultMap = resultMaps.get(0);
    return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
  }

  /**
   * 获取resultSet
   * @param stmt
   * @return
   * @throws SQLException
   */
  private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
    ResultSet rs = stmt.getResultSet();
    while (rs == null) {
      // move forward to get the first resultset in case the driver
      // doesn't return the resultset as the first result (HSQLDB 2.1)
      if (stmt.getMoreResults()) {
        rs = stmt.getResultSet();
      } else {
        if (stmt.getUpdateCount() == -1) {
          // no more results. Must be no resultset
          break;
        }
      }
    }
    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
  }

  private ResultSetWrapper getNextResultSet(Statement stmt) {
    // Making this method tolerant of bad JDBC drivers
    try {
      if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
        // Crazy Standard JDBC way of determining if there are more results
        if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
          ResultSet rs = stmt.getResultSet();
          if (rs == null) {
            return getNextResultSet(stmt);
          } else {
            return new ResultSetWrapper(rs, configuration);
          }
        }
      }
    } catch (Exception e) {
      // Intentionally ignored.
    }
    return null;
  }

  private void closeResultSet(ResultSet rs) {
    try {
      if (rs != null) {
        rs.close();
      }
    } catch (SQLException e) {
      // ignore
    }
  }

  private void cleanUpAfterHandlingResultSet() {
    nestedResultObjects.clear();
  }

  private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
    if (rsw != null && resultMapCount < 1) {
      throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
          + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
    }
  }

  /**
   * 处理resultSet
   * @param rsw resultSet的封装类
   * @param resultMap 返回结果集
   * @param multipleResults
   * @param parentMapping null
   * @throws SQLException
   */
  private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
    try {
      if (parentMapping != null) {
        handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
      } else {
        if (resultHandler == null) {
          //创建一个resultHandler，这个对象是封装了一个空结果集，用于存放返回结果
          DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
          //处理每行的值
          handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
          multipleResults.add(defaultResultHandler.getResultList());
        } else {
          handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
        }
      }
    } finally {
      // issue #228 (close resultsets)
      closeResultSet(rsw.getResultSet());
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object> collapseSingleResultList(List<Object> multipleResults) {
    return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
  }

  //
  // HANDLE ROWS FOR SIMPLE RESULTMAP
  //

  /**
   * 处理行值
   * @param rsw resultSet的封装类
   * @param resultMap 结果集
   * @param resultHandler
   * @param rowBounds
   * @param parentMapping
   * @throws SQLException
   */
  public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    if (resultMap.hasNestedResultMaps()) {
      ensureNoRowBounds();
      checkResultHandler();
      //对于association、collection的包含resultMap或者不包含select元素的解析行值
      handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    } else {
      //普通的行值解析
      handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    }
  }

  private void ensureNoRowBounds() {
    if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
          + "Use safeRowBoundsEnabled=false setting to bypass this check.");
    }
  }

  protected void checkResultHandler() {
    if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
          + "Use safeResultHandlerEnabled=false setting to bypass this check "
          + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
    }
  }

  /**
   * 普通的行值解析
   * @param rsw
   * @param resultMap
   * @param resultHandler
   * @param rowBounds
   * @param parentMapping
   * @throws SQLException
   */
  private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
      throws SQLException {
    DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    skipRows(rsw.getResultSet(), rowBounds);
    while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
      ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
      //获取行值
      Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
      storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
    }
  }

  private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
    if (parentMapping != null) {
      linkToParents(rs, parentMapping, rowValue);
    } else {
      callResultHandler(resultHandler, resultContext, rowValue);
    }
  }

  @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
  private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
    resultContext.nextResultObject(rowValue);
    ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
  }

  private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
    return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
  }

  /**
   * 跳过行
   * @param rs
   * @param rowBounds
   * @throws SQLException
   */
  private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
    if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
      if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
        rs.absolute(rowBounds.getOffset());
      }
    } else {
      for (int i = 0; i < rowBounds.getOffset(); i++) {
        rs.next();
      }
    }
  }

  //
  // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
  //

  /**
   * 获取行值
   * @param rsw 包含resultSet对象
   * @param resultMap
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    final ResultLoaderMap lazyLoader = new ResultLoaderMap();
    //创建用于承载的JavaBean
    Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
    if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      boolean foundValues = this.useConstructorMappings;
      //是否自动映射，默认是true
      if (shouldApplyAutomaticMappings(resultMap, false)) {
        //执行自动映射
        foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
      }
      foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
      foundValues = lazyLoader.size() > 0 || foundValues;
      rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
    }
    return rowValue;
  }

  private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
    if (resultMap.getAutoMapping() != null) {
      return resultMap.getAutoMapping();
    } else {
      if (isNested) {
        return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
      } else {
        return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
      }
    }
  }

  //
  // PROPERTY MAPPINGS
  //

  /**
   * 解析propertyResultMapping
   * @param rsw
   * @param resultMap
   * @param metaObject
   * @param lazyLoader
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    /**获取<resultMap>中列名能和resultSet对应上的字段集合*/
    final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
    boolean foundValues = false;
    //获取resultMap中的resultMapping
    final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
    for (ResultMapping propertyMapping : propertyMappings) {
      String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      //排除association、collection的resultMap
      if (propertyMapping.getNestedResultMapId() != null) {
        // the user added a column attribute to a nested result map, ignore it
        column = null;
      }
      if (propertyMapping.isCompositeResult()
          || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))
          || propertyMapping.getResultSet() != null) {
        /**获取这个resultMapping的最终值，包括typeHandler的处理*/
        Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
        // issue #541 make property optional
        final String property = propertyMapping.getProperty();
        if (property == null) {
          continue;
        } else if (value == DEFERED) {
          foundValues = true;
          continue;
        }
        if (value != null) {
          foundValues = true;
        }
        /**值不为空，并且属性对应的class不是private，则设置值*/
        if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(property, value);
        }
      }
    }
    return foundValues;
  }

  /**
   * 获取普通属性的值
   * @param rs
   * @param metaResultObject
   * @param propertyMapping
   * @param lazyLoader
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    if (propertyMapping.getNestedQueryId() != null) {
      //association、collection的select 元素的有值
      return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
    } else if (propertyMapping.getResultSet() != null) {
      addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
      return DEFERED;
    } else {
      /**普通列*/
      final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
      final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      return typeHandler.getResult(rs, column);
    }
  }

  /**
   * 创建自动映射的Mapping
   * @param rsw
   * @param resultMap
   * @param metaObject
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    final String mapKey = resultMap.getId() + ":" + columnPrefix;
    List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
    if (autoMapping == null) {
      autoMapping = new ArrayList<>();
      //获取不匹配的字段名（设置为resultType，一般就都是不匹配的）
      final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
      for (String columnName : unmappedColumnNames) {
        String propertyName = columnName;
        if (columnPrefix != null && !columnPrefix.isEmpty()) {
          // When columnPrefix is specified,
          // ignore columns without the prefix.
          if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
            propertyName = columnName.substring(columnPrefix.length());
          } else {
            continue;
          }
        }
        final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
        if (property != null && metaObject.hasSetter(property)) {
          if (resultMap.getMappedProperties().contains(property)) {
            continue;
          }
          final Class<?> propertyType = metaObject.getSetterType(property);
          //存在TypeHandler，mybatis中有默认的TypeHandler
          if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
            final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
            autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
          } else {
            configuration.getAutoMappingUnknownColumnBehavior()
                .doAction(mappedStatement, columnName, property, propertyType);
          }
        } else {
          configuration.getAutoMappingUnknownColumnBehavior()
              .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
        }
      }
      autoMappingsCache.put(mapKey, autoMapping);
    }
    return autoMapping;
  }

  /**
   * 执行自动映射
   * @param rsw
   * @param resultMap
   * @param metaObject
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    //获取不匹配的
    List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
    boolean foundValues = false;
    if (!autoMapping.isEmpty()) {
      for (UnMappedColumnAutoMapping mapping : autoMapping) {
        final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
        if (value != null) {
          foundValues = true;
        }
        if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(mapping.property, value);
        }
      }
    }
    return foundValues;
  }

  // MULTIPLE RESULT SETS

  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
    CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
    List<PendingRelation> parents = pendingRelations.get(parentKey);
    if (parents != null) {
      for (PendingRelation parent : parents) {
        if (parent != null && rowValue != null) {
          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
        }
      }
    }
  }

  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
    CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
    PendingRelation deferLoad = new PendingRelation();
    deferLoad.metaObject = metaResultObject;
    deferLoad.propertyMapping = parentMapping;
    List<PendingRelation> relations = pendingRelations.computeIfAbsent(cacheKey, k -> new ArrayList<>());
    // issue #255
    relations.add(deferLoad);
    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
    if (previous == null) {
      nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
    } else {
      if (!previous.equals(parentMapping)) {
        throw new ExecutorException("Two different properties are mapped to the same resultSet");
      }
    }
  }

  private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
    CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMapping);
    if (columns != null && names != null) {
      String[] columnsArray = columns.split(",");
      String[] namesArray = names.split(",");
      for (int i = 0; i < columnsArray.length; i++) {
        Object value = rs.getString(columnsArray[i]);
        if (value != null) {
          cacheKey.update(namesArray[i]);
          cacheKey.update(value);
        }
      }
    }
    return cacheKey;
  }

  //
  // INSTANTIATION & CONSTRUCTOR MAPPING
  //

  /**
   * 创建result的javaBean，用于存放每行的值
   * @param rsw
   * @param resultMap
   * @param lazyLoader
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
    this.useConstructorMappings = false; // reset previous mapping result
    //构造参数类型
    final List<Class<?>> constructorArgTypes = new ArrayList<>();
    //构造参数
    final List<Object> constructorArgs = new ArrayList<>();
    //创建result的javaBean，（存在构造器就使用构造器，否则默认构造器反射）
    // 这个方法值解析constructorResultMapping
    Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
    if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      //获取属性的resultMapping
      final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
      for (ResultMapping propertyMapping : propertyMappings) {
        /**
         * 如果collection、association对象上的select不为空，
         * 并且select是懒加载，则创建具有延迟加载能力的代理对象JavassistProxyFactory
         * 为什么要循环全部呢，感觉是因为默认懒加载是层级的懒加载，只要这个resultMap中有一个懒加载，那么其他的select
         * 都是懒加载的。这也是这里要break的原因吧
         * ，
         */
        if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
          resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
          break;
        }
      }
    }
    this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
    return resultObject;
  }

  /**
   * 创建result的javaBean（已经赋值了）
   * @param rsw
   * @param resultMap
   * @param constructorArgTypes 构造参数类型集合
   * @param constructorArgs 构造参数集合
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
      throws SQLException {
    final Class<?> resultType = resultMap.getType();
    //获取resultMap 对应的java类型的MetaClass
    final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
    final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
    /**存在typeHandler*/
    if (hasTypeHandlerForResultObject(rsw, resultType)) {
      return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
      //若constructor标签不为空，则解析
    } else if (!constructorMappings.isEmpty()) {
      return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
    //这个应该就是生成没有constructor标签的javaBean对象
    } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
      return objectFactory.create(resultType);
    } else if (shouldApplyAutomaticMappings(resultMap, false)) {
      return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix);
    }
    throw new ExecutorException("Do not know how to create an instance of " + resultType);
  }

  /**
   *  构造结果
   * @param rsw
   * @param resultType resultMap的java类型
   * @param constructorMappings 构造器resultMapping集合
   * @param constructorArgTypes 构造参数类型集合
   * @param constructorArgs 构造参数集合
   * @param columnPrefix
   * @return
   */
  Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                         List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
    boolean foundValues = false;
    //遍历constructor标签
    for (ResultMapping constructorMapping : constructorMappings) {
      //resultMapping的java类型
      final Class<?> parameterType = constructorMapping.getJavaType();
      //获取列名
      final String column = constructorMapping.getColumn();
      final Object value;
      try {
        //association、collection的select 元素的值 不为空
        if (constructorMapping.getNestedQueryId() != null) {
          //返回association、collection的select的值
          value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
          //association、collection的resultMap
        } else if (constructorMapping.getNestedResultMapId() != null) {
          final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
          //获取这个resultMap的值
          value = getRowValue(rsw, resultMap, constructorMapping.getColumnPrefix());
        } else {
          //若是普通列，直接获取typeHandler进行转化
          final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
          value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
        }
      } catch (ResultMapException | SQLException e) {
        throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
      }
      constructorArgTypes.add(parameterType);
      constructorArgs.add(value);
      foundValues = value != null || foundValues;
    }
    //构建返回result 的JavaBean，并赋值
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
                                              String columnPrefix) throws SQLException {
    final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
    final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);
    if (defaultConstructor != null) {
      return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, defaultConstructor);
    } else {
      for (Constructor<?> constructor : constructors) {
        if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
          return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, constructor);
        }
      }
    }
    throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
  }

  private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix, Constructor<?> constructor) throws SQLException {
    boolean foundValues = false;
    for (int i = 0; i < constructor.getParameterTypes().length; i++) {
      Class<?> parameterType = constructor.getParameterTypes()[i];
      String columnName = rsw.getColumnNames().get(i);
      TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
      Object value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(columnName, columnPrefix));
      constructorArgTypes.add(parameterType);
      constructorArgs.add(value);
      foundValues = value != null || foundValues;
    }
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private Constructor<?> findDefaultConstructor(final Constructor<?>[] constructors) {
    if (constructors.length == 1) {
      return constructors[0];
    }

    for (final Constructor<?> constructor : constructors) {
      if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
        return constructor;
      }
    }
    return null;
  }

  private boolean allowedConstructorUsingTypeHandlers(final Constructor<?> constructor, final List<JdbcType> jdbcTypes) {
    final Class<?>[] parameterTypes = constructor.getParameterTypes();
    if (parameterTypes.length != jdbcTypes.size()) return false;
    for (int i = 0; i < parameterTypes.length; i++) {
      if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
        return false;
      }
    }
    return true;
  }

  private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    final Class<?> resultType = resultMap.getType();
    final String columnName;
    if (!resultMap.getResultMappings().isEmpty()) {
      final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
      final ResultMapping mapping = resultMappingList.get(0);
      columnName = prependPrefix(mapping.getColumn(), columnPrefix);
    } else {
      columnName = rsw.getColumnNames().get(0);
    }
    final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
    return typeHandler.getResult(rsw.getResultSet(), columnName);
  }

  //
  // NESTED QUERY
  //

  /**
   * 返回association、collection的select的值
   * @param rs resultSet
   * @param constructorMapping resultMapping集合
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
    final String nestedQueryId = constructorMapping.getNestedQueryId();
    //获取association、collection对应的select对应的mappedStatement
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    //select对应的参数类型
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    /** 为 association、collection的select准备参数，也就是获取association、collection对应column对应的值*/
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    //若association、collection的select参数值不为空
    if (nestedQueryParameterObject != null) {
      //获取association、collection对应的select 的BoundSql对象
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      //创建缓存key
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      //获取association、collection对应的select的java类型
      final Class<?> targetType = constructorMapping.getJavaType();
      final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
      //获取association、collection对应的select的值，内部递归调用query方法
      value = resultLoader.loadResult();
    }
    return value;
  }

  /**
   * association、collection的select 元素的有值
   * @param rs
   * @param metaResultObject
   * @param propertyMapping
   * @param lazyLoader
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    final String nestedQueryId = propertyMapping.getNestedQueryId();
    final String property = propertyMapping.getProperty();
    /**获取association、collection的select 元素对应的MappedStatement*/
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    /**这个就是association、collection的select对应的参数值*/
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    if (nestedQueryParameterObject != null) {
      /**获取BoundSql*/
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      final Class<?> targetType = propertyMapping.getJavaType();
      if (executor.isCached(nestedQuery, key)) {
        executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
        value = DEFERED;
      } else {
        final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
        if (propertyMapping.isLazy()) {
          lazyLoader.addLoader(property, metaResultObject, resultLoader);
          value = DEFERED;
        } else {
          /**未延迟加载，就直接获取值*/
          value = resultLoader.loadResult();
        }
      }
    }
    return value;
  }

  /**
   * 为 association、collection的select准备参数，也就是获取association、collection对应column对应的值
   * @param rs
   * @param resultMapping
   * @param parameterType select对应的参数类型
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    if (resultMapping.isCompositeResult()) {
      return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    } else {
      return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    }
  }

  /**
   * 为 association、collection的select准备参数，也就是获取association、collection对应column对应的值
   * @param rs
   * @param resultMapping
   * @param parameterType select对应的参数类型
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final TypeHandler<?> typeHandler;
    if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
      typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
    } else {
      typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
    }
    /**
     * resultMapping.getColumn()这个方法可以看出  association、collection需要关联参数  如column="id"
     * 获取对应列的值，若列上有typeHandler，则进行相应的处理
     */
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final Object parameterObject = instantiateParameterObject(parameterType);
    final MetaObject metaObject = configuration.newMetaObject(parameterObject);
    boolean foundValues = false;
    for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
      final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
      final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
      final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
      // issue #353 & #560 do not execute nested query if key is null
      if (propValue != null) {
        metaObject.setValue(innerResultMapping.getProperty(), propValue);
        foundValues = true;
      }
    }
    return foundValues ? parameterObject : null;
  }

  private Object instantiateParameterObject(Class<?> parameterType) {
    if (parameterType == null) {
      return new HashMap<>();
    } else if (ParamMap.class.equals(parameterType)) {
      return new HashMap<>(); // issue #649
    } else {
      return objectFactory.create(parameterType);
    }
  }

  //
  // DISCRIMINATOR
  //

  /**
   * 解析 resultMap的Discriminated
   * @param rs resultSet
   * @param resultMap
   * @param columnPrefix null
   * @return
   * @throws SQLException
   */
  public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
    Set<String> pastDiscriminators = new HashSet<>();
    Discriminator discriminator = resultMap.getDiscriminator();
    while (discriminator != null) {
      final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
      final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
      if (configuration.hasResultMap(discriminatedMapId)) {
        resultMap = configuration.getResultMap(discriminatedMapId);
        Discriminator lastDiscriminator = discriminator;
        discriminator = resultMap.getDiscriminator();
        if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
          break;
        }
      } else {
        break;
      }
    }
    return resultMap;
  }

  private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
    final ResultMapping resultMapping = discriminator.getResultMapping();
    final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private String prependPrefix(String columnName, String prefix) {
    if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
      return columnName;
    }
    return prefix + columnName;
  }

  //
  // HANDLE NESTED RESULT MAPS
  //

  /**
   * 对于association、collection的包含resultMap元素的解析行值
   * @param rsw resultSet的封装类
   * @param resultMap
   * @param resultHandler
   * @param rowBounds
   * @param parentMapping
   * @throws SQLException
   */
  private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    skipRows(rsw.getResultSet(), rowBounds);
    Object rowValue = previousRowValue;
    while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
      //处理discriminated（没看）
      final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
      //为这行记录生成一个key，createRowKey会利用resultMapping来生成key
      final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
      Object partialObject = nestedResultObjects.get(rowKey);
      // issue #577 && #542
      if (mappedStatement.isResultOrdered()) {
        if (partialObject == null && rowValue != null) {
          nestedResultObjects.clear();
          storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
        }
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
      } else {
        /**获取每行的值*/
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
        if (partialObject == null) {
          //将返回值添加进DefaultResultHandler中的list对象
          storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
        }
      }
    }
    if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
      storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
      previousRowValue = null;
    } else if (rowValue != null) {
      previousRowValue = rowValue;
    }
  }

  //
  // GET VALUE FROM ROW FOR NESTED RESULT MAP
  //

  /**
   * 获取行值
   * @param rsw resultSet的包装类
   * @param resultMap ResultMap
   * @param combinedKey 前面计算出来的缓存key
   * @param columnPrefix
   * @param partialObject
   * @return
   * @throws SQLException
   */
  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
    final String resultMapId = resultMap.getId();
    Object rowValue = partialObject;
    if (rowValue != null) {
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      putAncestor(rowValue, resultMapId);
      applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
      ancestorObjects.remove(resultMapId);
    } else {
      //第一次进来这个值应该是空的
      final ResultLoaderMap lazyLoader = new ResultLoaderMap();
      //创建每行的对象（属性为null的JavaBean对象）
      rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
      /**javaBean不为空，并且创建出来的JavaBean不存在其类型的TypeHandler*/
      if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
        /**创建JavaBean的MetaObject，这个用于赋值*/
        final MetaObject metaObject = configuration.newMetaObject(rowValue);
        boolean foundValues = this.useConstructorMappings;
        if (shouldApplyAutomaticMappings(resultMap, true)) {
          foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
        }
        /**解析propertyResultMapping，填充上面创建出来的rowValue值，返回是否发现值，这个主要用于
         * association、collection的判断，如果association、collection对应的字段都为空，那么这个
         * 关联的值为null 而不是一个值都为空的对象*/
        foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
        putAncestor(rowValue, resultMapId);
        //解析association、collection的resultMap，填充rowValue的值
        foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
        ancestorObjects.remove(resultMapId);
        foundValues = lazyLoader.size() > 0 || foundValues;
        //这里可以看到，如果一行中都未查询到值，并且setting中未配置当查询的行都为空时返回空的JavaBean时，则直接返回null
        //而不是空的javaBean
        rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
      }
      if (combinedKey != CacheKey.NULL_CACHE_KEY) {
        nestedResultObjects.put(combinedKey, rowValue);
      }
    }
    return rowValue;
  }

  private void putAncestor(Object resultObject, String resultMapId) {
    ancestorObjects.put(resultMapId, resultObject);
  }

  //
  // NESTED RESULT MAP (JOIN MAPPING)
  //

  /**
   * //解析association、collection的resultMap，填充rowValue的值
   * @param rsw
   * @param resultMap
   * @param metaObject  解析生成的结果集对象
   * @param parentPrefix
   * @param parentRowKey
   * @param newObject
   * @return
   */
  private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
    boolean foundValues = false;
    for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
      /**获取当前ResultMapping上的resultMap的id*/
      final String nestedResultMapId = resultMapping.getNestedResultMapId();
      if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
        try {
          final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
          /**获取association、collection的resultMap对象*/
          final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
          if (resultMapping.getColumnPrefix() == null) {
            // try to fill circular reference only when columnPrefix
            // is not specified for the nested result map (issue #215)
            Object ancestorObject = ancestorObjects.get(nestedResultMapId);
            if (ancestorObject != null) {
              if (newObject) {
                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
              }
              continue;
            }
          }
          //创建缓存key
          final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
          final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
          //从缓存中获取，nestedResultObjects这个是在解析构造器的时候已经解析过了
          Object rowValue = nestedResultObjects.get(combinedKey);
          boolean knownValue = rowValue != null;
          //resultMap对应的Java类型是集合子类，那么就创建用于承载resultMap的集合对象
          instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
          if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
            //获取association、collection的值
            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
            if (rowValue != null && !knownValue) {
              linkObjects(metaObject, resultMapping, rowValue);
              foundValues = true;
            }
          }
        } catch (SQLException e) {
          throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
        }
      }
    }
    return foundValues;
  }

  private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
    final StringBuilder columnPrefixBuilder = new StringBuilder();
    if (parentPrefix != null) {
      columnPrefixBuilder.append(parentPrefix);
    }
    if (resultMapping.getColumnPrefix() != null) {
      columnPrefixBuilder.append(resultMapping.getColumnPrefix());
    }
    return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
  }

  private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
    Set<String> notNullColumns = resultMapping.getNotNullColumns();
    if (notNullColumns != null && !notNullColumns.isEmpty()) {
      ResultSet rs = rsw.getResultSet();
      for (String column : notNullColumns) {
        rs.getObject(prependPrefix(column, columnPrefix));
        if (!rs.wasNull()) {
          return true;
        }
      }
      return false;
    } else if (columnPrefix != null) {
      for (String columnName : rsw.getColumnNames()) {
        if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
    ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
    return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
  }

  //
  // UNIQUE RESULT KEY
  //

  private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
    final CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMap.getId());
    //获取resultMap的idResultMapping集合，若不存在就获取propertyResultMapping
    List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
    if (resultMappings.isEmpty()) {
      if (Map.class.isAssignableFrom(resultMap.getType())) {
        createRowKeyForMap(rsw, cacheKey);
      } else {
        createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
      }
    } else {
      createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
    }
    if (cacheKey.getUpdateCount() < 2) {
      return CacheKey.NULL_CACHE_KEY;
    }
    return cacheKey;
  }

  private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
    if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
      CacheKey combinedKey;
      try {
        combinedKey = rowKey.clone();
      } catch (CloneNotSupportedException e) {
        throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
      }
      combinedKey.update(parentRowKey);
      return combinedKey;
    }
    return CacheKey.NULL_CACHE_KEY;
  }

  private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
    List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
    if (resultMappings.isEmpty()) {
      resultMappings = resultMap.getPropertyResultMappings();
    }
    return resultMappings;
  }

  /**
   * 为mappedStatement的配置创建行key
   * @param resultMap
   * @param rsw 包含resultSet对象
   * @param cacheKey 缓存key
   * @param resultMappings resultMap下的resultMapping集合
   * @param columnPrefix
   * @throws SQLException
   */
  private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
    for (ResultMapping resultMapping : resultMappings) {
      //若是当前resultMap中包含collection、association节点（没有select属性），并且result属性为空
      if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
        // Issue #392
        //那么从configuration中获取这个resultMapping中包含的resultMap
        final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
        //递归调用创建行key
        createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey, nestedResultMap.getConstructorResultMappings(),
            prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
        //association、collection的select 元素的值，就说明这个resultMapping是简单的列，没有级联查询的属性什么的
      } else if (resultMapping.getNestedQueryId() == null) {
        //返回列名
        final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
        //获取这个resultMapping的typeHandler实例
        final TypeHandler<?> th = resultMapping.getTypeHandler();
        //获取resultMap能和resultSet对应上的列名集合
        List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        // Issue #114
        //当前resuestMapping的列名存在于  resultMap能和resultSet对应上的列名集合
        if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
          //根据列名从resultSet中获取值
          final Object value = th.getResult(rsw.getResultSet(), column);
          //值不为空，更新缓存key
          if (value != null || configuration.isReturnInstanceForEmptyRow()) {
            cacheKey.update(column);
            cacheKey.update(value);
          }
        }
      }
    }
  }

  private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
    final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
    List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
    for (String column : unmappedColumnNames) {
      String property = column;
      if (columnPrefix != null && !columnPrefix.isEmpty()) {
        // When columnPrefix is specified, ignore columns without the prefix.
        if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
          property = column.substring(columnPrefix.length());
        } else {
          continue;
        }
      }
      if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
        String value = rsw.getResultSet().getString(column);
        if (value != null) {
          cacheKey.update(column);
          cacheKey.update(value);
        }
      }
    }
  }

  private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
    List<String> columnNames = rsw.getColumnNames();
    for (String columnName : columnNames) {
      final String value = rsw.getResultSet().getString(columnName);
      if (value != null) {
        cacheKey.update(columnName);
        cacheKey.update(value);
      }
    }
  }

  private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
    //resultMap对应的Java类型是集合子类，那么就创建用于承载resultMap的集合对象
    final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
    if (collectionProperty != null) {
      final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
      //若是集合添加进集合
      targetMetaObject.add(rowValue);
    } else {
      //否则直接设置值到对应属性
      metaObject.setValue(resultMapping.getProperty(), rowValue);
    }
  }

  /**
   * resultMap对应的Java类型是集合子类，那么就创建用于承载resultMap的集合对象
   * @param resultMapping
   * @param metaObject
   * @return
   */
  private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
    final String propertyName = resultMapping.getProperty();
    //获取resultMapping上resultMap的值，一般为空
    Object propertyValue = metaObject.getValue(propertyName);
    if (propertyValue == null) {
      //java类型
      Class<?> type = resultMapping.getJavaType();
      if (type == null) {
        //java类型为空，反射获取java类型
        type = metaObject.getSetterType(propertyName);
      }
      try {
        if (objectFactory.isCollection(type)) {
          propertyValue = objectFactory.create(type);
          metaObject.setValue(propertyName, propertyValue);
          return propertyValue;
        }
      } catch (Exception e) {
        throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
      }
    } else if (objectFactory.isCollection(propertyValue.getClass())) {
      return propertyValue;
    }
    return null;
  }

  private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
    if (rsw.getColumnNames().size() == 1) {
      return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
    }
    return typeHandlerRegistry.hasTypeHandler(resultType);
  }

}
