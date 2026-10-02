#!/bin/sh
# RikkaHub Workspace Environment Setup - Tsinghua (TUNA) Mirror Switcher
# Safe three-step switcher: HTTP -> Install ca-certificates & curl -> Upgrade to HTTPS

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() {
    printf "${GREEN}[INFO]${NC} %s\n" "$1"
}

log_warn() {
    printf "${YELLOW}[WARN]${NC} %s\n" "$1"
}

log_err() {
    printf "${RED}[ERROR]${NC} %s\n" "$1"
}

# 1. 架构检测 (arm64/aarch64 对应 ubuntu-ports, x86_64 对应 ubuntu)
ARCH="$(uname -m)"
case "$ARCH" in
    aarch64|arm64)
        UBUNTU_PATH="ubuntu-ports"
        ;;
    x86_64|amd64)
        UBUNTU_PATH="ubuntu"
        ;;
    armv7*|armhf)
        UBUNTU_PATH="ubuntu-ports"
        ;;
    *)
        UBUNTU_PATH="ubuntu-ports"
        ;;
esac

log_info "Detected architecture: $ARCH (Ubuntu repo path: $UBUNTU_PATH)"

# 2. 发行版检测
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS_ID="$ID"
    OS_CODENAME="${VERSION_CODENAME:-}"
else
    log_err "Cannot find /etc/os-release. Unknown Linux distribution."
    exit 1
fi

log_info "Detected OS: $OS_ID ($OS_CODENAME)"

