package com.metaverse.coin.strategy.common.base.constants;

import com.metaverse.coin.strategy.common.base.domain.InDTO;
import com.metaverse.coin.strategy.common.base.utils.ConvertUtils;
import com.metaverse.coin.strategy.common.base.utils.RequestUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

@Slf4j
@Getter
@AllArgsConstructor
public enum BusinessErrEnum {

    LOGIN_HAS_EXPIRED(10000, ConvertUtils.beanToMap(new InDTO("登录已过期", "登錄已過期", "Login expired", "ログイン期限切れ"))),
    PARAMETER_IS_INCORRECT(10001, ConvertUtils.beanToMap(new InDTO("参数不正确", "參數不正確", "The parameter is incorrect", "パラメータが正しくありません"))),
    NOT_LOGIN_TOKEN(401, ConvertUtils.beanToMap(new InDTO("用户未登录", "用戶未登錄", "User not logged in", "ユーザーが未登録です"))),
    OLD_PASSWORD_IS_ABNORMAL(10004, ConvertUtils.beanToMap(new InDTO("旧密码不正确！", "舊密碼不正確", "Incorrect old password", "古いパスワードが正しくありません"))),
    NFT_NOT_EXIST(10005, ConvertUtils.beanToMap(new InDTO("nft信息不存在！", "nft信息不存在", "nft info does not exist", "nft の情報が存在しません"))),
    BLINDBOX_NOT_EXIST(10006, ConvertUtils.beanToMap(new InDTO("盲盒信息不存在！", "盲盒信息不存在", "Blind box information does not exist", "ブラインドボックスの情報が存在しません"))),
    NFT_NOT_SELL(10007, ConvertUtils.beanToMap(new InDTO("NFT非售卖状态！", "NFT非售賣狀態", "NFT non-sale status", "NFT非売品状態"))),
    LOGIN_TOKEN_ERROR(1008, ConvertUtils.beanToMap(new InDTO("登录校验失败", "登錄校驗失敗", "Login verification failed", "ログイン認証が失敗しました"))),
    NFT_METADATE_NOT_EXIST(10009, ConvertUtils.beanToMap(new InDTO("nft元数据不存在！", "nft元數據不存在", "nft metadata does not exist", "nft 元データが存在しません"))),
    NFT_TRADING(100010, ConvertUtils.beanToMap(new InDTO("nft交易中！", "nft交易中", "nft transaction", "nft 取引中"))),
    BLIND_BOX_OVERSELL(100011, ConvertUtils.beanToMap(new InDTO("该盲盒告罄", "該盲盒告罄", "The blind box is sold out", "ブラインドボックスは売れ切りです"))),
    ILLEGAL_OPERATION_BLIND_BOX_CANNOT_BE_OPEN(100012, ConvertUtils.beanToMap(new InDTO("非本人盲盒,无法开启", "非本人盲盒,無法開啟", "Non-personal blind box, cannot be opened", "本人しかブラインドボックスを開けません"))),
    BLIND_BOX_STYLE_NOT_EXISTED(100013, ConvertUtils.beanToMap(new InDTO("盲盒样式不存在", "盲盒樣式不存在", "Blind box style does not exist", "ブラインド ボックスのスタイルが存在しません"))),
    ALREADY_COLLECTED(100014, ConvertUtils.beanToMap(new InDTO("已关注/收藏,请勿重复提交", "已關注/收藏,請勿重複提交", "Followed/Favorites, please do not submit again", "フォロー済み/お気に入り、再送信しないでください"))),
    USER_NOT_COLLECTED(100015, ConvertUtils.beanToMap(new InDTO("未收藏,无法取消收藏", "未收藏,無法取消收藏", "Not Favorite, Can't Unfavorite", "お気に入りに未追加、お気に入りから削除することはできません"))),
    REPEAT_SUBMIT(100016, ConvertUtils.beanToMap(new InDTO("请勿重复提交", "請勿重複提交", "Do not resubmit", "再送信しないでください"))),
    OPERATING_FREQUENCY(100017, ConvertUtils.beanToMap(new InDTO("操作频繁，请稍后再试", "操作頻繁，請稍後再試", "Frequent operations, please try again later", "頻繁な操作で、しばらくしてからもう一度お試しください"))),
    SYSTEM_BUSY(100018, ConvertUtils.beanToMap(new InDTO("系统频繁，请稍后再试", "系統頻繁，請稍後再試", "System is frequent, please try again later", "システムが頻繁にアクセスされて、しばらくしてからもう一度お試しください"))),
    ILLEGAL_REQUEST(100019, ConvertUtils.beanToMap(new InDTO("非法请求", "非法請求", "illegal request", "違法請求"))),
    USER_EXIST(100020, ConvertUtils.beanToMap(new InDTO("用户已存在！", "用戶已存在", "user already exists", "このユーザーは既に存在します"))),
    DATA_NOT_EXIST(100021, ConvertUtils.beanToMap(new InDTO("更新数据不存在！", "更新數據不存在", "Update data does not exist", "更新データが存在しません"))),

