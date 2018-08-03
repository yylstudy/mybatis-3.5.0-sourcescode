package com.yyl;

import java.util.List;

public interface RoleMapper {
    List<Role> getRole();
    int addRole(Role role);
}
