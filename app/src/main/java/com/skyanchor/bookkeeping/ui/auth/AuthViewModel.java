package com.skyanchor.bookkeeping.ui.auth;

import android.app.Application;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.data.repository.AuthRepository;
import com.skyanchor.bookkeeping.util.Callback;

/**
 * 认证 ViewModel：登录 / 注册 / 重发验证邮件。
 * 错误文案来自服务端（已人类可读），客户端不再转译一层（基线第 33 章）。
 */
public class AuthViewModel extends AndroidViewModel {

    private final AuthRepository authRepository;

    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> loginSuccess = new MutableLiveData<>();

    public AuthViewModel(@NonNull Application application) {
        super(application);
        authRepository = BookkeepingApp.get(application).getAuthRepository();
    }

    public LiveData<Boolean> busy() {
        return busy;
    }

    public LiveData<Boolean> loginSuccess() {
        return loginSuccess;
    }

    public boolean isLoggedIn() {
        return authRepository.isLoggedIn();
    }

    public boolean isEmailVerified() {
        return authRepository.isEmailVerified();
    }

    public String accountEmail() {
        return authRepository.getAccountEmail();
    }

    /** 登录：成功回调 onResult(response)，失败回调 onError（文案人类可读）。 */
    public void login(@NonNull String email, @NonNull String password,
                      @NonNull Callback<ApiDtos.AuthResponse> callback) {
        if (isBusy()) {
            return;
        }
        busy.setValue(true);
        authRepository.login(email, password, Build.MODEL, versionName(),
                new Callback<ApiDtos.AuthResponse>() {
                    @Override
                    public void onResult(ApiDtos.AuthResponse result) {
                        busy.postValue(false);
                        loginSuccess.postValue(true);
                        callback.onResult(result);
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        busy.postValue(false);
                        callback.onError(e);
                    }
                });
    }

    /** 注册：成功后进入「验证邮件已发送」状态。 */
    public void register(@NonNull String email, @NonNull String password,
                         @NonNull Callback<Boolean> callback) {
        if (isBusy()) {
            return;
        }
        busy.setValue(true);
        authRepository.register(email, password, new Callback<Boolean>() {
            @Override
            public void onResult(Boolean result) {
                busy.postValue(false);
                callback.onResult(result);
            }

            @Override
            public void onError(@NonNull Exception e) {
                busy.postValue(false);
                callback.onError(e);
            }
        });
    }

    public void resendVerification(@NonNull String email, @NonNull Callback<Boolean> callback) {
        authRepository.resendVerification(email, callback);
    }

    private boolean isBusy() {
        Boolean value = busy.getValue();
        return value != null && value;
    }

    private String versionName() {
        try {
            return getApplication().getPackageManager()
                    .getPackageInfo(getApplication().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
