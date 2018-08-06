package com.yyl;

import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

/**
 * @Interceptor 说明是一个拦截器
 * @Signature 拦截器的签名
 *
 * type:拦截的类型，sqlSession的四大对象之一（Executor,ResultSetHandler,ParameterHandler,StatementHandler）
 * method:拦截的方法
 * args:参数
 */
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class,Integer.class}))
public class MyPageInterceptor implements Interceptor {
    private static final ObjectFactory DEFAULT_OBJECT_FACTORY = new DefaultObjectFactory();
    private static final ObjectWrapperFactory DEFAULT_OBJECT_WRAPPER_FACTORY = new DefaultObjectWrapperFactory();
    Logger log = LoggerFactory.getLogger(MyPageInterceptor.class);

    /**
     * 进行拦截的时候要执行的方法
     * @param invocation
     * @return
     * @throws Throwable
     */
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        //获取StatementHandler,默认是RoutingStatementHandler
        StatementHandler statementHandler = (StatementHandler)invocation.getTarget();
        //获取statementHandler的包装类
        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
        MappedStatement mappedStatement =
                (MappedStatement)metaObject.getValue("delegate.mappedStatement");
        // 分离代理对象链(由于目标类可能被多个拦截器拦截，从而形成多次代理，通过下面的两次循环可以分离出最原始的的目标类)
        while (metaObject.hasGetter("h")) {
            Object object = metaObject.getValue("h");
            metaObject = MetaObject.forObject(object,
                    DEFAULT_OBJECT_FACTORY, DEFAULT_OBJECT_WRAPPER_FACTORY,null);
        }
        // 分离最后一个代理对象的目标类
        while (metaObject.hasGetter("target")) {
            Object object = metaObject.getValue("target");
            metaObject = MetaObject.forObject(object,
                    DEFAULT_OBJECT_FACTORY, DEFAULT_OBJECT_WRAPPER_FACTORY,null);
        }
        String methodName = mappedStatement.getId();
        //若方法名符合该正则表达式,如queryRoleByPage
        if(methodName.matches(".+ByPage$")){
            //要执行的sql对象
            BoundSql boundSql = statementHandler.getBoundSql();
            Object object = boundSql.getParameterObject();
            if(object instanceof Page){
                Page page = (Page)object;
                Connection connection = (Connection)invocation.getArgs()[0];
                String sql = boundSql.getSql();
                String countSql = "select count(1) "+sql.substring(sql.indexOf("from"),sql.length());
                PreparedStatement countStatement = connection.prepareStatement(countSql);
                ParameterHandler parameterHandler = (ParameterHandler) metaObject.getValue("delegate.parameterHandler");
                parameterHandler.setParameters(countStatement);
                ResultSet resultSet = countStatement.executeQuery();
                if(resultSet.next()){
                    page.setTotalRecord(resultSet.getInt(1));
                    log.debug("拦截器得知page的总记录数为："+page.getTotalRecord());
                }
                String pageSql = sql+" limit "+page.getPageNo()*page.getPageSize()+" , "+page.getPageSize();
                metaObject.setValue("delegate.boundSql.sql", pageSql);
            }else{
                log.debug("分页拦截器执行失败，参数不符合规定");
            }
        }
        return invocation.proceed();
    }

    /**
     *
     * 决定是否拦截进而决定要返回一个什么样的对象
     * @param target  CachingExecutor
     * @return
     */
    @Override
    public Object plugin(Object target) {
        // TODO Auto-generated method stub
        if (target instanceof StatementHandler) {
            return Plugin.wrap(target, this);
        } else {
            return target;
        }
    }


    @Override
    public void setProperties(Properties properties) {

    }
}
