package com.yyl;

import org.apache.ibatis.session.SqlSession;

import java.util.List;

public class MyTest1 {
    public static void main(String[] args){
        SqlSession sqlsession = SqlSessionFactoryUtil.openSqlSession();
        RoleMapper roleMapper = sqlsession.getMapper(RoleMapper.class);
        Role role = new Role();
        role.setName("yyl5");
        roleMapper.addRole(role);
        List<Role> list = roleMapper.getRole();
        list.forEach(role2-> System.out.println(role2.getName()));
        sqlsession.commit();
        sqlsession.close();
    }
    
}