    MISSING_CAPTCHA(20000, ConvertUtils.beanToMap(new InDTO("未输入验证码", "未輸入驗證碼", "No verification code entered", "確認コードが入力されていませんでした"))),
    CAPTCHA_OVERDUE(100020, ConvertUtils.beanToMap(new InDTO("验证码已过期,请刷新", "驗證碼已過期,請刷新", "The verification code has expired, please refresh", "確認コードの有効期限が切れて、更新してください"))),
    CAPTCHA_ERROR(100021, ConvertUtils.beanToMap(new InDTO("验证码错误", "驗證碼錯誤", "Verification code error", "確認コードが正しくありません"))),
    USERNAME_OR_PASSWORD_NULL(100022, ConvertUtils.beanToMap(new InDTO("请填写账户或者密码", "請填寫賬戶或者密碼", "Please fill in the account or password", "アカウントまたはパスワードを入力してください"))),
    USERNAME_OR_PASSWORD_ERROR(100023, ConvertUtils.beanToMap(new InDTO("用户账户或者密码错误", "用戶賬戶或者密碼錯誤", "User account or password incorrect", "ユーザーアカウントまたはパスワードが正しくありません"))),
    TOKEN_ERROR(100024, ConvertUtils.beanToMap(new InDTO("无效TOKEN", "無效TOKEN", "Invalid TOKEN", "無効なトークン"))),


    BIND_USER_NOT_EXIST(300001, ConvertUtils.beanToMap(new InDTO("绑定用户不存在！", "綁定用戶不存在", "bind user does not exist", "バンディングのユーザーが存在しません"))),
    BLIND_BOX_CONFIG_NOT_EXIST(300002, ConvertUtils.beanToMap(new InDTO("盲盒配置不存在！", "盲盒配置不存在", "Blind box configuration does not exist", "ブラインド ボックスが配置されていません"))),
    BIND_INVITE_REPEAT(300003, ConvertUtils.beanToMap(new InDTO("已绑定邀请人，请勿重复绑定", "已綁定邀請人，請勿重複綁定", "The inviter has been bound, please do not bind again", "招待人とバンディング済みで、再度バインドしないでください"))),
    BLIND_BOX_NOT_EXIST(300004, ConvertUtils.beanToMap(new InDTO("盲盒不存在", "盲盒不存在", "Blind box does not exist", "ブラインドボックスが存在しません"))),
    NFT_STYLE_NOT_EXIST(300005, ConvertUtils.beanToMap(new InDTO("款式不存在", "款式不存在", "style does not exist", "スタイルが存在しません"))),
    ACTIVITY_NOT_EXIST(300006, ConvertUtils.beanToMap(new InDTO("nft活动不存在", "nft活動不存在", "nft activity does not exist", "nft イベントが存在しません"))),
    ACTIVITY_SELL_OUT(300007, ConvertUtils.beanToMap(new InDTO("活动已售罄", "活動已售罄", "Event is sold out", "イベントはセールアウトです"))),
    ACTIVITY_INADEQUATE(300008, ConvertUtils.beanToMap(new InDTO("活动数量不足", "活動數量不足", "Not enough events", "イベントの数が足りません"))),
    SUBSCRIBE_ERROR(300009, ConvertUtils.beanToMap(new InDTO("网络波动,请稍后再试", "網絡波動,請稍後再試", "Network fluctuates, please try again later", "ネットワークが不安定で、しばらくしてからもう一度お試しください"))),
    LIMIT_BUY_NUMBER(3000010, ConvertUtils.beanToMap((new InDTO("购买失败，已购买%d个,还可购买%d个", "購買失敗，已購買%d個,還可購買%d個",
            "Purchase failed, %d have been purchased, and %d can still be purchased", "購入に失敗しました。%d 個購入済みで、あと %d 個購入できます")))),

