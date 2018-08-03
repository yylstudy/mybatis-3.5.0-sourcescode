package com.yyl;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.InputStream;
import java.util.List;

public class SqlSessionFactoryUtil {
    private static SqlSessionFactory sqlSessionFactory = null;
    private static final Class CLASS_LOCK = SqlSessionFactoryUtil.class;
    private SqlSessionFactoryUtil(){}

    public static SqlSessionFactory initSqlSessionFactory(){
        String resource = "mybatis-config.xml";
        InputStream is = null;
        try{
            is = Resources.getResourceAsStream(resource);
        }catch(Exception e){
            e.printStackTrace();
        }
        synchronized (CLASS_LOCK){
            if(sqlSessionFactory==null){
                sqlSessionFactory = new SqlSessionFactoryBuilder().build(is);
            }
        }
        return sqlSessionFactory;
    }
    public static SqlSession openSqlSession(){
        if(sqlSessionFactory==null){
            initSqlSessionFactory();
        }
        return sqlSessionFactory.openSession();
    }
    public static void main(String[] args){
        SqlSession sqlSession = openSqlSession();
        RoleMapper roleMapper = sqlSession.getMapper(RoleMapper.class);
        Role role = new Role();
        role.setName("yyl5");
        roleMapper.addRole(role);
        List<Role> list = roleMapper.getRole();
        list.forEach(role2-> System.out.println(role2.getName()));
        sqlSession.commit();
        sqlSession.close();
    }
}
