package com.yyl;

import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 拦截器分页测试
 */
public class MyTest2 {
    private static Logger log = LoggerFactory.getLogger(MyTest2.class);
    public static void main(String[] args){
        SqlSession sqlsession = SqlSessionFactoryUtil.openSqlSession();
        RoleMapper roleMapper = sqlsession.getMapper(RoleMapper.class);
        Page page = new Page<Role>();
        List list = roleMapper.getRoleByPage(page);
        System.out.println(list.size());
        System.out.println(page.getTotalRecord());
    }
    
}
