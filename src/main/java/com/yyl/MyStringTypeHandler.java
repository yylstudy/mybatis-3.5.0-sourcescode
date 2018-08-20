package com.yyl;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.apache.ibatis.type.TypeHandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 自定义typeHandler，对结果进行处理，可以看到getResult有三个方法，但是好像只有一个会生效？？？？？？？？？
 * 查看源码可知，getResult(ResultSet rs, String columnName)是都是调用这个方法，其他两个方法不知道在什么时候调用
 */
//定义的是JavaType类型，可以指定哪些Java类型被拦截
@MappedTypes({String.class})
//定义的是JdbcType类型
@MappedJdbcTypes(JdbcType.VARCHAR)
public class MyStringTypeHandler implements TypeHandler<String> {
    /**
     *
     * @param ps JDBC的PreparedStatement
     * @param i  参数的下标
     * @param parameter 参数的值
     * @param jdbcType  字段对应的JDBC类型
     * @throws SQLException
     */
    @Override
    public void setParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        System.out.println("使用我自定义的typeHandler，设置参数，改变参数");
        ps.setString(i,"hahaha"+parameter);
    }

    /**
     *
     * @param rs JDBC的ResultSet
     * @param columnName 字段名
     * @return
     * @throws SQLException
     */
    @Override
    public String getResult(ResultSet rs, String columnName) throws SQLException {
        System.out.println("使用我自定义的typeHandler 根据字段名称，改变结果");
        return "自定义typeHandler类型根据列名获取结果："+rs.getString(columnName);
    }

    /**
     *
     * @param rs JDBC的ResultSet
     * @param columnIndex 字段下标
     * @return
     * @throws SQLException
     */
    @Override
    public String getResult(ResultSet rs, int columnIndex) throws SQLException {
        System.out.println("使用我自定义的TypeHandler 根据下标，改变结果");
        return "自定义typeHandler类型根据下标获取结果："+rs.getString(columnIndex);
    }

    @Override
    public String getResult(CallableStatement cs, int columnIndex) throws SQLException {
        System.out.println("使用自定义的typeHandler,CallableStatement获取结果，改变结果");
        return cs.getString(columnIndex);
    }
}
