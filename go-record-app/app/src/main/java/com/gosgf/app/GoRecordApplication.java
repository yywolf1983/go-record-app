package com.gosgf.app;

import android.app.Application;
import com.reggate.lib.RegGateConfig;

/**
 * 应用入口：集成注册库。
 * 不写死任何策略参数，全部由注册库编译进的 assets/reggate_config.dat 决定。
 */
public class GoRecordApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 生命周期守卫与注册入口按钮在 RegGateConfig.build() 中自动启用，无需手动安装
        RegGateConfig.init(this)
                .mainActivity(MainActivity.class)
                .loadFromConfig()
                .build();
    }
}
