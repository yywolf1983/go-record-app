#!/bin/bash

# 构建和运行脚本

echo "围棋打谱应用构建脚本"
echo "===================="

# 显示帮助信息
show_help() {
    echo "用法: ./build.sh [命令]"
    echo ""
    echo "命令:"
    echo "  build       构建项目"
    echo "  install     安装到设备"
    echo "  run         构建并运行"
    echo "  clean       清理构建文件"
    echo "  devices     查看连接的设备"
    echo "  logcat      查看日志"
    echo "  help        显示此帮助信息"
}

# 构建项目
build_project() {
    echo "正在构建项目..."
    ./gradlew assembleDebug
    if [ $? -eq 0 ]; then
        echo "构建成功!"
    else
        echo "构建失败!"
        exit 1
    fi
}

# 安装到设备
install_app() {
    echo "正在安装到设备..."
    ./gradlew installDebug
    if [ $? -eq 0 ]; then
        echo "安装成功!"
    else
        echo "安装失败!"
        exit 1
    fi
}

# 运行应用
run_app() {
    echo "正在构建并运行应用..."
    ./gradlew installDebug
    if [ $? -eq 0 ]; then
        echo "安装成功，正在启动应用..."
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

    case "$1" in
        build)
            build_project
            ;;
        install)
            install_app
            ;;
        run)
            run_app
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
            echo "未知命令: $1"
            show_help
            exit 1
            ;;
    esac
}

# 执行主函数
main "$@"
