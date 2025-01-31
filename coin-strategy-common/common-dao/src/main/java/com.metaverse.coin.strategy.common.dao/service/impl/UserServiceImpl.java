package com.metaverse.coin.strategy.common.dao.service.impl;

import com.metaverse.coin.strategy.common.dao.bean.User;
import org.springframework.stereotype.Service;
import com.metaverse.coin.strategy.common.dao.service.UserService;

import java.util.List;

@Service
public class UserServiceImpl implements UserService {
    @Override
    public boolean addUser(User user) {
        return false;
    }

    @Override
    public User getUser(Integer id) {
        return null;
    }

    @Override
    public List<User> getUser(String userName) {
        return null;
    }

    @Override
    public boolean updateUser(User user) {
        return false;
    }
}
