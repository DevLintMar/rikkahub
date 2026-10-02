#!/bin/sh
# RikkaHub Workspace Environment Setup - Diagnostic script

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

printf "${CYAN}=== RikkaHub Workspace Diagnostics ===${NC}\n"

# 1. 系统与架构
ARCH="$(uname -m)"
OS_DESC="Unknown"
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS_DESC="${PRETTY_NAME:-$ID}"
fi
printf "OS:           %s (%s)\n" "$OS_DESC" "$ARCH"

# 2. 软件源检查
printf "Package Source: "
if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
    if grep -q "mirrors.tuna.tsinghua.edu.cn" /etc/apt/sources.list.d/ubuntu.sources; then
        printf "${GREEN}Tsinghua Mirror (DEB822)${NC}\n"
    else
        printf "${YELLOW}Official/Default (DEB822)${NC}\n"
    fi
elif [ -f /etc/apt/sources.list ]; then
    if grep -q "mirrors.tuna.tsinghua.edu.cn" /etc/apt/sources.list; then
        printf "${GREEN}Tsinghua Mirror (APT)${NC}\n"
    else
        printf "${YELLOW}Official/Default (APT)${NC}\n"
    fi
elif [ -f /etc/apk/repositories ]; then
    if grep -q "mirrors.tuna.tsinghua.edu.cn" /etc/apk/repositories; then
        printf "${GREEN}Tsinghua Mirror (APK)${NC}\n"
    else
        printf "${YELLOW}Official/Default (APK)${NC}\n"
    fi
else
    printf "${RED}No standard source file found${NC}\n"
fi

# 3. CA 证书状态
printf "CA Certs:     "
if [ -s /etc/ssl/certs/ca-certificates.crt ]; then
    CERT_COUNT=$(grep -c "BEGIN CERTIFICATE" /etc/ssl/certs/ca-certificates.crt 2>/dev/null || echo "ok")
    printf "${GREEN}Present (%s certificates)${NC}\n" "$CERT_COUNT"
else
    printf "${RED}Missing or empty (/etc/ssl/certs/ca-certificates.crt)${NC}\n"
fi

# 4. Python 环境
printf "Python:       "
if command -v python3 >/dev/null 2>&1; then
    PY_VER="$(python3 --version 2>&1)"
    printf "${GREEN}%s${NC}\n" "$PY_VER"
else
    printf "${YELLOW}Not installed${NC}\n"
fi

# 5. 虚拟环境 (/workspace/.venv/default)
printf "Default Venv: "
if [ -d "/workspace/.venv/default" ] && [ -f "/workspace/.venv/default/bin/activate" ]; then
    PIP_INDEX="$("/workspace/.venv/default/bin/pip" config get global.index-url 2>/dev/null || echo "default")"
    printf "${GREEN}Active (/workspace/.venv/default, index: %s)${NC}\n" "$PIP_INDEX"
else
    printf "${YELLOW}Not created${NC}\n"
fi

# 6. 磁盘空间
printf "Disk Space:   "
DF_OUT="$(df -h /workspace 2>/dev/null || df -h / 2>/dev/null | tail -n 1)"
printf "%s\n" "$(echo "$DF_OUT" | awk '{print $4 " available on " $6}')"

printf "${CYAN}=======================================${NC}\n"
