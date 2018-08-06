package com.yyl;

import java.util.List;

public interface RoleMapper {
    List<Role> getRole();
    List<Role> getRoleByPage(Page page);
    int addRole(Role role);
}
