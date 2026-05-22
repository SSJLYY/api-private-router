#!/usr/bin/env bash
#
# api-private-router Java installation script
# Usage: provide this script from your own deployment channel and run it with bash
#

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

GITHUB_REPO="${GITHUB_REPO:-}"
INSTALL_DIR="/opt/api-private-router"
SERVICE_NAME="api-private-router"
SERVICE_USER="api-private-router"
CONFIG_DIR="/etc/api-private-router"
JAVA_BIN="${JAVA_BIN:-/usr/bin/java}"
SERVER_HOST="${SERVER_HOST:-0.0.0.0}"
SERVER_PORT="${SERVER_PORT:-8080}"
DATA_DIR="${DATA_DIR:-$INSTALL_DIR}"
LANG_CHOICE="zh"

declare -A MSG_ZH=(
    ["info"]="信息"
    ["success"]="成功"
    ["warning"]="警告"
    ["error"]="错误"
    ["select_lang"]="请选择语言 / Select language"
    ["lang_zh"]="中文"
    ["lang_en"]="English"
    ["enter_choice"]="请输入选择 (默认: 1)"
    ["install_title"]="api-private-router Java 安装脚本"
    ["run_as_root"]="请使用 root 权限运行 (使用 sudo)"
    ["detected_platform"]="检测到平台"
    ["unsupported_arch"]="不支持的架构"
    ["unsupported_os"]="不支持的操作系统"
    ["missing_deps"]="缺少依赖"
    ["install_deps_first"]="请先安装以下依赖"
    ["fetching_version"]="正在获取最新版本..."
    ["latest_version"]="最新版本"
    ["failed_get_version"]="获取最新版本失败"
    ["downloading"]="正在下载"
    ["download_failed"]="下载失败"
    ["verifying_checksum"]="正在校验文件..."
    ["checksum_verified"]="校验通过"
    ["checksum_failed"]="校验失败"
    ["checksum_not_found"]="无法验证校验和（checksums.txt 未找到）"
    ["extracting"]="正在解压..."
    ["binary_installed"]="应用已安装到"
    ["user_exists"]="用户已存在"
    ["creating_user"]="正在创建系统用户"
    ["user_created"]="用户已创建"
    ["setting_up_dirs"]="正在设置目录..."
    ["dirs_configured"]="目录配置完成"
    ["installing_service"]="正在安装 systemd 服务..."
    ["service_installed"]="systemd 服务已安装"
    ["service_started"]="服务已启动"
    ["service_start_failed"]="服务启动失败，请检查日志"
    ["ready_for_setup"]="已准备好初始化向导"
    ["install_complete"]="api-private-router 安装完成！"
    ["install_dir"]="安装目录"
    ["next_steps"]="后续步骤"
    ["step1_check_services"]="确保 PostgreSQL 和 Redis 已运行："
    ["step2_start_service"]="启动 api-private-router 服务："
    ["step3_enable_autostart"]="设置开机自启："
    ["step4_open_wizard"]="在浏览器中打开初始化向导："
    ["wizard_guide"]="初始化向导将引导您完成："
    ["wizard_db"]="数据库配置"
    ["wizard_redis"]="Redis 配置"
    ["wizard_admin"]="管理员账号创建"
    ["useful_commands"]="常用命令"
    ["cmd_status"]="查看状态"
    ["cmd_logs"]="查看日志"
    ["cmd_restart"]="重启服务"
    ["cmd_stop"]="停止服务"
    ["upgrading"]="正在升级 api-private-router..."
    ["current_version"]="当前版本"
    ["stopping_service"]="正在停止服务..."
    ["backup_created"]="备份已创建"
    ["starting_service"]="正在启动服务..."
    ["upgrade_complete"]="升级完成！"
    ["installing_version"]="正在安装指定版本"
    ["version_not_found"]="指定版本不存在"
    ["same_version"]="已经是该版本，无需操作"
    ["rollback_complete"]="版本回退完成！"
    ["install_version_complete"]="指定版本安装完成！"
    ["validating_version"]="正在校验版本..."
    ["available_versions"]="可用版本列表"
    ["fetching_versions"]="正在获取可用版本..."
    ["not_installed"]="api-private-router 尚未安装，请先执行全新安装"
    ["fresh_install_hint"]="用法"
    ["uninstall_confirm"]="这将从系统中移除 api-private-router。"
    ["are_you_sure"]="确定要继续吗? (y/N)"
    ["uninstall_cancelled"]="卸载已取消"
    ["removing_files"]="正在移除文件..."
    ["removing_install_dir"]="正在移除安装目录..."
    ["removing_user"]="正在移除用户..."
    ["config_not_removed"]="配置目录未被移除"
    ["remove_manually"]="如不再需要，请手动删除"
    ["removing_install_lock"]="正在移除安装锁文件..."
    ["install_lock_removed"]="安装锁文件已移除，重新安装时将进入初始化向导"
    ["purge_prompt"]="是否同时删除配置目录？这将删除所有配置和数据 [y/N]: "
    ["removing_config_dir"]="正在移除配置目录..."
    ["uninstall_complete"]="api-private-router 已卸载"
    ["usage"]="用法"
    ["cmd_none"]="(无参数)"
    ["cmd_install"]="安装 api-private-router"
    ["cmd_upgrade"]="升级到最新版本"
    ["cmd_uninstall"]="卸载 api-private-router"
    ["cmd_install_version"]="安装/回退到指定版本"
    ["cmd_list_versions"]="列出可用版本"
    ["opt_version"]="指定要安装的版本号 (例如: v1.0.0)"
    ["server_config_title"]="服务配置"
    ["server_config_desc"]="配置 api-private-router 服务监听地址"
    ["server_host_prompt"]="服务监听地址"
    ["server_host_hint"]="0.0.0.0 表示监听所有网卡，127.0.0.1 仅本地访问"
    ["server_port_prompt"]="服务端口"
    ["server_port_hint"]="建议使用 1024-65535 之间的端口"
    ["server_config_summary"]="服务配置"
    ["invalid_port"]="无效端口号，请输入 1-65535 之间的数字"
    ["enabling_autostart"]="正在设置开机自启..."
    ["autostart_enabled"]="开机自启已启用"
    ["getting_public_ip"]="正在获取公网 IP..."
    ["public_ip_failed"]="无法获取公网 IP，使用本地 IP"
    ["java_missing"]="未找到 Java 17+ 运行时"
    ["data_dir"]="数据目录"
)

