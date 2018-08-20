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
package org.apache.ibatis.executor.statement;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 */
public abstract class BaseStatementHandler implements StatementHandler {
  //全局配置
  protected final Configuration configuration;
  //构建范湖JavaBean 工厂实例
  protected final ObjectFactory objectFactory;
  //typeHandler注册器
  protected final TypeHandlerRegistry typeHandlerRegistry;
  //resultSetHandler，用于后面处理返回结果集的
  protected final ResultSetHandler resultSetHandler;
  //parameterHandler，用于后面处理参数的
  protected final ParameterHandler parameterHandler;
  //执行器
  protected final Executor executor;
  //mappedStatement
  protected final MappedStatement mappedStatement;
  //RowBounds
  protected final RowBounds rowBounds;
  //要执行的sql对象
  protected BoundSql boundSql;
  /**
   * 构建一个基础的StatementHandler
   * @param executor
   * @param mappedStatement
   * @param parameterObject 参数名和参数值的映射关系，Map<String,Object>，
   *      *                  若参数只有一个且没有@Param注解，那么这个parameter就是第一个参数本身
   * @param rowBounds
   * @param resultHandler
   * @param boundSql
   */
  protected BaseStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    this.configuration = mappedStatement.getConfiguration();
    this.executor = executor;
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;

    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();

    if (boundSql == null) { // issue #435, get the key before calculating the statement
      generateKeys(parameterObject);
      //获取boundSql对象
      boundSql = mappedStatement.getBoundSql(parameterObject);
    }

    this.boundSql = boundSql;
    //创建sqlSession 下的四大对象之一，parameterHandler，用于处理参数
    this.parameterHandler = configuration.newParameterHandler(mappedStatement, parameterObject, boundSql);
    //创建sqlSession下的四大对象之一，resultSetHandler，用于处理返回结果集
    this.resultSetHandler = configuration.newResultSetHandler(executor, mappedStatement, rowBounds, parameterHandler, resultHandler, boundSql);
  }

  @Override
  public BoundSql getBoundSql() {
    return boundSql;
  }

  @Override
  public ParameterHandler getParameterHandler() {
    return parameterHandler;
  }

  /**
   * statement 的准备方法，获取一个JDBC的statement
   * @param connection
   * @param transactionTimeout
   * @return
   * @throws SQLException
   */
  @Override
  public Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException {
    ErrorContext.instance().sql(boundSql.getSql());
    Statement statement = null;
    try {
      //初始化一个statement，比如经常使用到的PreparedStatement
      statement = instantiateStatement(connection);
      setStatementTimeout(statement, transactionTimeout);
      setFetchSize(statement);
      return statement;
    } catch (SQLException e) {
      closeStatement(statement);
      throw e;
    } catch (Exception e) {
      closeStatement(statement);
      throw new ExecutorException("Error preparing statement.  Cause: " + e, e);
    }
  }

  protected abstract Statement instantiateStatement(Connection connection) throws SQLException;

  protected void setStatementTimeout(Statement stmt, Integer transactionTimeout) throws SQLException {
    Integer queryTimeout = null;
    if (mappedStatement.getTimeout() != null) {
      queryTimeout = mappedStatement.getTimeout();
    } else if (configuration.getDefaultStatementTimeout() != null) {
      queryTimeout = configuration.getDefaultStatementTimeout();
    }
    if (queryTimeout != null) {
      stmt.setQueryTimeout(queryTimeout);
    }
    StatementUtil.applyTransactionTimeout(stmt, queryTimeout, transactionTimeout);
  }

  protected void setFetchSize(Statement stmt) throws SQLException {
    Integer fetchSize = mappedStatement.getFetchSize();
    if (fetchSize != null) {
      stmt.setFetchSize(fetchSize);
      return;
    }
    Integer defaultFetchSize = configuration.getDefaultFetchSize();
    if (defaultFetchSize != null) {
      stmt.setFetchSize(defaultFetchSize);
    }
  }

  protected void closeStatement(Statement statement) {
    try {
      if (statement != null) {
        statement.close();
      }
    } catch (SQLException e) {
      //ignore
    }
  }

  /**
   * 允许JDBC自动生成主键
   * @param parameter 参数名和参数值的映射关系，Map<String,Object>
   */
  protected void generateKeys(Object parameter) {
    /**
     * 获取主键生成策略
     * Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE
     * 可以看到 若是NoKeyGenerator 则processBefore do nothing
     *
     */
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    ErrorContext.instance().store();
    //处理selectKey或者其他主键生成策略
    keyGenerator.processBefore(executor, mappedStatement, null, parameter);
    ErrorContext.instance().recall();
  }

}
