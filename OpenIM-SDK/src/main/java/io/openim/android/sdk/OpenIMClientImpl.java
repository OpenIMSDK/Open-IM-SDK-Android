package io.openim.android.sdk;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;

import java.util.List;
import java.util.Map;

import io.openim.android.sdk.internal.Releasable;
import io.openim.android.sdk.internal.log.LogcatHelper;
import io.openim.android.sdk.internal.schedule.Schedulers;
import io.openim.android.sdk.listener.BaseImpl;
import io.openim.android.sdk.listener.ConnectListener;
import io.openim.android.sdk.listener.InitCallback;
import io.openim.android.sdk.listener.InitSDKListener;
import io.openim.android.sdk.listener.OnBase;
import io.openim.android.sdk.listener.UserStateChangedListener;
import io.openim.android.sdk.manager.ConversationManager;
import io.openim.android.sdk.manager.FriendshipManager;
import io.openim.android.sdk.manager.GroupManager;
import io.openim.android.sdk.manager.MessageManager;
import io.openim.android.sdk.models.UserInfo;
import io.openim.android.sdk.user.Credential;
import io.openim.android.sdk.util.CollectionUtils;
import io.openim.android.sdk.util.JsonUtil;
import io.openim.android.sdk.util.Predicates;
import open_im_sdk.Open_im_sdk;

/**
 * Internal client impl
 * <p/>
 * Access from {@link OpenIMClient} only
 * <p/>
 * Created by alvince on 2021/9/24
 *
 * @author alvince.zy@gmail.com
 */
class OpenIMClientImpl implements Releasable {

    ConversationManager conversationManager;
    FriendshipManager friendshipManager;
    GroupManager groupManager;
    MessageManager messageManager;

    private ListenerInfo mListenerInfo;

    OpenIMClientImpl() {
        conversationManager = new ConversationManager();
        friendshipManager = new FriendshipManager();
        groupManager = new GroupManager();
        messageManager = new MessageManager();
    }

    /**
     * Release and un-init SDK
     */
    @Override
    public void release() {
        LogcatHelper.logDInDebug("Release and un-init sdk.");
        Open_im_sdk.unInitSDK();
        if (Predicates.nonNull(mListenerInfo)) {
            mListenerInfo.release();
        }
    }

    /**
     * 初始化sdk
     * 注：在创建图片，语音，视频，文件等需要路径参数的消息体时，
     * 如果选择的是非全路径方法如：createSoundMessage（全路径方法为：createSoundMessageFromFullPath）,
     * 需要将文件自行拷贝到dbPath目录下，如果此时文件路径为 apath+"/sound/a.mp3"，则参数path的值为：/sound/a.mp3。
     * 如果选择的全路径方法，路径为你文件的实际路径不需要再拷贝。
     *
     * @param apiUrl     SDK的API接口地址。如：http:xxx:10000
     * @param wsUrl      SDK的web socket地址。如： ws:xxx:17778
     * @param storageDir 数据存储目录路径
     * @param listener   SDK初始化监听
     */
    boolean initSDK(String apiUrl, String wsUrl, String storageDir, InitSDKListener listener) {
        // fastjson is unreliable, should instead with google/gson in android
        String paramsText = JsonUtil.toString(CollectionUtils.simpleMapOf("platform", 2, "ipApi", apiUrl, "ipWs", wsUrl, "dbDir", storageDir));
        LogcatHelper.logDInDebug(String.format("init config: %s", paramsText));
        return Open_im_sdk.initSDK(paramsText, new open_im_sdk.IMSDKListener() {
            @Override
            public void onConnectFailed(long l, String s) {
                if (null != listener) {
                    Schedulers.runOnMainThread(() -> listener.onConnectFailed(l, s));
                }
            }

            @Override
            public void onConnectSuccess() {
                if (null != listener) {
                    Schedulers.runOnMainThread(listener::onConnectSuccess);
                }
            }

            @Override
            public void onConnecting() {
                if (null != listener) {
                    Schedulers.runOnMainThread(listener::onConnecting);
                }
            }

            @Override
            public void onKickedOffline() {
                if (null != listener) {
                    Schedulers.runOnMainThread(listener::onKickedOffline);
                }
            }

            @Override
            public void onSelfInfoUpdated(String s) {
                if (null != listener) {
                    Schedulers.runOnMainThread(() -> listener.onSelfInfoUpdated(JsonUtil.toObj(s, UserInfo.class)));
                }
            }

            @Override
            public void onUserTokenExpired() {
                if (null != listener) {
                    Schedulers.runOnMainThread(listener::onUserTokenExpired);
                }
            }
        });
    }

    void init(@NonNull OpenIMConfig config, @NonNull InitCallback callback) {
        String conf = Predicates.requireNonNull(config).toJson();
        if (TextUtils.isEmpty(conf)) {
            RuntimeException err = new IllegalArgumentException("Invalid config json: empty.");
            Predicates.requireNonNull(callback).onFailed(err);
            return;
        }
        if (Open_im_sdk.initSDK(conf, createCoreListener())) {
            Predicates.requireNonNull(callback).onSucceed();
            return;
        }
        Predicates.requireNonNull(callback).onFailed(new IllegalStateException("Unknown error while init sdk."));
    }

    void login(@NonNull Credential credential, @Nullable OnBase<String> callback) {
        Predicates.requireNonNull(credential);

        String uid = credential.getUid();
        String token = credential.getToken();
        if (TextUtils.isEmpty(uid) || TextUtils.isEmpty(token)) {
            if (Predicates.nonNull(callback)) {
                // FIXME: give correct error-code for invalid credential
                callback.onError(-1, "Empty uid or token.");
            }
            return;
        }
        Open_im_sdk.login(uid, token, BaseImpl.stringBase(callback));
    }

