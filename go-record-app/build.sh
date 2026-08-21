#!/bin/bash

# 构建和运行脚本

echo "围棋打谱应用构建脚本"
echo "===================="

# 显示帮助信息
show_help() {
    echo "用法: ./build.sh [命令] [abi]"
    echo ""
    echo "命令:"
    echo "  build       构建项目 (默认产出 app-debug.apk)"
    echo "  install     构建并安装到设备"
    echo "  run         构建、安装并启动应用"
    echo "  clean       清理构建文件"
    echo "  devices     查看连接的设备"
    echo "  logcat      查看日志"
    echo "  help        显示此帮助信息"
    echo ""
    echo "abi (可选): arm (默认, 真机 arm64-v8a) | x86 (模拟器 x86_64)"
    echo "  不传 abi 时产出统一 APK: app/build/outputs/apk/debug/app-debug.apk"
    echo "  传 abi 时通过 -Pabi=xxx 传给 Gradle，由其切换 abiFilters"
}

# 解析 ABI 参数（仅校验合法性，不强制默认值）
resolve_abi() {
    local abi="$1"
    if [ -z "$abi" ]; then
        echo ""
        return
    fi
    case "$abi" in
        arm|a) echo "arm" ;;
        x86|i386|x86_64) echo "x86" ;;
        *) echo "未知 abi: $abi" >&2; exit 1 ;;
    esac
}

# 返回对应 APK 路径
apk_path() {
    local abi="$1"
    # 统一产出 app-debug.apk，不再按 ABI 分目录
    echo "app/build/outputs/apk/debug/app-debug.apk"
}

# 复制构建产物到备份路径
copy_apk() {
    local apk="$1"
    local backup_dir="/Users/yy/Desktop/back/mapk"
    mkdir -p "$backup_dir"
    cp "$apk" "$backup_dir/go.apk"
    echo "已复制 APK 到 ${backup_dir}/go.apk"
}

# 构建项目
build_project() {
    local abi
    abi=$(resolve_abi "$1")
    local abi_arg=""
    if [ -n "$abi" ]; then
        abi_arg="-Pabi=${abi}"
    fi
    echo "正在构建项目 (abi=${abi:-默认 arm64-v8a})..."
    ./gradlew assembleDebug $abi_arg
    if [ $? -eq 0 ]; then
        local apk
        apk=$(apk_path "$abi")
        echo "构建成功: $apk"
        copy_apk "$apk"
    else
        echo "构建失败!"
        exit 1
    fi
}

# 安装到设备
install_app() {
    local abi
    abi=$(resolve_abi "$1")
    # 始终先重新编译，保证安装的是最新代码
    build_project "$abi"
    local apk
    apk=$(apk_path "$abi")
    echo "正在安装到设备 (abi=${abi:-默认 arm64-v8a})..."
    adb install -r "$apk"
    if [ $? -eq 0 ]; then
        echo "安装成功!"
    else
        echo "安装失败!"
        exit 1
    fi
}

# 运行应用
run_app() {
    local abi
    abi=$(resolve_abi "$1")
    echo "正在构建并运行应用 (abi=${abi:-默认 arm64-v8a})..."
    install_app "$abi"
    if [ $? -eq 0 ]; then
        echo "正在启动应用..."
        adb shell am start -n com.gosgf.app/.MainActivity
    else
        echo "安装失败!"
        exit 1
    fi
}

# 清理构建文件
clean_project() {
    echo "正在清理构建文件..."
    ./gradlew clean
    if [ $? -eq 0 ]; then
        echo "清理成功!"
    else
        echo "清理失败!"
        exit 1
    fi
}

# 查看连接的设备
list_devices() {
    echo "连接的设备:"
    adb devices
}

# 查看日志
show_logs() {
    echo "查看日志..."
    adb logcat | grep com.gosgf.app
}

# 主函数
main() {
    if [ $# -eq 0 ]; then
        show_help
        exit 0
    fi

    local cmd="$1"
    local arg="$2"

    case "$cmd" in
        build)
            build_project "$arg"
            ;;
        install)
            install_app "$arg"
            ;;
        run)
            run_app "$arg"
            ;;
        clean)
            clean_project
            ;;
        devices)
            list_devices
            ;;
        logcat)
            show_logs
            ;;
        help)
            show_help
            ;;
        *)
            echo "未知命令: $cmd"
            show_help
            exit 1
            ;;
    esac
}

# 执行主函数
main "$@"
