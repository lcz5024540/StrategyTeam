package com.metaverse.coin.strategy.common.dao.service.impl;

import com.metaverse.coin.strategy.common.dao.bean.UserAccount;
import org.springframework.stereotype.Service;
import com.metaverse.coin.strategy.common.dao.service.UserAccountService;

import java.util.List;

@Service
public class UserAccountServiceImpl implements UserAccountService {
    @Override
    public boolean addAccount(UserAccount account) {
        return false;
    }

    @Override
    public UserAccount getAccount(Integer id) {
        return null;
    }

    @Override
    public List<UserAccount> getAccount(String userName) {
        return null;
    }

    @Override
    public boolean updateUserAccount(UserAccount account) {
        return false;
    }
}