declare -A MSG_EN=(
    ["info"]="INFO"
    ["success"]="SUCCESS"
    ["warning"]="WARNING"
    ["error"]="ERROR"
    ["select_lang"]="请选择语言 / Select language"
    ["lang_zh"]="中文"
    ["lang_en"]="English"
    ["enter_choice"]="Enter your choice (default: 1)"
    ["install_title"]="api-private-router Java Installation Script"
    ["run_as_root"]="Please run as root (use sudo)"
    ["detected_platform"]="Detected platform"
    ["unsupported_arch"]="Unsupported architecture"
    ["unsupported_os"]="Unsupported OS"
    ["missing_deps"]="Missing dependencies"
    ["install_deps_first"]="Please install them first"
    ["fetching_version"]="Fetching latest version..."
    ["latest_version"]="Latest version"
    ["failed_get_version"]="Failed to get latest version"
    ["downloading"]="Downloading"
    ["download_failed"]="Download failed"
    ["verifying_checksum"]="Verifying checksum..."
    ["checksum_verified"]="Checksum verified"
    ["checksum_failed"]="Checksum verification failed"
    ["checksum_not_found"]="Could not verify checksum (checksums.txt not found)"
    ["extracting"]="Extracting..."
    ["binary_installed"]="Application installed to"
    ["user_exists"]="User already exists"
    ["creating_user"]="Creating system user"
    ["user_created"]="User created"
    ["setting_up_dirs"]="Setting up directories..."
    ["dirs_configured"]="Directories configured"
    ["installing_service"]="Installing systemd service..."
    ["service_installed"]="Systemd service installed"
    ["service_started"]="Service started"
    ["service_start_failed"]="Service failed to start, please check logs"
    ["ready_for_setup"]="Ready for Setup Wizard"
    ["install_complete"]="api-private-router installation completed!"
    ["install_dir"]="Installation directory"
    ["next_steps"]="NEXT STEPS"
    ["step1_check_services"]="Make sure PostgreSQL and Redis are running:"
    ["step2_start_service"]="Start api-private-router service:"
    ["step3_enable_autostart"]="Enable auto-start on boot:"
    ["step4_open_wizard"]="Open the Setup Wizard in your browser:"
    ["wizard_guide"]="The Setup Wizard will guide you through:"
    ["wizard_db"]="Database configuration"
    ["wizard_redis"]="Redis configuration"
    ["wizard_admin"]="Admin account creation"
    ["useful_commands"]="USEFUL COMMANDS"
    ["cmd_status"]="Check status"
    ["cmd_logs"]="View logs"
    ["cmd_restart"]="Restart"
    ["cmd_stop"]="Stop"
    ["upgrading"]="Upgrading api-private-router..."
    ["current_version"]="Current version"
    ["stopping_service"]="Stopping service..."
    ["backup_created"]="Backup created"
    ["starting_service"]="Starting service..."
    ["upgrade_complete"]="Upgrade completed!"
    ["installing_version"]="Installing specified version"
    ["version_not_found"]="Specified version not found"
    ["same_version"]="Already at this version, no action needed"
    ["rollback_complete"]="Version rollback completed!"
    ["install_version_complete"]="Specified version installed!"
    ["validating_version"]="Validating version..."
    ["available_versions"]="Available versions"
    ["fetching_versions"]="Fetching available versions..."
    ["not_installed"]="api-private-router is not installed. Please run a fresh install first"
    ["fresh_install_hint"]="Usage"
    ["uninstall_confirm"]="This will remove api-private-router from your system."
    ["are_you_sure"]="Are you sure? (y/N)"
    ["uninstall_cancelled"]="Uninstall cancelled"
    ["removing_files"]="Removing files..."
    ["removing_install_dir"]="Removing installation directory..."
    ["removing_user"]="Removing user..."
    ["config_not_removed"]="Config directory was NOT removed."
    ["remove_manually"]="Remove it manually if you no longer need it."
    ["removing_install_lock"]="Removing install lock file..."
    ["install_lock_removed"]="Install lock removed. Setup wizard will appear on next install."
    ["purge_prompt"]="Also remove config directory? This will delete all config and data [y/N]: "
    ["removing_config_dir"]="Removing config directory..."
    ["uninstall_complete"]="api-private-router has been uninstalled"
    ["usage"]="Usage"
    ["cmd_none"]="(none)"
    ["cmd_install"]="Install api-private-router"
    ["cmd_upgrade"]="Upgrade to the latest version"
    ["cmd_uninstall"]="Remove api-private-router"
    ["cmd_install_version"]="Install/rollback to a specific version"
    ["cmd_list_versions"]="List available versions"
    ["opt_version"]="Specify version to install (e.g., v1.0.0)"
    ["server_config_title"]="Server Configuration"
    ["server_config_desc"]="Configure api-private-router server listen address"
    ["server_host_prompt"]="Server listen address"
    ["server_host_hint"]="0.0.0.0 listens on all interfaces, 127.0.0.1 for local only"
    ["server_port_prompt"]="Server port"
    ["server_port_hint"]="Recommended range: 1024-65535"
    ["server_config_summary"]="Server configuration"
    ["invalid_port"]="Invalid port number, please enter a number between 1-65535"
    ["enabling_autostart"]="Enabling auto-start on boot..."
    ["autostart_enabled"]="Auto-start enabled"
    ["getting_public_ip"]="Getting public IP..."
    ["public_ip_failed"]="Failed to get public IP, using local IP"
    ["java_missing"]="Java 17+ runtime not found"
    ["data_dir"]="Data directory"
)