    STYLE_NOT_EXIST(3000011, ConvertUtils.beanToMap(new InDTO("款式数据不存在！", "款式數據不存在", "Style data does not exist", "スタイル データが存在しません"))),
    STYLE_REPEATED(3000011, ConvertUtils.beanToMap(new InDTO("铸造款式已存在！", "鑄造款式已存在", "Cast style already exists", "キャスティングスタイルが既に存在します"))),

    ACTIVITY_STYLE_REPEATED(3000012, ConvertUtils.beanToMap(new InDTO("对应款式活动已存在！", "對應款式活動已存在", "The corresponding style activity already exists", "対応スタイルのイベントが既に存在します"))),
    NFT_FORGING_NOT_EXIST(3000013, ConvertUtils.beanToMap(new InDTO("铸造数据不存在！", "鑄造數據不存在", "casting data does not exist", "キャスティングデータが存在しません"))),
    NFT_FORGING_NOT_START(3000014, ConvertUtils.beanToMap(new InDTO("款式未开始铸造！", "款式未開始鑄造", "Model has not started casting", "スタイルキャスティングはまだ開始していません"))),
    ACTIVITY_PUBLISH_NUMBER_EXCESS(3000015, ConvertUtils.beanToMap(new InDTO("活动数据大于铸造数量！", "活動數據大於鑄造數量", "Active data is greater than mint purchaseCount", "イベント数はキャスティング数を超えています"))),
    NFT_FORGING_MINTING(3000016, ConvertUtils.beanToMap(new InDTO("款式铸造中！", "款式鑄造中", "Model casting", "スタイルキャスティング中"))),
    NFT_FORGING_PEND_ORDER(3000017, ConvertUtils.beanToMap(new InDTO("款式已挂单！", "款式已掛單", "The style has been placed", "スタイルが注文されました"))),
    BLIND_BOX_REWARD_NOT_ENOUGH(300018, ConvertUtils.beanToMap(new InDTO("邀请奖励盲盒的数量不足，请联系官方人员！", "邀請獎勵盲盒的數量不足，請聯繫官方人員", "Insufficient number of invitation reward blind boxes, please contact official staff", "招待奨励のブラインドボックスの数が不足で、公式スタッフにお問い合わせください"))),
    BLIND_BOX_REWARD_SEND_FAIL(300019, ConvertUtils.beanToMap(new InDTO("邀请奖励盲盒发放失败，请联系官方人员！", "邀請獎勵盲盒發放失敗，請聯繫官方人員", "Invitation reward blind box distribution failed, please contact official staff", "招待奨励のブラインドボックスが配布失敗で、公式スタッフにお問い合わせください"))),
    TRANSFER_ERROR(300020, ConvertUtils.beanToMap(new InDTO("转账异常！", "轉賬異常", "abnormal transfer", "振替異常"))),
    ACTIVITY_NOT_PENDING(300021, ConvertUtils.beanToMap(new InDTO("nft活动未挂单或挂单数量不足", "nft活動未掛單或挂單數量不足", "No pending order for nft event or order's count is not enough", "nftイベントの未決注文なし"))),