# 3. 三步法实施
case "$OS_ID" in
    ubuntu)
        DEB822_FILE="/etc/apt/sources.list.d/ubuntu.sources"
        TRADITIONAL_FILE="/etc/apt/sources.list"

        # 判断是否为 Ubuntu 24.04 (noble) DEB822 格式
        if [ -f "$DEB822_FILE" ]; then
            log_info "Ubuntu DEB822 format detected: $DEB822_FILE"
            if [ ! -f "${DEB822_FILE}.bak" ]; then
                cp "$DEB822_FILE" "${DEB822_FILE}.bak"
                log_info "Backup created: ${DEB822_FILE}.bak"
            fi

            # Step 1: 写入明文 HTTP 清华源 (规避无 CA 证书死锁)
            log_info "Step 1/3: Configuring plain HTTP Tsinghua mirror..."
            sed -i -E "s|URIs: https?://[^/]+/ubuntu/?|URIs: http://mirrors.tuna.tsinghua.edu.cn/${UBUNTU_PATH}/|g" "$DEB822_FILE"
            sed -i -E "s|URIs: https?://[^/]+/ubuntu-ports/?|URIs: http://mirrors.tuna.tsinghua.edu.cn/${UBUNTU_PATH}/|g" "$DEB822_FILE"

            # Step 2: HTTP 下更新并安装 CA 证书
            log_info "Step 2/3: Updating apt cache and installing ca-certificates curl..."
            apt-get update -y
            apt-get install -y --no-install-recommends ca-certificates curl

            # Step 3: 平滑升级为加密 HTTPS 清华源
            log_info "Step 3/3: Upgrading to secure HTTPS Tsinghua mirror..."
            sed -i -E "s|URIs: http://mirrors.tuna.tsinghua.edu.cn/|URIs: https://mirrors.tuna.tsinghua.edu.cn/|g" "$DEB822_FILE"
            if apt-get update -y; then
                log_info "Successfully switched to HTTPS Tsinghua mirror!"
            else
                log_warn "HTTPS update failed, falling back to HTTP mirror..."
                sed -i -E "s|URIs: https://mirrors.tuna.tsinghua.edu.cn/|URIs: http://mirrors.tuna.tsinghua.edu.cn/|g" "$DEB822_FILE"
                apt-get update -y
            fi
        else
            log_info "Ubuntu traditional format detected: $TRADITIONAL_FILE"
            if [ ! -f "${TRADITIONAL_FILE}.bak" ]; then
                cp "$TRADITIONAL_FILE" "${TRADITIONAL_FILE}.bak"
                log_info "Backup created: ${TRADITIONAL_FILE}.bak"
            fi

            # Step 1: 写入明文 HTTP 清华源
            log_info "Step 1/3: Configuring plain HTTP Tsinghua mirror..."
            sed -i -E "s|https?://[a-zA-Z0-9._-]+/ubuntu/?|http://mirrors.tuna.tsinghua.edu.cn/${UBUNTU_PATH}/|g" "$TRADITIONAL_FILE"
            sed -i -E "s|https?://[a-zA-Z0-9._-]+/ubuntu-ports/?|http://mirrors.tuna.tsinghua.edu.cn/${UBUNTU_PATH}/|g" "$TRADITIONAL_FILE"

            # Step 2: HTTP 下更新并安装 CA 证书
            log_info "Step 2/3: Updating apt cache and installing ca-certificates curl..."
            apt-get update -y
            apt-get install -y --no-install-recommends ca-certificates curl

            # Step 3: 升级为 HTTPS 清华源
            log_info "Step 3/3: Upgrading to secure HTTPS Tsinghua mirror..."
            sed -i -E "s|http://mirrors.tuna.tsinghua.edu.cn/|https://mirrors.tuna.tsinghua.edu.cn/|g" "$TRADITIONAL_FILE"
            if apt-get update -y; then
                log_info "Successfully switched to HTTPS Tsinghua mirror!"
            else
                log_warn "HTTPS update failed, falling back to HTTP mirror..."
                sed -i -E "s|https://mirrors.tuna.tsinghua.edu.cn/|http://mirrors.tuna.tsinghua.edu.cn/|g" "$TRADITIONAL_FILE"
                apt-get update -y
            fi
        fi
        ;;

    debian)
        TRADITIONAL_FILE="/etc/apt/sources.list"
        if [ ! -f "${TRADITIONAL_FILE}.bak" ]; then
            cp "$TRADITIONAL_FILE" "${TRADITIONAL_FILE}.bak"
            log_info "Backup created: ${TRADITIONAL_FILE}.bak"
        fi

        # Step 1: 写入明文 HTTP 清华源
        log_info "Step 1/3: Configuring plain HTTP Tsinghua mirror..."
        sed -i -E "s|https?://deb.debian.org/debian/?|http://mirrors.tuna.tsinghua.edu.cn/debian/|g" "$TRADITIONAL_FILE"
        sed -i -E "s|https?://security.debian.org/debian-security/?|http://mirrors.tuna.tsinghua.edu.cn/debian-security/|g" "$TRADITIONAL_FILE"

        # Step 2: HTTP 下更新并安装 CA 证书
        log_info "Step 2/3: Updating apt cache and installing ca-certificates curl..."
        apt-get update -y
        apt-get install -y --no-install-recommends ca-certificates curl

        # Step 3: 升级为 HTTPS 清华源
        log_info "Step 3/3: Upgrading to secure HTTPS Tsinghua mirror..."
        sed -i -E "s|http://mirrors.tuna.tsinghua.edu.cn/|https://mirrors.tuna.tsinghua.edu.cn/|g" "$TRADITIONAL_FILE"
        if apt-get update -y; then
            log_info "Successfully switched to HTTPS Tsinghua mirror!"
        else
            log_warn "HTTPS update failed, falling back to HTTP mirror..."
            sed -i -E "s|https://mirrors.tuna.tsinghua.edu.cn/|http://mirrors.tuna.tsinghua.edu.cn/|g" "$TRADITIONAL_FILE"
            apt-get update -y
        fi
        ;;

    alpine)
        REPOS_FILE="/etc/apk/repositories"
        if [ ! -f "${REPOS_FILE}.bak" ]; then
            cp "$REPOS_FILE" "${REPOS_FILE}.bak"
            log_info "Backup created: ${REPOS_FILE}.bak"
        fi

        # Step 1: 写入明文 HTTP 清华源
        log_info "Step 1/3: Configuring plain HTTP Tsinghua mirror..."
        sed -i -E "s|https?://dl-cdn.alpinelinux.org/alpine/|http://mirrors.tuna.tsinghua.edu.cn/alpine/|g" "$REPOS_FILE"

        # Step 2: HTTP 下更新并安装 CA 证书
        log_info "Step 2/3: Updating apk cache and installing ca-certificates curl..."
        apk update
        apk add --no-cache ca-certificates curl

        # Step 3: 升级为 HTTPS 清华源
        log_info "Step 3/3: Upgrading to secure HTTPS Tsinghua mirror..."
        sed -i -E "s|http://mirrors.tuna.tsinghua.edu.cn/|https://mirrors.tuna.tsinghua.edu.cn/|g" "$REPOS_FILE"
        if apk update; then
            log_info "Successfully switched to HTTPS Tsinghua mirror!"
        else
            log_warn "HTTPS update failed, falling back to HTTP mirror..."
            sed -i -E "s|https://mirrors.tuna.tsinghua.edu.cn/|http://mirrors.tuna.tsinghua.edu.cn/|g" "$REPOS_FILE"
            apk update
        fi
        ;;

    *)
        log_err "Unsupported Linux distribution: $OS_ID"
        exit 1
        ;;
esac

log_info "Mirror configuration complete."