msg() {
    local key="$1"
    if [ "$LANG_CHOICE" = "en" ]; then
        echo "${MSG_EN[$key]}"
    else
        echo "${MSG_ZH[$key]}"
    fi
}

print_info() { echo -e "${BLUE}[$(msg 'info')]${NC} $1"; }
print_success() { echo -e "${GREEN}[$(msg 'success')]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[$(msg 'warning')]${NC} $1"; }
print_error() { echo -e "${RED}[$(msg 'error')]${NC} $1"; }

is_interactive() {
    [ -e /dev/tty ] && [ -r /dev/tty ] && [ -w /dev/tty ]
}

select_language() {
    if ! is_interactive; then
        LANG_CHOICE="zh"
        return
    fi

    echo ""
    echo -e "${CYAN}=============================================="
    echo "  $(msg 'select_lang')"
    echo "==============================================${NC}"
    echo ""
    echo "  1) $(msg 'lang_zh') (default)"
    echo "  2) $(msg 'lang_en')"
    echo ""
    read -r -p "$(msg 'enter_choice'): " lang_input < /dev/tty
    case "$lang_input" in
        2|en|EN|english|English) LANG_CHOICE="en" ;;
        *) LANG_CHOICE="zh" ;;
    esac
    echo ""
}

validate_port() {
    local port="$1"
    [[ "$port" =~ ^[0-9]+$ ]] && [ "$port" -ge 1 ] && [ "$port" -le 65535 ]
}