    OPER_ERROR(300022, ConvertUtils.beanToMap(new InDTO("操作失败", "操作失敗", "operation failed", "操作失敗"))),
    REMARK_IS_NULL_ERROR(300023, ConvertUtils.beanToMap(new InDTO("驳回请输入相关备注", "駁回請輸入相關備註", "Please enter relevant remarks for rejection", "関連するコメントを入力して拒否します"))),
    ENUM_NOT_EXIST_ERROR(300024, ConvertUtils.beanToMap(new InDTO("枚举值不存在!", "枚舉值不存在", "enum value does not exist", "列挙値が存在しません"))),
    AUDIT_STATE_EXIST(300025, ConvertUtils.beanToMap(new InDTO("已审核,不可操作", "已審核,不可操作", "Reviewed, inoperable", "審査済み、操作不可"))),
    CONFIG_NOT_EXIST(3000026, ConvertUtils.beanToMap(new InDTO("配置项不存在!", "配置項不存在", "Configuration item does not exist", "配置アイテムが存在しません"))),
    BALANCE_LACK_EXIST(3000027, ConvertUtils.beanToMap(new InDTO("余额不足,请充值", "餘額不足,請充值", "Insufficient balance, please recharge", "残高が不足で、チャージしてください"))),
    AUDIT_REJECT_ERROR(300028, ConvertUtils.beanToMap(new InDTO("已驳回,不可操作", "已駁回,不可操作", "rejected, invalid procedure", "拒否された、操作不可"))),