    void login(OnBase<String> base, Credential credential) {
        Open_im_sdk.login(
            Predicates.checkParamValue("uid", Predicates.requireNonNull(credential).getUid()),
            Predicates.checkParamValue("token", Predicates.requireNonNull(credential).getToken()),
            BaseImpl.stringBase(base)
        );
    }

    /**
     * 登出
     */
    void logout(OnBase<String> base) {
        Open_im_sdk.logout(BaseImpl.stringBase(base));
    }

    /**
     * 查询登录状态
     */
    long getLoginStatus() {
        return Open_im_sdk.getLoginStatus();
    }

    /**
     * 当前登录uid
     */
    String getLoginUid() {
        return Open_im_sdk.getLoginUid();
    }

    /**
     * 根据uid 批量查询用户信息
     *
     * @param uidList 用户id列表
     * @param base    callback List<{@link UserInfo}>
     */
    void getUsersInfo(OnBase<List<UserInfo>> base, List<String> uidList) {
        Open_im_sdk.getUsersInfo(JsonUtil.toString(uidList), BaseImpl.arrayBase(base, UserInfo.class));
    }

    /**
     * 修改资料
     *
     * @param name   名字
     * @param icon   头像
     * @param gender 性别
     * @param mobile 手机号
     * @param birth  出生日期
     * @param email  邮箱
     * @param base   callback String
     */
    void setSelfInfo(OnBase<String> base, String name, String icon, int gender, String mobile, String birth, String email) {
        Map<String, Object> map = new ArrayMap<>();
        map.put("name", name);
        map.put("icon", icon);
        map.put("gender", gender);
        map.put("mobile", mobile);
        map.put("birth", birth);
        map.put("email", email);
        Open_im_sdk.setSelfInfo(JsonUtil.toString(map), BaseImpl.stringBase(base));
    }

    void forceSyncLoginUerInfo() {
        Open_im_sdk.forceSyncLoginUerInfo();
    }

    void forceReConn() {
        Open_im_sdk.forceReConn();
    }

    void registerConnListener(@NonNull ConnectListener listener) {
        Predicates.requireNonNull(listener);

        @NonNull ListenerInfo listenerInfo = getListenerInfo();
        if (listenerInfo.connListener == listener) {
            return;
        }
        listenerInfo.connListener = listener;
    }

    void unregisterConnListener(@Nullable ConnectListener listener) {
        if (Predicates.isNull(listener)) {
            return;
        }
        @NonNull ListenerInfo listenerInfo = getListenerInfo();
        if (listenerInfo.connListener == listener) {
            listenerInfo.connListener = null;
        }
    }

    void registerUserChangedListener(@NonNull UserStateChangedListener listener) {
        Predicates.requireNonNull(listener);

        @NonNull ListenerInfo listenerInfo = getListenerInfo();
        if (listenerInfo.userStateListener == listener) {
            return;
        }
        listenerInfo.userStateListener = listener;
    }

    void unregisterUserChangedListener(@Nullable UserStateChangedListener listener) {
        if (Predicates.isNull(listener)) {
            return;
        }
        @NonNull ListenerInfo listenerInfo = getListenerInfo();
        if (listenerInfo.userStateListener == listener) {
            listenerInfo.userStateListener = null;
        }
    }

    @NonNull
    synchronized ListenerInfo getListenerInfo() {
        if (mListenerInfo != null) {
            return mListenerInfo;
        }
        mListenerInfo = new ListenerInfo();
        return mListenerInfo;
    }

    private open_im_sdk.IMSDKListener createCoreListener() {
        return new open_im_sdk.IMSDKListener() {
            @Override
            public void onConnectFailed(long code, String s) {
                final ConnectListener l = getListenerInfo().connListener;
                if (Predicates.nonNull(l)) {
                    Schedulers.runOnMainThread(() -> l.onConnectFailed(code, s));
                }
            }

            @Override
            public void onConnectSuccess() {
                final ConnectListener l = getListenerInfo().connListener;
                if (Predicates.nonNull(l)) {
                    Schedulers.runOnMainThread(l::onConnectSuccess);
                }
            }

            @Override
            public void onConnecting() {
                final ConnectListener l = getListenerInfo().connListener;
                if (Predicates.nonNull(l)) {
                    Schedulers.runOnMainThread(l::onConnecting);
                }
            }

            @Override
            public void onKickedOffline() {
                final ConnectListener l = getListenerInfo().connListener;
                if (Predicates.nonNull(l)) {
                    Schedulers.runOnMainThread(l::onKickedOffline);
                }
            }

            @Override
            public void onSelfInfoUpdated(String s) {
                final UserStateChangedListener l = getListenerInfo().userStateListener;
                if (Predicates.nonNull(l)) {
                    Schedulers.runOnMainThread(() -> l.onSelfProfileUpdated(JsonUtil.toObj(s, UserInfo.class)));
                }
            }

            @Override
            public void onUserTokenExpired() {
                final UserStateChangedListener l = getListenerInfo().userStateListener;
                if (Predicates.nonNull(l)) {
                    Schedulers.runOnMainThread(l::onUserTokenExpired);
                }
            }
        };
    }

    static final class ListenerInfo implements Releasable {
        ListenerInfo() {
        }

        @Nullable
        ConnectListener connListener;

        @Nullable
        UserStateChangedListener userStateListener;

        @Override
        public void release() {
            connListener = null;
            userStateListener = null;
        }
    }
}