configure_server() {
    if ! is_interactive; then
        print_info "$(msg 'server_config_summary'): ${SERVER_HOST}:${SERVER_PORT} (default)"
        return
    fi

    echo ""
    echo -e "${CYAN}=============================================="
    echo "  $(msg 'server_config_title')"
    echo "==============================================${NC}"
    echo ""
    echo -e "${BLUE}$(msg 'server_config_desc')${NC}"
    echo ""
    echo -e "${YELLOW}$(msg 'server_host_hint')${NC}"
    read -r -p "$(msg 'server_host_prompt') [${SERVER_HOST}]: " input_host < /dev/tty
    if [ -n "$input_host" ]; then
        SERVER_HOST="$input_host"
    fi
    echo ""
    echo -e "${YELLOW}$(msg 'server_port_hint')${NC}"
    while true; do
        read -r -p "$(msg 'server_port_prompt') [${SERVER_PORT}]: " input_port < /dev/tty
        if [ -z "$input_port" ]; then
            break
        elif validate_port "$input_port"; then
            SERVER_PORT="$input_port"
            break
        else
            print_error "$(msg 'invalid_port')"
        fi
    done
    echo ""
    print_info "$(msg 'server_config_summary'): ${SERVER_HOST}:${SERVER_PORT}"
    echo ""
}

check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        print_error "$(msg 'run_as_root')"
        exit 1
    fi
}

detect_platform() {
    OS=$(uname -s | tr '[:upper:]' '[:lower:]')
    ARCH=$(uname -m)

    case "$ARCH" in
        x86_64) ARCH="amd64" ;;
        aarch64|arm64) ARCH="arm64" ;;
        *)
            print_error "$(msg 'unsupported_arch'): $ARCH"
            exit 1
            ;;
    esac

    case "$OS" in
        linux) OS="linux" ;;
        darwin)
            print_error "$(msg 'unsupported_os'): $OS"
            exit 1
            ;;
        *)
            print_error "$(msg 'unsupported_os'): $OS"
            exit 1
            ;;
    esac

    print_info "$(msg 'detected_platform'): ${OS}_${ARCH}"
}

