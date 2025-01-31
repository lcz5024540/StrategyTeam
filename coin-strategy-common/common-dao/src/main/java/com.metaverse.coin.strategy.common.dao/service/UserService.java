package com.metaverse.coin.strategy.common.dao.service;


import com.metaverse.coin.strategy.common.dao.bean.User;

import java.util.List;

public interface UserService {

    boolean addUser(User user);

    User getUser(Integer id);

    List<User> getUser(String userName);

    boolean updateUser(User user);
}
