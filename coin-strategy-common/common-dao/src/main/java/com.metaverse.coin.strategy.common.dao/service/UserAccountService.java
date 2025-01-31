package com.metaverse.coin.strategy.common.dao.service;


import com.metaverse.coin.strategy.common.dao.bean.UserAccount;

import java.util.List;

public interface UserAccountService {

    boolean addAccount(UserAccount account);

    UserAccount getAccount(Integer id);

    List<UserAccount> getAccount(String userName);

    boolean updateUserAccount(UserAccount account);
}