check_dependencies() {
    local missing=()
    if ! command -v curl >/dev/null 2>&1; then
        missing+=("curl")
    fi
    if ! command -v tar >/dev/null 2>&1; then
        missing+=("tar")
    fi
    if ! command -v sha256sum >/dev/null 2>&1; then
        missing+=("sha256sum")
    fi
    if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
        missing+=("$JAVA_BIN")
    fi
    if [ ${#missing[@]} -gt 0 ]; then
        print_error "$(msg 'missing_deps'): ${missing[*]}"
        print_info "$(msg 'install_deps_first')"
        exit 1
    fi
    if ! "$JAVA_BIN" -version 2>&1 | grep -Eq 'version "1[7-9]|version "2[0-9]|openjdk 17|openjdk version "17'; then
        print_error "$(msg 'java_missing')"
        exit 1
    fi
}

ensure_release_source() {
    if [ -z "$GITHUB_REPO" ]; then
        print_error "Release source is not configured"
        print_info "Set GITHUB_REPO to the repository path for your release source before running this installer"
        exit 1
    fi
}

get_latest_version() {
    ensure_release_source
    print_info "$(msg 'fetching_version')"
    LATEST_VERSION=$(curl -fsSL --connect-timeout 10 --max-time 30 "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
    if [ -z "$LATEST_VERSION" ]; then
        print_error "$(msg 'failed_get_version')"
        exit 1
    fi
    print_info "$(msg 'latest_version'): $LATEST_VERSION"
}

list_versions() {
    ensure_release_source
    print_info "$(msg 'fetching_versions')"
    local versions
    versions=$(curl -fsSL --connect-timeout 10 --max-time 30 "https://api.github.com/repos/${GITHUB_REPO}/releases" | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/' | head -20)
    if [ -z "$versions" ]; then
        print_error "$(msg 'failed_get_version')"
        exit 1
    fi
    echo ""
    echo "$(msg 'available_versions'):"
    echo "----------------------------------------"
    echo "$versions" | while read -r version; do
        echo "  $version"
    done
    echo "----------------------------------------"
    echo ""
}

validate_version() {
    local version="$1"
    ensure_release_source
    if [ -z "$version" ]; then
        print_error "$(msg 'opt_version')" >&2
        exit 1
    fi
    if [[ ! "$version" =~ ^v ]]; then
        version="v$version"
    fi
    print_info "$(msg 'validating_version') $version" >&2
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 10 --max-time 30 "https://api.github.com/repos/${GITHUB_REPO}/releases/tags/${version}" || true)
    if [ "$http_code" != "200" ]; then
        print_error "$(msg 'version_not_found'): $version" >&2
        echo "" >&2
        list_versions >&2
        exit 1
    fi
    echo "$version"
}

get_release_asset_name() {
    local version_num="${1#v}"
    echo "api-private-router_${version_num}_linux_java.tar.gz"
}

get_current_version() {
    if [ -f "$INSTALL_DIR/VERSION" ]; then
        tr -d '\r\n' < "$INSTALL_DIR/VERSION"
        return
    fi
    if [ -f "$INSTALL_DIR/api-private-router.jar" ]; then
        echo "unknown"
        return
    fi
    echo "not_installed"
}

download_and_extract() {
    local version="$LATEST_VERSION"
    local version_num="${version#v}"
    local archive_name
    archive_name=$(get_release_asset_name "$version")
    local download_url="https://github.com/${GITHUB_REPO}/releases/download/${version}/${archive_name}"
    local checksum_url="https://github.com/${GITHUB_REPO}/releases/download/${version}/checksums.txt"

    print_info "$(msg 'downloading') ${archive_name}..."
    TEMP_DIR=$(mktemp -d)
    trap 'rm -rf "$TEMP_DIR"' EXIT

    curl -fsSL "$download_url" -o "$TEMP_DIR/$archive_name" || {
        print_error "$(msg 'download_failed')"
        exit 1
    }

    print_info "$(msg 'verifying_checksum')"
    if curl -fsSL "$checksum_url" -o "$TEMP_DIR/checksums.txt" 2>/dev/null; then
        local expected_checksum
        expected_checksum=$(grep " ${archive_name}$" "$TEMP_DIR/checksums.txt" | awk '{print $1}')
        local actual_checksum
        actual_checksum=$(sha256sum "$TEMP_DIR/$archive_name" | awk '{print $1}')
        if [ -z "$expected_checksum" ] || [ "$expected_checksum" != "$actual_checksum" ]; then
            print_error "$(msg 'checksum_failed')"
            print_error "Expected: ${expected_checksum:-missing}"
            print_error "Actual: $actual_checksum"
            exit 1
        fi
        print_success "$(msg 'checksum_verified')"
    else
        print_warning "$(msg 'checksum_not_found')"
    fi

    print_info "$(msg 'extracting')"
    tar -xzf "$TEMP_DIR/$archive_name" -C "$TEMP_DIR"

    mkdir -p "$INSTALL_DIR"
    install -m 0644 "$TEMP_DIR/api-private-router.jar" "$INSTALL_DIR/api-private-router.jar"
    install -m 0644 "$TEMP_DIR/VERSION" "$INSTALL_DIR/VERSION"
    if [ -d "$TEMP_DIR/deploy" ]; then
        mkdir -p "$INSTALL_DIR/deploy"
        cp -R "$TEMP_DIR/deploy/." "$INSTALL_DIR/deploy/" 2>/dev/null || true
    fi
    print_success "$(msg 'binary_installed') $INSTALL_DIR/api-private-router.jar"
}

create_user() {
    if id "$SERVICE_USER" >/dev/null 2>&1; then
        print_info "$(msg 'user_exists'): $SERVICE_USER"
    else
        print_info "$(msg 'creating_user') $SERVICE_USER..."
        useradd -r -s /bin/sh -d "$INSTALL_DIR" "$SERVICE_USER"
        print_success "$(msg 'user_created')"
    fi
}

setup_directories() {
    print_info "$(msg 'setting_up_dirs')"
    mkdir -p "$INSTALL_DIR"
    mkdir -p "$INSTALL_DIR/data"
    mkdir -p "$CONFIG_DIR"
    chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
    chown -R "$SERVICE_USER:$SERVICE_USER" "$CONFIG_DIR"
    print_success "$(msg 'dirs_configured')"
}

install_service() {
    print_info "$(msg 'installing_service')"
    cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<EOF
[Unit]
Description=api-private-router - Application Runtime
After=network.target postgresql.service redis.service
Wants=postgresql.service redis.service

[Service]
Type=simple
User=${SERVICE_USER}
Group=${SERVICE_USER}
WorkingDirectory=${INSTALL_DIR}
ExecStart=${JAVA_BIN} -jar ${INSTALL_DIR}/api-private-router.jar
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal
SyslogIdentifier=${SERVICE_NAME}

NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=${INSTALL_DIR} ${CONFIG_DIR}

Environment=SERVER_HOST=${SERVER_HOST}
Environment=SERVER_PORT=${SERVER_PORT}
Environment=DATA_DIR=${DATA_DIR}
Environment=APP_BUILD_TYPE=release

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    print_success "$(msg 'service_installed')"
}

prepare_for_setup() {
    print_success "$(msg 'ready_for_setup')"
}

get_public_ip() {
    print_info "$(msg 'getting_public_ip')"
    local response
    response=$(curl -s --connect-timeout 5 --max-time 10 "https://ipinfo.io/json" 2>/dev/null || true)
    if [ -n "$response" ]; then
        PUBLIC_IP=$(echo "$response" | grep -o '"ip": *"[^"]*"' | sed 's/"ip": *"\([^"]*\)"/\1/')
        if [ -n "$PUBLIC_IP" ]; then
            print_success "Public IP: $PUBLIC_IP"
            return 0
        fi
    fi
    print_warning "$(msg 'public_ip_failed')"
    PUBLIC_IP=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "YOUR_SERVER_IP")
    return 1
}

start_service() {
    print_info "$(msg 'starting_service')"
    if systemctl start "$SERVICE_NAME"; then
        print_success "$(msg 'service_started')"
        return 0
    fi
    print_error "$(msg 'service_start_failed')"
    print_info "sudo journalctl -u ${SERVICE_NAME} -n 50"
    return 1
}

enable_autostart() {
    print_info "$(msg 'enabling_autostart')"
    if systemctl enable "$SERVICE_NAME" >/dev/null 2>&1; then
        print_success "$(msg 'autostart_enabled')"
    else
        print_warning "Failed to enable auto-start"
    fi
}

print_completion() {
    local display_host="${PUBLIC_IP:-YOUR_SERVER_IP}"
    if [ "$SERVER_HOST" = "127.0.0.1" ]; then
        display_host="127.0.0.1"
    fi

    echo ""
    echo "=============================================="
    print_success "$(msg 'install_complete')"
    echo "=============================================="
    echo ""
    echo "$(msg 'install_dir'): $INSTALL_DIR"
    echo "$(msg 'data_dir'): $DATA_DIR"
    echo "$(msg 'server_config_summary'): ${SERVER_HOST}:${SERVER_PORT}"
    echo ""
    echo "=============================================="
    echo "  $(msg 'step4_open_wizard')"
    echo "=============================================="
    echo ""
    print_info "     http://${display_host}:${SERVER_PORT}"
    echo ""
    echo "     $(msg 'wizard_guide')"
    echo "     - $(msg 'wizard_db')"
    echo "     - $(msg 'wizard_redis')"
    echo "     - $(msg 'wizard_admin')"
    echo ""
    echo "=============================================="
    echo "  $(msg 'useful_commands')"
    echo "=============================================="
    echo ""
    echo "  $(msg 'cmd_status'):   sudo systemctl status ${SERVICE_NAME}"
    echo "  $(msg 'cmd_logs'):     sudo journalctl -u ${SERVICE_NAME} -f"
    echo "  $(msg 'cmd_restart'):  sudo systemctl restart ${SERVICE_NAME}"
    echo "  $(msg 'cmd_stop'):     sudo systemctl stop ${SERVICE_NAME}"
    echo ""
    echo "=============================================="
}

backup_existing_install() {
    local current_version
    current_version=$(get_current_version)
    if [ -f "$INSTALL_DIR/api-private-router.jar" ]; then
        local suffix
        if [ "$current_version" != "unknown" ] && [ "$current_version" != "not_installed" ]; then
            suffix="$current_version"
        else
            suffix="$(date +%Y%m%d%H%M%S)"
        fi
        cp "$INSTALL_DIR/api-private-router.jar" "$INSTALL_DIR/api-private-router.jar.backup.${suffix}"
        if [ -f "$INSTALL_DIR/VERSION" ]; then
            cp "$INSTALL_DIR/VERSION" "$INSTALL_DIR/VERSION.backup.${suffix}"
        fi
        print_info "$(msg 'backup_created'): $INSTALL_DIR/api-private-router.jar.backup.${suffix}"
    fi
}

upgrade() {
    if [ ! -f "$INSTALL_DIR/api-private-router.jar" ]; then
        print_error "$(msg 'not_installed')"
        print_info "$(msg 'fresh_install_hint'): $0 install"
        exit 1
    fi

    print_info "$(msg 'upgrading')"
    local current_version
    current_version=$(get_current_version)
    print_info "$(msg 'current_version'): $current_version"

    if systemctl is-active --quiet "$SERVICE_NAME"; then
        print_info "$(msg 'stopping_service')"
        systemctl stop "$SERVICE_NAME"
    fi

    backup_existing_install
    get_latest_version
    download_and_extract
    chown "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR/api-private-router.jar" "$INSTALL_DIR/VERSION"
    print_info "$(msg 'starting_service')"
    systemctl start "$SERVICE_NAME"
    print_success "$(msg 'upgrade_complete')"
}

install_version() {
    local target_version="$1"
    if [ ! -f "$INSTALL_DIR/api-private-router.jar" ]; then
        print_error "$(msg 'not_installed')"
        print_info "$(msg 'fresh_install_hint'): $0 install -v $target_version"
        exit 1
    fi

    target_version=$(validate_version "$target_version")
    print_info "$(msg 'installing_version'): $target_version"
    local current_version
    current_version=$(get_current_version)
    print_info "$(msg 'current_version'): $current_version"
    if [ "$current_version" = "$target_version" ] || [ "$current_version" = "${target_version#v}" ]; then
        print_warning "$(msg 'same_version')"
        exit 0
    fi

    if systemctl is-active --quiet "$SERVICE_NAME"; then
        print_info "$(msg 'stopping_service')"
        systemctl stop "$SERVICE_NAME"
    fi

    backup_existing_install
    LATEST_VERSION="$target_version"
    download_and_extract
    chown "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR/api-private-router.jar" "$INSTALL_DIR/VERSION"
    print_info "$(msg 'starting_service')"
    if systemctl start "$SERVICE_NAME"; then
        print_success "$(msg 'install_version_complete')"
    else
        print_error "Failed to start service after version install"
        print_info "sudo journalctl -u ${SERVICE_NAME} -n 50"
    fi
}

uninstall() {
    print_warning "$(msg 'uninstall_confirm')"
    if ! is_interactive; then
        if [ "${FORCE_YES:-}" != "true" ]; then
            print_error "Non-interactive mode detected. Use 'curl ... | bash -s -- uninstall -y' to confirm."
            exit 1
        fi
    else
        read -r -p "$(msg 'are_you_sure') " -n 1 REPLY < /dev/tty
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            print_info "$(msg 'uninstall_cancelled')"
            exit 0
        fi
    fi

    print_info "$(msg 'stopping_service')"
    systemctl stop "$SERVICE_NAME" 2>/dev/null || true
    systemctl disable "$SERVICE_NAME" 2>/dev/null || true

    print_info "$(msg 'removing_files')"
    rm -f "/etc/systemd/system/${SERVICE_NAME}.service"
    systemctl daemon-reload

    print_info "$(msg 'removing_install_dir')"
    rm -rf "$INSTALL_DIR"

    print_info "$(msg 'removing_user')"
    userdel "$SERVICE_USER" 2>/dev/null || true

    print_info "$(msg 'removing_install_lock')"
    rm -f "$CONFIG_DIR/.installed" 2>/dev/null || true
    rm -f "$INSTALL_DIR/.installed" 2>/dev/null || true
    print_success "$(msg 'install_lock_removed')"

    local remove_config=false
    if [ "${PURGE:-}" = "true" ]; then
        remove_config=true
    elif is_interactive; then
        read -r -p "$(msg 'purge_prompt')" -n 1 REPLY < /dev/tty
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            remove_config=true
        fi
    fi

    if [ "$remove_config" = true ]; then
        print_info "$(msg 'removing_config_dir')"
        rm -rf "$CONFIG_DIR"
    else
        print_warning "$(msg 'config_not_removed'): $CONFIG_DIR"
        print_warning "$(msg 'remove_manually')"
    fi

    print_success "$(msg 'uninstall_complete')"
}

main() {
    local target_version=""
    local positional_args=()

    while [[ $# -gt 0 ]]; do
        case "$1" in
            -y|--yes)
                FORCE_YES="true"
                shift
                ;;
            --purge)
                PURGE="true"
                shift
                ;;
            -v|--version)
                if [ -n "${2:-}" ] && [[ ! "$2" =~ ^- ]]; then
                    target_version="$2"
                    shift 2
                else
                    echo "Error: --version requires a version argument"
                    exit 1
                fi
                ;;
            --version=*)
                target_version="${1#*=}"
                shift
                ;;
            *)
                positional_args+=("$1")
                shift
                ;;
        esac
    done

    set -- "${positional_args[@]}"
    select_language

    echo ""
    echo "=============================================="
    echo "       $(msg 'install_title')"
    echo "=============================================="
    echo ""

    case "${1:-}" in
        upgrade|update)
            check_root
            detect_platform
            check_dependencies
            if [ -n "$target_version" ]; then
                install_version "$target_version"
            else
                upgrade
            fi
            exit 0
            ;;
        install)
            check_root
            detect_platform
            check_dependencies
            if [ -n "$target_version" ]; then
                if [ -f "$INSTALL_DIR/api-private-router.jar" ]; then
                    install_version "$target_version"
                else
                    configure_server
                    LATEST_VERSION=$(validate_version "$target_version")
                    download_and_extract
                    create_user
                    setup_directories
                    install_service
                    prepare_for_setup
                    get_public_ip
                    start_service
                    enable_autostart
                    print_completion
                fi
            else
                configure_server
                get_latest_version
                download_and_extract
                create_user
                setup_directories
                install_service
                prepare_for_setup
                get_public_ip
                start_service
                enable_autostart
                print_completion
            fi
            exit 0
            ;;
        rollback)
            if [ -z "$target_version" ] && [ -n "${2:-}" ]; then
                target_version="$2"
            fi
            if [ -z "$target_version" ]; then
                print_error "$(msg 'opt_version')"
                echo ""
                echo "Usage: $0 rollback -v <version>"
                echo "       $0 rollback <version>"
                echo ""
                list_versions
                exit 1
            fi
            check_root
            detect_platform
            check_dependencies
            install_version "$target_version"
            exit 0
            ;;
        list-versions|versions)
            list_versions
            exit 0
            ;;
        uninstall|remove)
            check_root
            uninstall
            exit 0
            ;;
        --help|-h)
            echo "$(msg 'usage'): $0 [command] [options]"
            echo ""
            echo "Commands:"
            echo "  $(msg 'cmd_none')            $(msg 'cmd_install')"
            echo "  install              $(msg 'cmd_install')"
            echo "  upgrade              $(msg 'cmd_upgrade')"
            echo "  rollback <version>   $(msg 'cmd_install_version')"
            echo "  list-versions        $(msg 'cmd_list_versions')"
            echo "  uninstall            $(msg 'cmd_uninstall')"
            echo ""
            echo "Options:"
            echo "  -v, --version <ver>  $(msg 'opt_version')"
            echo "  -y, --yes            Skip confirmation prompts (for uninstall)"
            echo ""
            exit 0
            ;;
    esac

    check_root
    detect_platform
    check_dependencies
    if [ -n "$target_version" ]; then
        if [ -f "$INSTALL_DIR/api-private-router.jar" ]; then
            install_version "$target_version"
        else
            configure_server
            LATEST_VERSION=$(validate_version "$target_version")
            download_and_extract
            create_user
            setup_directories
            install_service
            prepare_for_setup
            get_public_ip
            start_service
            enable_autostart
            print_completion
        fi
    else
        configure_server
        get_latest_version
        download_and_extract
        create_user
        setup_directories
        install_service
        prepare_for_setup
        get_public_ip
        start_service
        enable_autostart
        print_completion
    fi
}

main "$@"