    IN_IS_NULL(300029, ConvertUtils.beanToMap(new InDTO("国际化参数不能为空", "國際化參數不能為空", "Internationalization parameter cannot be empty", "インターナショナルパラメータを空にすることはできません"))),
    ILLEGAL_SIGNATURE_DATA(300030, ConvertUtils.beanToMap(new InDTO("非法签名数据", "非法簽名數據", "illegal signature data", "不正な署名データ"))),
    ILLEGAL_ETH_ADDRESS(300031, ConvertUtils.beanToMap(new InDTO("非法eth地址", "非法eth地址", "illegal eth address", "不正な eth アドレス"))),
    USER_NOT_EXIST(300032, ConvertUtils.beanToMap(new InDTO("用户不存在", "用戶不存在", "User does not exist", "ユーザーは存在しません"))),
    INVALID_ETH_SIGNATURE_DATA(300033, ConvertUtils.beanToMap(new InDTO("无效的eth签名数据", "無效的eth簽名數據", "Invalid eth signature data", "無効な eth 署名データ"))),
    TOKEN_IS_NULL(300034, ConvertUtils.beanToMap(new InDTO("令牌为空", "令牌為空", "token is empty", "トークンが無い"))),
    NVITECODE_IS_NULL(300035, ConvertUtils.beanToMap(new InDTO("邀请码为空", "邀請碼為空", "Invitation code is empty", "招待コードが無い"))),
    TODAY_GET_MAX_LIMIT(300036, ConvertUtils.beanToMap(new InDTO("今日领取已达上限", "今日領取已達上限", "Receipt limit reached today", "本日受け取り上限に達しました"))),
    USER_IS_NULL(300037, ConvertUtils.beanToMap(new InDTO("返佣用户不存在", "返佣用戶不存在", "The rebate user does not exist", "リベートユーザーが存在しません"))),
    GET_AMOUNT_EROOR(300038, ConvertUtils.beanToMap(new InDTO("领取失败", "領取失敗", "Failed to claim", "受領失敗"))),
    MIX_GET_LIMIT_EROOR(300039, ConvertUtils.beanToMap(new InDTO("最少领取:[%s]", "最少領取", "Minimum pick up", "最小受け取り"))),
    MAX_GET_LIMIT_EROOR(300040, ConvertUtils.beanToMap(new InDTO("最大领取:[%s]", "最大領取", "Maximum claim", "最大受け取り"))),
    LIST_ERROR(300041, ConvertUtils.beanToMap(new InDTO("样式id为%d的物品可上架数量为%d,少于上架数量%d，上架失败", "樣式id為 %d 的物品可上架數量為%d,少於上架數量%d，上架失敗",
            "The item whose style id is %d can be listed as %d, if it is less than the listed quantity %d, the listing fails", "スタイル ID が %d のアイテムはオンラインできる数は %d、 オンライン数量 %d 未満の場合、失敗となります。"))),
    BLIND_BOX_CONFIG_ERROR(300042, ConvertUtils.beanToMap(new InDTO("盲盒配置失败", "盲盒配置失敗", "Blind box configuration failed", "ブラインド ボックスの配置に失敗しました"))),
    INSUFFICIENT_BALANCE(300043, ConvertUtils.beanToMap(new InDTO("余额不足", "餘額不足", "Insufficient balance", "残高が不足で"))),
    CAN_NOT_BIND_SELF_OR_JUNIOR(300044, ConvertUtils.beanToMap(new InDTO("不允许绑定自己和自己的下级！", "不允許綁定自己和自己的下級", "Binding yourself and junior is not allowed ", "バインディングジュニアは許可されていません"))),
    CAN_NOT_OFF_SALE(300045, ConvertUtils.beanToMap(new InDTO("未上架或上架失败的活动无法下架", "未上架或上架失败的活動無法下架", "Can not off list activity whice is await or fail", "待機中または失敗したアクティビティをリストから外すことはできません"))),
    CAN_NOT_SALE(300046, ConvertUtils.beanToMap(new InDTO("上架中或已上架的活动无法上架", "上架中或已上架的活動無法上架", "Can not list activity whice is pending or listed", "保留中またはリストされている活動はリストできません"))),
    NOT_APPROVAL(300047, ConvertUtils.beanToMap(new InDTO("款式未授权","款式未授權","not approval style","無許可のスタイル"))),
    ACTIVITY_UNSALE(300048, ConvertUtils.beanToMap(new InDTO("活动已下架", "活動已下架", "activity has been taken down", "イベントは削除されました"))),
    ACTIVITY_PROCEED(300049, ConvertUtils.beanToMap(new InDTO("活动进行中，无法删除款式", "活動進行中，無法刪除款式", "activity is proceed, can't delete style", "イベントは進行中です。スタイルは削除できません"))),
    UPPER_LIMIT(300050, ConvertUtils.beanToMap(new InDTO("您添加好友数量已经到达上限，在官网邀请好友进行绑定可以增加好友数量上限", "您添加好友數量已經到達上限，在官網邀請好友進行綁定可以增加好友數量上限", "The number of friends you have added has reached the upper limit. Inviting friends to bind on the official website can increase the upper limit of friends", "追加したフレンドの数が上限に達しました。公式サイトでフレンドをバインドに招待すると、フレンドの上限を増やすことができます"))),
    FRIENDS_UPPER_LIMIT(300051, ConvertUtils.beanToMap(new InDTO("该用户的好友数量已经到达上限，请您换一位用户添加", "該用戶的好友數量已經到達上限，請您換一位用戶添加", "The number of friends of this user has reached the upper limit, please add another user", "このユーザーのフレンド数が上限に達しました。別のユーザーを追加してください"))),
    ALREADY_FRIENDS(300052, ConvertUtils.beanToMap(new InDTO("你们已是好友，不可再次添加", "你們已是好友，不可再次添加", "You are already friends and cannot be added again", "あなたはすでに友達なので、再度追加することはできません"))),
    IN_BLACKLIST(300053, ConvertUtils.beanToMap(new InDTO("添加失败，您被拉黑或该用户已在黑名单中", "添加失敗，您被拉黑或該用戶已在黑名單中", "Failed to add, you have been blocked or the user is already in the blacklist", "追加できませんでした。ブロックされているか、ユーザーが既にブラックリストに登録されています"))),

    ;


    private Integer value;
    private Map<String, Object> inMsgMap;

    /**
     * 获取国际化错误msg,默认中文
     *
     * @param errEnum 错误枚举
     * @return
     */
    public static String getMsgByIn(BusinessErrEnum errEnum) {
        String in = RequestUtils.getHeaderIn();
        if (StringUtils.isBlank(in)) {
            //默认中文  {@link InDTO}字段名
            in = "zh";
        }
        return (String) errEnum.getInMsgMap().get(in);
    }

    /**
     * 根据 inDTO 转map<inDTO字段名,国际化数据>
     *
     * @param inDTO
     * @return
     */
    public static String getMsgByIn(InDTO inDTO) {
        String in = RequestUtils.getHeaderIn();
        if (StringUtils.isBlank(in)) {
            //默认中文  {@link InDTO}字段名
            in = "zh";
        }
        Map<String, String> map = ConvertUtils.beanToMap(inDTO);
        return map.getOrDefault(in, "");
    }

}
