"""测试账户守卫"""

import pytest

from htx_trend.research.account_guard import (
    AccountConfig,
    AccountGuard,
    create_main_account_guard,
)


def test_account_config_validation():
    """测试账户配置验证"""
    valid_config = AccountConfig(
        account_id="main_123",
        account_type="MAIN",
        margin_mode="cross",
    )
    
    valid_config.validate()
    
    invalid_type = AccountConfig(
        account_id="sub_456",
        account_type="SUB",
        margin_mode="cross",
    )
    
    with pytest.raises(ValueError, match="Only MAIN account is allowed"):
        invalid_type.validate()
    
    invalid_margin = AccountConfig(
        account_id="main_123",
        account_type="MAIN",
        margin_mode="isolated",
    )
    
    with pytest.raises(ValueError, match="Only cross margin mode is allowed"):
        invalid_margin.validate()


def test_account_guard_allows_correct_account():
    """测试守卫允许正确的账户"""
    config = AccountConfig(
        account_id="main_12345",
        account_type="MAIN",
        margin_mode="cross",
    )
    
    guard = AccountGuard(config)
    
    allowed, reason = guard.check_order_permission("main_12345", "place_order")
    
    assert allowed
    assert "authorized" in reason.lower()


def test_account_guard_rejects_wrong_account():
    """测试守卫拒绝错误的账户"""
    config = AccountConfig(
        account_id="main_12345",
        account_type="MAIN",
        margin_mode="cross",
    )
    
    guard = AccountGuard(config)
    
    allowed, reason = guard.check_order_permission("sub_67890", "place_order")
    
    assert not allowed
    assert "rejected" in reason.lower()
    assert "main_12345" in reason
    assert "sub_67890" in reason


def test_account_guard_require_permission_success():
    """测试 require_permission 成功情况"""
    guard = create_main_account_guard("main_12345")
    
    guard.require_permission("main_12345", "place_order")


def test_account_guard_require_permission_failure():
    """测试 require_permission 失败情况"""
    guard = create_main_account_guard("main_12345")
    
    with pytest.raises(PermissionError, match="Order place_order rejected"):
        guard.require_permission("sub_67890", "place_order")


def test_create_main_account_guard():
    """测试便捷创建函数"""
    guard = create_main_account_guard("main_test")
    
    assert guard.allowed_account.account_id == "main_test"
    assert guard.allowed_account.account_type == "MAIN"
    assert guard.allowed_account.margin_mode == "cross"
    
    allowed, _ = guard.check_order_permission("main_test")
    assert allowed
    
    allowed, _ = guard.check_order_permission("other_account")
    assert not allowed


def test_account_guard_creation_with_invalid_config():
    """测试使用无效配置创建守卫"""
    invalid_config = AccountConfig(
        account_id="sub_123",
        account_type="SUB",
        margin_mode="cross",
    )
    
    with pytest.raises(ValueError, match="Only MAIN account is allowed"):
        AccountGuard(invalid_config)
